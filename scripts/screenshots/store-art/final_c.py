import sys, math, os; sys.path.insert(0,'.')
from pano import build, phone, fragment, W, CW, RAW
from drift import procession, cards, sweep

PY_TOP, PH, PW = 700, 1400, 856
AMP, PER, PHZ = 74.0, 4300.0, 0.55

# The About dialog prints the build number, stale the day after a release and only
# fixable by a re-render plus a Play re-upload - the same reason the APK size came
# out of a caption in 5b9fb92. The dialog surface is flat here, so the line is
# covered in its own colour. Declared here, not painted into the capture.
VERSION_PATCH = [(96, 460, 894, 96, "#2D2A23")]

glow=lambda i: f'<div class="glow" style="left:{i*W-40}px;top:{PY_TOP-200}px;width:{W+80}px;height:{PH+320}px"></div>'
def _wave(x): return AMP*math.sin(2*math.pi*(x/PER)+PHZ)
def _slope(x): return AMP*(2*math.pi/PER)*math.cos(2*math.pi*(x/PER)+PHZ)

def P(i,a,b,k,src,patches=(),frag=None):
    cx=i*W+W//2
    y=PY_TOP+_wave(cx)
    rot=-math.degrees(math.atan(_slope(cx)))*0.62
    parts=[glow(i), phone(i*W+(W-PW)//2, int(y), PW, PH, src, top=0, z=12, rot=rot, patches=patches)]
    if frag:
        fx,fy,fw,(cx0,cy0,cw,ch)=frag
        parts.append(fragment(i*W+fx, int(y)+fy, fw, src, cx0, cy0, cw, ch, rot=rot+1.6, z=16))
    return {"cap":(a,b),"kick":k,"parts":parts,"src":src}

# the controls, lifted and magnified ~1.5x over their on-screen size
FIND_BAR = (10, 286, 750, 139)     # query + live match count
SHEET_TABS = (8, 292, 792, 138)    # tab strip plus the first rows under it

panels=[
 P(0,"Opens","everything.","PDF, Word, Excel, PowerPoint, photos,<br>video, audio, Markdown and code.","d-welcome.png"),
 P(1,"Takes","nothing.","No permissions. No trackers.<br>No internet access at all.","d-about.png",patches=VERSION_PATCH),
 P(2,"Finds","anything.","Search inside a PDF, a spreadsheet<br>or a deck. Match by match.","d-find.png",
   frag=(90, 560, 900, FIND_BAR)),
 P(3,"Every","sheet.","Multi-sheet workbooks, tabs and all.<br>xlsx, xls, xlsm, xlsb, csv, ods.","d-xlsx.png",
   frag=(90, 560, 900, SHEET_TABS)),
 P(4,"Any","deck.","PowerPoint slides, without<br>installing an office suite.","d-pptx.png"),
 # 1.14 shot this slot on a Markdown file and claimed only that the app follows the
 # system dark mode, which every Material app does. It was Markdown because it had to
 # be: raw/d-pdf.png was captured for this same shoot and never used, being the dark
 # app around a glaring white PDF page, which would have refuted the caption above it.
 # 1.15 turned the paper over, so the slot can now carry the claim nothing else in the
 # category makes. Shoot a page that holds a photograph, or the frame shows only black
 # paper and white text and the half that is actually rare goes unsaid.
 P(5,"Reads","at 2am.","PDF paper goes black, text goes white,<br>and photographs stay as printed.","d-pdf-night.png"),
]
if os.path.exists(f"{RAW}/d-folder.png"):
    panels.append(P(6,"From your","folders.",
      "Grant one once. Gander still needs<br>no storage permission to read it.","d-folder.png"))

# Every band spreads exactly n items across the whole canvas, so widening the canvas
# without raising n stretches the field instead of extending it: at seven frames the
# procession went from a tile every 368px to one every 425px, thinning all seven
# backgrounds by 15% to fill the one that had been empty. These counts were tuned
# against the six-frame canvas, so scale them with the width and the spacing holds.
# `accents` stay index-based and land at slightly different points in the graduation,
# which is the intended reading of "a few of them carry the palette".
dens = lambda n: round(n * CW / 6480)

bands=[
  procession(seed=7, n=dens(20), base=800, amp=130, period=3100,
             size_a=76, size_b=118, mute_a=.92, mute_b=.84, rot=9, z=1, blur=2.2, phase=0.7, jitter=44),
  sweep(base=1500, amp=118, period=5200, phase=0.35, z=4),
  cards(seed=41, n=dens(13), base=1560, amp=126, period=5200,
        w_a=150, w_b=214, op_a=.30, op_b=.52, rot=8, z=6, blur=1.1, phase=0.35, accents=(3,9)),
  cards(seed=88, n=dens(9),  base=1700, amp=112, period=5200,
        w_a=236, w_b=316, op_a=.80, op_b=1.0, rot=9, z=9, phase=0.35, accents=(1,5,7)),
]
# A caption promising black paper printed over a white page is worse than not
# re-shooting at all, so a missing capture stops the render instead of quietly
# falling back to whatever the slot held last time.
missing=[s for s in sorted({p["src"] for p in panels}) if not os.path.exists(f"{RAW}/{s}")]
if missing:
    sys.exit(
      "Missing captures under %s:\n  %s\n\n"
      "d-pdf-night.png is the 1.15 re-shoot of panel 5. Open a PDF that carries a\n"
      "photograph, turn night mode on from the viewer's overflow menu, and capture at\n"
      "1080x1920 with the debug resource overlay moved aside, per\n"
      "scripts/screenshots/README.md. A text-only page renders as black paper and white\n"
      "text and says nothing a reader could not guess; the photograph left as printed is\n"
      "the part the caption is selling." % (RAW, "\n  ".join(missing)))

build(panels,bands,"c.html")
print("panels:", len(panels), "canvas:", W*len(panels), "x", 1920)
