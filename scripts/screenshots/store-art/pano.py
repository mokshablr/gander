#!/usr/bin/env python3
"""Concept C - one canvas W*N wide, sliced into N 1080x1920 Play frames.

Continuity is carried by three things that cross every seam: a single
gradient across the whole canvas, an angled terracotta ribbon, and a band
of format tiles that runs the full width. Each panel still holds one
complete, legible capture so it survives being shown alone.
"""
import pathlib
RAW="/Users/arjun.maniyani/Desktop/Arjun/Projects/gander/docs/screenshots/v1.14/raw"
import os
W,H,N = 1080,1920,(7 if os.path.exists('/Users/arjun.maniyani/Desktop/Arjun/Projects/gander/docs/screenshots/v1.14/raw/d-folder.png') else 6)
CW = W*N

# drift.py carries the tile/card/sweep vocabulary and still defaults to the 6480px
# six-frame canvas it was written against. Repoint its constants the way tabpano.py
# does, so the two sets keep one implementation and the bands run the full width.
# Without this the seventh frame gets no ribbon, no procession and no cards: they
# all stop dead at x=6480, which is exactly its left edge. Read at call time, so
# mutating the module before the generators run is enough.
import drift
drift.CW, drift.H = CW, H

BADGE=["#B3261E","#1565C0","#2E7D32","#B25000","#7B1FA2","#AD1457","#00838F","#455A64","#616161"]

def tiles(y, size, gap, rot):
    out=[]; x=-260; i=0
    while x < CW+320:
        c=BADGE[i%len(BADGE)]
        out.append(f'<div class="tl" style="left:{x}px;top:{y}px;width:{size}px;height:{size}px;background:{c}"></div>')
        x+=size+gap; i+=1
    return (f'<div class="band" style="transform:rotate({rot}deg)">' + "".join(out) + "</div>")

def win(x,y,w,h,src,cx,cy,s,rot=0,cls="soft"):
    tx,ty=-cx*s,-cy*s
    r=f"transform:rotate({rot}deg);" if rot else ""
    return (f'<div class="win {cls}" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;{r}">'
            f'<img src="{RAW}/{src}" style="transform:translate({tx:.1f}px,{ty:.1f}px) scale({s})"></div>')

def phone(x, y, w, h, src, top=0, z=7, rot=0.0, patches=()):
    """A device showing the screen from `top` downward, bleeding off the panel bottom."""
    inner = w - 28
    scale = inner / 1080.0
    r=f"transform:rotate({rot:.2f}deg);transform-origin:50% 30%;" if rot else ""
    return (f'<div class="phone" style="left:{x}px;top:{y}px;width:{w}px;height:{h}px;z-index:{z};{r}">'
            f'<div class="screen"><img src="{RAW}/{src}" style="top:{-top*scale:.1f}px">'
            + "".join(
                f'<div style="position:absolute;left:{px*scale:.1f}px;top:{(py-top)*scale:.1f}px;'
                f'width:{pw*scale:.1f}px;height:{ph*scale:.1f}px;background:{pc}"></div>'
                for (px,py,pw,ph,pc) in patches)
            + f'<div class="sheen"></div></div></div>')

def fragment(x, y, w, src, cx, cy, cw, ch, rot=0.0, z=14):
    """A control lifted out of the same capture and magnified, floated in front of
    the device. Same shadow language as the phone, so it reads as one object family."""
    scale = w / float(cw)
    h = ch * scale
    tx, ty = -cx*scale, -cy*scale
    r = f"transform:rotate({rot:.2f}deg);" if rot else ""
    return (f'<div class="frag" style="left:{x}px;top:{y}px;width:{w}px;height:{h:.0f}px;z-index:{z};{r}">'
            f'<img src="{RAW}/{src}" style="width:{1080*scale:.1f}px;'
            f'transform:translate({tx:.1f}px,{ty:.1f}px)"></div>')

def cap(i,a,b,kick):
    return (f'<div class="head" style="left:{i*W+76}px">'
            f'<h1>{a}<br><span class="hot">{b}</span></h1><p class="kick">{kick}</p></div>')

CSS=f"""
:root{{--ink:#F8EFE0;--hot:#E2795F;--dim:#CFC5B2}}
*{{box-sizing:border-box;margin:0}}
body{{margin:0;background:#000}}
.canvas{{position:relative;width:{CW}px;height:{H}px;overflow:hidden;font-family:Jost,sans-serif;
  color:var(--ink);
  background:
    radial-gradient(2200px 1200px at 12% -10%, rgba(226,121,95,.10), transparent 62%),
    radial-gradient(2400px 1400px at 78% 118%, rgba(120,150,210,.07), transparent 62%),
    linear-gradient(97deg,#1A140D 0%,#131009 30%,#191309 58%,#120E08 82%,#0E0B06 100%);}}
/* warm light pooled under each capture, so a card sits in something rather than on nothing */
.glow{{position:absolute;z-index:3;pointer-events:none;
  background:radial-gradient(closest-side, rgba(255,176,140,.17), transparent 72%)}}
/* edges: a vignette stops the strip reading as a row of flat swatches */
.vig{{position:absolute;left:0;top:0;width:{CW}px;height:{H}px;z-index:11;pointer-events:none;
  background:linear-gradient(to bottom, rgba(0,0,0,.34) 0%, transparent 16%, transparent 76%, rgba(0,0,0,.42) 100%)}}
.band{{position:absolute;left:0;top:0;width:{CW}px;height:{H}px;transform-origin:0 0}}
.tl{{position:absolute;border-radius:34px;opacity:.90}}
.ribbon{{position:absolute;left:-200px;width:{CW+400}px;height:16px;background:var(--hot);
  transform-origin:0 0;opacity:.95}}
.head{{position:absolute;top:140px;width:{W-152}px;z-index:8}}
h1{{font-size:168px;font-weight:700;line-height:.92;letter-spacing:-.045em;
  text-shadow:0 2px 30px rgba(0,0,0,.55)}}
h1 .hot{{color:var(--hot)}}
.kick{{font-size:44px;font-weight:500;color:var(--dim);margin-top:34px;line-height:1.3}}
.win{{position:absolute;overflow:hidden;z-index:6}}
.win img{{position:absolute;left:0;top:0;width:1080px;display:block;transform-origin:0 0}}
.phone{{position:absolute;z-index:7;border-radius:78px;padding:14px;
  background:linear-gradient(155deg,#4A4238 0%,#1A160F 26%,#0E0C08 58%,#2C2620 100%);
  box-shadow:
    0 0 0 1px rgba(255,255,255,.07),
    0 3px 6px rgba(0,0,0,.55),
    0 34px 60px rgba(0,0,0,.55),
    0 90px 170px rgba(0,0,0,.60);}}
.phone .screen{{position:relative;width:100%;height:100%;border-radius:64px;overflow:hidden;
  background:#0A0806;box-shadow:inset 0 0 0 1px rgba(0,0,0,.85)}}
.phone .screen img{{position:absolute;left:0;top:0;width:100%;display:block}}
.phone .sheen{{position:absolute;inset:0;border-radius:64px;pointer-events:none;
  background:linear-gradient(122deg, rgba(255,255,255,.11) 0%, rgba(255,255,255,.03) 17%,
             transparent 34%, transparent 100%)}}
.frag{{position:absolute;overflow:hidden;border-radius:26px;background:#191410;
  outline:1px solid rgba(248,239,224,.16);outline-offset:-1px;
  box-shadow:0 2px 4px rgba(0,0,0,.6), 0 22px 44px rgba(0,0,0,.58), 0 60px 120px rgba(0,0,0,.55)}}
.frag img{{position:absolute;left:0;top:0;display:block;transform-origin:0 0}}
.soft{{border-radius:30px;
  box-shadow:0 2px 0 rgba(255,255,255,.07) inset, 0 1px 2px rgba(0,0,0,.5),
             0 26px 50px rgba(0,0,0,.55), 0 60px 130px rgba(0,0,0,.55);
  outline:1px solid rgba(248,239,224,.09);outline-offset:-1px}}
.ring{{border:1px solid rgba(248,239,224,.16)}}
"""

def build(panels, bands, out):
    parts=[b for b in bands]
    for i,p in enumerate(panels):
        parts.append(cap(i,*p["cap"],p["kick"]))
        parts.extend(p["parts"])
    pathlib.Path(out).write_text(
      '<!doctype html><html><head><meta charset="utf-8">'
      '<link href="https://fonts.googleapis.com/css2?family=Jost:wght@400;500;600;700&display=block" rel="stylesheet">'
      f'<style>{CSS}</style></head><body><div class="canvas">' + "".join(parts) + '<div class="vig"></div></div></body></html>')
