#!/usr/bin/env node
// Renders film.html to video, one frame at a time, by driving headless Chrome over the
// DevTools protocol. No dependencies: Node 22's own WebSocket, the Chrome already on this
// machine, and ffmpeg. Frames are asked for by time, so they can be rendered in any order
// and shared between tabs.
//
//   node render.mjs                         the whole film, 1080p60, into out/gander-film.mp4
//   node render.mjs --scale 2               rendered at 4K and brought down, for clean edges
//   node render.mjs --from 8 --to 14        part of it
//   node render.mjs --stills 3.2,9.5        single full-size frames into out/stills/
//   node render.mjs --sheet 0,8,12          twelve small frames from 0s to 8s as one picture
//   node render.mjs --check                 prove that a frame depends on nothing but its time
//   node render.mjs --cues                  write out/cues.json, the timeline the soundtrack is cut to
//   node render.mjs --tall --scale 2 --keyframes 60            the phone cut, 1080x1920, for Instagram
//   node render.mjs --film ../ten-thousand-film/film.html --size 1080x1350      a film beside this one, in its own frame
//
// Options: --fps 60  --workers 6  --crf 14  --out path.mp4  --keep (leave the frames behind)

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, writeFileSync, copyFileSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf("--" + name);
  return i < 0 ? dflt : args[i + 1] && !args[i + 1].startsWith("--") ? args[i + 1] : true;
};
const FPS = Number(opt("fps", 60));
const SCALE = Number(opt("scale", 1));
const WORKERS = Number(opt("workers", 6));
const CRF = String(opt("crf", 14));
const TALL = Boolean(opt("tall"));
// A second film drawn with this engine is rendered by this same script: --film names its
// page and --size its frame, and its frames go into the out/ beside that page. Without
// them this is the film in this directory, wide or tall, as before.
const FILM = opt("film") ? resolve(String(opt("film"))) : join(HERE, "film.html");
const THERE = dirname(FILM);
const [VW, VH] = opt("size") ? String(opt("size")).split("x").map(Number) : TALL ? [1080, 1920] : [1920, 1080];
const OUT = resolve(String(opt("out", join(THERE, "out", TALL ? "gander-film-tall.mp4" : "gander-film.mp4"))));
const CHROME = process.env.CHROME || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PAGE = pathToFileURL(FILM).href + "?render" + (TALL ? "&tall" : "") + (opt("nograin") ? "&nograin" : "") + (opt("nofx") ? "&nofx" : "");

if (!existsSync(join(HERE, "fonts", "Jost.ttf"))) {
  console.error("fonts/Jost.ttf is missing. See README.md: the film will not render in a fallback face.");
  process.exit(1);
}

// ---- a very small DevTools client ---------------------------------------------------------
class CDP {
  static connect(url) {
    return new Promise((ok, bad) => {
      const ws = new WebSocket(url);
      const c = new CDP(ws);
      ws.addEventListener("open", () => ok(c));
      ws.addEventListener("error", (e) => bad(new Error("websocket: " + (e.message || "failed"))));
    });
  }
  constructor(ws) {
    this.ws = ws;
    this.n = 0;
    this.waiting = new Map();
    ws.addEventListener("message", (m) => {
      const msg = JSON.parse(m.data);
      if (!msg.id) return;
      const w = this.waiting.get(msg.id);
      if (!w) return;
      this.waiting.delete(msg.id);
      msg.error ? w.bad(new Error(msg.error.message)) : w.ok(msg.result);
    });
  }
  send(method, params = {}, sessionId) {
    const id = ++this.n;
    return new Promise((ok, bad) => {
      this.waiting.set(id, { ok, bad });
      this.ws.send(JSON.stringify({ id, method, params, sessionId }));
    });
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A render that is interrupted must not leave a headless Chrome running or a few gigabytes
// of frames behind in the temp directory, so everything made here is remembered and undone.
const temps = [];
let chrome = null;
const mktemp = (prefix) => { const d = mkdtempSync(join(tmpdir(), prefix)); temps.push(d); return d; };
const cleanup = () => {
  try { chrome?.kill("SIGKILL"); } catch {}
  for (const d of temps.splice(0)) try { rmSync(d, { recursive: true, force: true }); } catch {}
};
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) process.on(sig, () => { cleanup(); process.exit(130); });

async function launch() {
  const profile = mktemp("gander-film-");
  const proc = spawn(CHROME, [
    "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "--hide-scrollbars",
    "--mute-audio", "--no-first-run", "--force-color-profile=srgb", "--font-render-hinting=none",
    "--allow-file-access-from-files", "--disable-background-timer-throttling", "about:blank",
  ], { stdio: ["ignore", "ignore", "pipe"] });
  chrome = proc;
  const wsUrl = await new Promise((ok, bad) => {
    let buf = "";
    proc.stderr.on("data", (d) => {
      buf += d;
      const m = buf.match(/DevTools listening on (ws:\/\/\S+)/);
      if (m) ok(m[1]);
    });
    proc.on("exit", () => bad(new Error("Chrome exited before DevTools came up:\n" + buf)));
    setTimeout(() => bad(new Error("Chrome did not start within 20s")), 20000);
  });
  return { proc, profile, cdp: await CDP.connect(wsUrl) };
}

async function openTab(cdp, scale) {
  const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
  const s = (m, p) => cdp.send(m, p, sessionId);
  await s("Page.enable");
  await s("Runtime.enable");
  await s("Emulation.setDeviceMetricsOverride", { width: VW, height: VH, deviceScaleFactor: scale, mobile: false });
  await s("Emulation.setDefaultBackgroundColorOverride", { color: { r: 0, g: 0, b: 0, a: 255 } });
  await s("Page.navigate", { url: PAGE });
  for (let i = 0; ; i++) {
    const r = await s("Runtime.evaluate", { expression: "window.__ready===true", returnByValue: true });
    if (r.result.value === true) break;
    if (i > 400) throw new Error("film.html never became ready");
    await sleep(50);
  }
  const shot = async (t) => {
    const r = await s("Runtime.evaluate", { expression: `F.renderAt(${t})`, returnByValue: true });
    if (r.exceptionDetails) throw new Error(`renderAt(${t}): ` + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    const { data } = await s("Page.captureScreenshot", { format: "png", optimizeForSpeed: true });
    return Buffer.from(data, "base64");
  };
  await shot(0); // a throwaway, so the grain image and the fonts are decoded before frame one
  const dur = (await s("Runtime.evaluate", { expression: "F.DUR", returnByValue: true })).result.value;
  const evaluate = async (expression) => (await s("Runtime.evaluate", { expression, returnByValue: true })).result.value;
  return { shot, dur, evaluate };
}

function run(cmd, argv) {
  return new Promise((ok, bad) => {
    const p = spawn(cmd, argv, { stdio: ["ignore", "ignore", "pipe"] });
    let err = "";
    p.stderr.on("data", (d) => (err += d));
    p.on("exit", (code) => (code === 0 ? ok(err) : bad(new Error(`${cmd} exited ${code}\n${err.slice(-2000)}`))));
  });
}

const { proc, profile, cdp } = await launch();
let failed = null;
try {
  if (opt("stills")) {
    const dir = join(THERE, "out", "stills");
    mkdirSync(dir, { recursive: true });
    const tab = await openTab(cdp, SCALE);
    for (const t of String(opt("stills")).split(",").map(Number)) {
      const f = join(dir, `t${t.toFixed(2).padStart(6, "0")}.png`);
      writeFileSync(f, await tab.shot(t));
      console.log(f);
    }
  } else if (opt("cues")) {
    const tab = await openTab(cdp, 1);
    const json = await tab.evaluate("JSON.stringify({ fps: F.FPS, duration: F.DUR, cues: F.cues.slice().sort((a, b) => a.t - b.t) }, null, 1)");
    mkdirSync(join(THERE, "out"), { recursive: true });
    writeFileSync(join(THERE, "out", "cues.json"), json + "\n");
    console.log(`${JSON.parse(json).cues.length} cues in out/cues.json`);
  } else if (opt("check")) {
    // Two tabs, each loaded fresh, draw the same times in opposite orders. If any frame
    // differs between them, something on the page is carrying state from one frame to the
    // next, and a render shared between tabs would no longer match the player.
    // At the film's own scale, not a smaller one: below 1 the grain tile is resampled, and
    // Chrome does not resample it identically in two tabs, which fails every frame for a
    // reason that has nothing to do with the film.
    const a = await openTab(cdp, 1), b = await openTab(cdp, 1);
    const times = [];
    for (let t = 0.13; t < a.dur; t += 0.37) times.push(Number(t.toFixed(3)));
    const dir = mktemp("gander-check-");
    const name = (side, i) => join(dir, `${side}${String(i).padStart(4, "0")}.png`);
    const sum = (buf) => createHash("sha256").update(buf).digest("hex");
    const fwd = [], back = [];
    for (const [i, t] of times.entries()) { const buf = await a.shot(t); writeFileSync(name("a", i), buf); fwd[i] = sum(buf); }
    for (let i = times.length - 1; i >= 0; i--) { const buf = await b.shot(times[i]); writeFileSync(name("b", i), buf); back[i] = sum(buf); }
    // Not every mismatch is the film's doing. Chrome's glyph rasteriser can land a few pixels
    // of large type a level or two apart depending on what it drew before, which no eye and
    // no encoder will ever see. So a mismatch is measured: that noise is above 90 dB, and a
    // real leak (a pose that drifted, a neck that went missing) is nowhere near it.
    const differs = times.map((_, i) => i).filter((i) => fwd[i] !== back[i]), bad = [];
    for (const i of differs) {
      const err = await run("ffmpeg", ["-hide_banner", "-i", name("a", i), "-i", name("b", i), "-lavfi", "psnr", "-f", "null", "-"]);
      const m = err.match(/average:(inf|[\d.]+)/);
      if (!m || (m[1] !== "inf" && Number(m[1]) < 70)) bad.push(i);
    }
    if (differs.length) console.log(`${differs.length - bad.length} frames differ only by rasteriser noise (a few pixels, a level or two)`);
    if (bad.length) {
      // Keep both drawings of the first frame that differs, so the difference can be looked at.
      mkdirSync(join(THERE, "out"), { recursive: true });
      copyFileSync(name("a", bad[0]), join(THERE, "out", "check-forwards.png"));
      copyFileSync(name("b", bad[0]), join(THERE, "out", "check-backwards.png"));
    }
    rmSync(dir, { recursive: true, force: true });
    if (bad.length) throw new Error(`${bad.length} of ${times.length} frames depend on the order they were drawn in:\n  ${bad.map((i) => times[i]).join(" ")}\n  the first is in out/check-forwards.png and out/check-backwards.png`);
    console.log(`${times.length} frames drawn forwards and backwards: none depends on the order it was drawn in`);
  } else if (opt("sheet")) {
    const [a, b, n] = String(opt("sheet")).split(",").map(Number);
    const cols = Number(opt("cols", 4));
    const dir = mktemp("gander-sheet-");
    const tab = await openTab(cdp, Number(opt("sheetscale", 0.25)));
    for (let i = 0; i < n; i++) writeFileSync(join(dir, `${String(i).padStart(3, "0")}.png`), await tab.shot(a + ((b - a) * i) / Math.max(1, n - 1)));
    const out = join(THERE, "out", `sheet-${a}-${b}.png`);
    mkdirSync(dirname(out), { recursive: true });
    await run("ffmpeg", ["-y", "-loglevel", "error", "-framerate", "1", "-i", join(dir, "%03d.png"), "-vf", `tile=${cols}x${Math.ceil(n / cols)}:padding=6:color=0x222222`, "-frames:v", "1", out]);
    rmSync(dir, { recursive: true, force: true });
    console.log(out);
  } else {
    const first = await openTab(cdp, SCALE);
    const from = Number(opt("from", 0)), to = Number(opt("to", first.dur));
    const f0 = Math.round(from * FPS), f1 = Math.round(to * FPS), total = f1 - f0;
    const frames = mktemp("gander-frames-");
    const tabs = [first];
    while (tabs.length < Math.min(WORKERS, total)) tabs.push(await openTab(cdp, SCALE));
    console.log(`${total} frames at ${FPS} fps, ${VW * SCALE}x${VH * SCALE}, ${tabs.length} tabs`);
    const began = Date.now();
    let next = 0, done = 0;
    await Promise.all(tabs.map(async (tab) => {
      for (;;) {
        const i = next++;
        if (i >= total) return;
        writeFileSync(join(frames, `${String(i).padStart(6, "0")}.png`), await tab.shot((f0 + i) / FPS));
        if (++done % 120 === 0 || done === total) {
          const el = (Date.now() - began) / 1000;
          console.log(`  ${done}/${total}  ${(done / el).toFixed(1)} fps  ${Math.round(((total - done) / done) * el)}s left`);
        }
      }
    }));
    mkdirSync(dirname(OUT), { recursive: true });
    // RGB to BT.709 limited range, said out loud: left to itself ffmpeg converts with the
    // BT.601 matrix, and the coral comes out a different orange on every player that
    // assumes 709 for HD.
    const encode = (w, h, level, out) => run("ffmpeg", ["-y", "-loglevel", "error", "-framerate", String(FPS), "-i", join(frames, "%06d.png"),
      "-vf", `scale=${w}:${h}:flags=lanczos:in_range=full:out_range=tv:out_color_matrix=bt709,format=yuv420p`,
      "-c:v", "libx264", "-preset", "slow", "-crf", CRF, "-profile:v", "high", "-level", level,
      // A keyframe where a cut is wanted later, so that the film can be split there without re-encoding.
      ...(opt("keyframes") ? ["-force_key_frames", String(opt("keyframes"))] : []),
      "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
      "-movflags", "+faststart", "-an", out]);
    await encode(VW, VH, "4.2", OUT);
    console.log(OUT);
    // The same frames at full size, for anywhere that re-encodes what it is given: a 4K
    // upload gets a better 1080p stream out of YouTube than a 1080p upload does.
    if (opt("uhd") && SCALE >= 2) {
      const uhd = OUT.replace(/\.mp4$/, "-2160p.mp4");
      await encode(VW * 2, VH * 2, "5.2", uhd);
      console.log(uhd);
    }
    if (opt("keep")) { temps.splice(temps.indexOf(frames), 1); console.log("frames kept in " + frames); }
  }
} catch (e) {
  failed = e;
} finally {
  try { await cdp.send("Browser.close"); } catch {}
  await sleep(200);
  cleanup();
}
if (failed) {
  console.error(failed.message);
  process.exit(1);
}
