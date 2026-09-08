#!/usr/bin/env python3
"""The Willowmere condition survey: the sample PDF that carries a photograph.

Written for store panel 5, "Reads at 2am.", whose caption claims that night mode
turns the paper over and leaves photographs as printed. Demonstrating that needs a
PDF with a photograph in it, and none of the other samples has one.

TWO THINGS HERE ARE LOAD BEARING AND NEITHER IS OBVIOUS.

1. WHICH CROP OF THE PHOTO. `looksLikePaper()` in pdf.html calls an image paper, and
   turns it over with the text, when its mean saturation is under PROBE_SATURATION
   (0.15) AND more than PROBE_PAPER (0.25) of it is near white. A snowy scene is pale
   and unsaturated, which is a scanned page's signature, so the obvious crop of the
   sample world's own photo is classified as paper and INVERTED - producing a negative
   photograph directly under a caption promising the opposite. Measured on the source
   capture:

       whole photo          sat 0.090  pale 0.361  -> inverted
       building, mid frame  sat 0.129  pale 0.280  -> inverted
       PLATE (below)        sat 0.119  pale 0.086  -> kept as printed

   The plate crop clears the pale test by roughly 3x. `check()` re-measures it on every
   run and refuses to build if a future edit walks it back over the line.

2. NO TABLES. make_samples.py's own comment records that reportlab tables trip the
   WebView rendering fault described in scripts/screenshots/README.md, and this file
   goes in front of a store reviewer. Everything here is paragraphs and one image.

The photograph is the CC0 image named in scripts/screenshots/README.md, recovered from
the committed device capture rather than re-downloaded, so this runs offline.
"""
import pathlib
import sys

from PIL import Image
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (BaseDocTemplate, Frame, Image as RLImage,
                                PageTemplate, Paragraph)

HERE = pathlib.Path(__file__).parent
OUT = HERE / "out"
OUT.mkdir(exist_ok=True)
SOURCE = HERE.parent.parent / "docs/screenshots/v1.14/raw/photo.png"
PLATE_BOX = (0, 1300, 830, 1780)          # see note 1: the low, wooded, unsnowy corner

# pdf.html's own thresholds. Keep in step with PROBE_SATURATION / PROBE_PAPER there.
PROBE_SATURATION, PROBE_PAPER = 0.15, 0.25

INK = colors.HexColor("#1a1a1a")
ACCENT = colors.HexColor("#1F3A5F")
ss = getSampleStyleSheet()
S = {
    "title": ParagraphStyle("t", parent=ss["Normal"], fontName="Times-Bold", fontSize=19,
                            leading=23, alignment=1, textColor=INK, spaceAfter=3),
    "sub": ParagraphStyle("s", parent=ss["Normal"], fontName="Times-Roman", fontSize=10.5,
                          leading=14, alignment=1,
                          textColor=colors.HexColor("#4a4a4a"), spaceAfter=16),
    "h": ParagraphStyle("h", parent=ss["Normal"], fontName="Times-Bold", fontSize=11.5,
                        leading=14, textColor=ACCENT, spaceBefore=11, spaceAfter=5),
    "body": ParagraphStyle("b", parent=ss["Normal"], fontName="Times-Roman", fontSize=10,
                           leading=14.5, alignment=4, textColor=INK, spaceAfter=7),
    "cap": ParagraphStyle("cap", parent=ss["Normal"], fontName="Times-Italic", fontSize=9,
                          leading=12, alignment=1,
                          textColor=colors.HexColor("#4a4a4a"), spaceBefore=5, spaceAfter=12),
}


def measure(im):
    """pdf.html's looksLikePaper(), on a 64x64 sample. Returns (saturation, pale)."""
    q = im.convert("RGB").resize((64, 64))
    pale = sat = 0
    for r, g, b in q.getdata():
        mx, mn = max(r, g, b), min(r, g, b)
        if mn > 204:
            pale += 1
        if mx > 0:
            sat += (mx - mn) / mx
    n = 64 * 64
    return sat / n, pale / n


def plate():
    """Cut the photographic plate, and refuse to ship one night mode would invert."""
    # Native crop, no upscale. A 2x LANCZOS pass invents no detail and cost 340 kB; the
    # plate lands about 980 px wide in the viewer, so 830 px is a 1.18x stretch nobody sees.
    im = Image.open(SOURCE).convert("RGB").crop(PLATE_BOX)
    s, p = measure(im)
    verdict = "paper" if (s < PROBE_SATURATION and p > PROBE_PAPER) else "photograph"
    print("  plate %dx%d  saturation %.3f  pale %.3f  -> night mode reads it as a %s"
          % (im.width, im.height, s, p, verdict))
    if verdict == "paper":
        sys.exit(
            "\nPLATE_BOX crops an image night mode classifies as paper, so it would be\n"
            "turned over along with the text. The panel 5 caption says photographs stay\n"
            "as printed, so this document would disprove its own screenshot.\n"
            "Move the crop toward the darker, less snowy part of the frame and re-run.")
    # JPEG, not PNG: this is a photograph, and a lossless plate put the finished PDF at
    # 5.7 MB, larger than the app. q88 brings it under 300 kB with no visible change, and
    # it is what a real survey report would embed anyway. Measured after encoding, since
    # compression moves the numbers the paper test reads.
    out = OUT / "plate.jpg"
    im.save(out, quality=88, subsampling=0)
    s2, p2 = measure(Image.open(out))
    if s2 < PROBE_SATURATION and p2 > PROBE_PAPER:
        sys.exit("JPEG encoding pushed the plate over the paper threshold "
                 "(saturation %.3f, pale %.3f). Raise quality or move the crop." % (s2, p2))
    return out, im.width / im.height


def page(c, _doc):          # reportlab calls this positionally; the doc arg is unused
    c.saveState()
    c.setFont("Times-Roman", 7.5)
    c.setFillColor(colors.HexColor("#8a8a8a"))
    c.drawString(20 * mm, 12 * mm,
                 "Willowmere Phase 3 - condition survey, Aldergate Property Care")
    c.drawRightString(A4[0] - 20 * mm, 12 * mm, "Page 1 of 6")
    c.drawRightString(A4[0] - 20 * mm, A4[1] - 14 * mm, "REF: WM-3/2026-02")
    c.restoreState()


def survey_pdf():
    img, aspect = plate()
    path = OUT / "Willowmere Phase 3 - condition survey.pdf"
    doc = BaseDocTemplate(str(path), pagesize=A4, leftMargin=20 * mm, rightMargin=20 * mm,
                          topMargin=22 * mm, bottomMargin=20 * mm)
    doc.addPageTemplates([PageTemplate(
        id="p",
        frames=[Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="f")],
        onPage=page)])
    w = doc.width
    doc.build([
        Paragraph("WILLOWMERE PHASE 3", S["title"]),
        Paragraph("Winter condition survey of the depot buildings and northern boundary",
                  S["sub"]),
        Paragraph("1.  Scope and method", S["h"]),
        Paragraph(
            "This survey records the condition of the depot range and the northern boundary "
            "planting following the February cold spell. The walkover was made on foot from "
            "the nine fixed photographic points established in Phase 1, in daylight, with no "
            "access to roof level. Nothing in this report rests on measurement taken from a "
            "photograph.", S["body"]),
        Paragraph(
            "Conditions were hard frost with lying snow of roughly 120 mm on level ground, "
            "which obscured the yard surfaces and the lower courses of the boundary wall. "
            "Those elements are carried forward to the spring visit rather than reported "
            "here.", S["body"]),
        Paragraph("2.  Depot range, north elevation", S["h"]),
        RLImage(str(img), width=w, height=w / aspect),
        Paragraph(
            "Plate 3 - northern boundary planting from fixed point 4, 14 February 2026. The "
            "mature spruce at centre is outside the demise and is noted for the shading it "
            "casts on the north range rather than for its own condition.", S["cap"]),
        Paragraph(
            "The roof covering is sound across the visible slopes. Snow has not lifted at the "
            "verges, which would be the first sign of a failed fixing, and the valley between "
            "the two ranges is clear. Two rooflights on the eastern slope carry ice at their "
            "upstands and should be re-inspected once the thaw is complete.", S["body"]),
        Paragraph(
            "Rainwater goods are the principal defect. The eastern downpipe is discharging at "
            "the hopper rather than into it, and the staining below is consistent with this "
            "having run for more than one season. Repair is straightforward and is "
            "recommended before the spring rain rather than as part of the Phase 4 works.",
            S["body"]),
        Paragraph("3.  Northern boundary", S["h"]),
        Paragraph(
            "The boundary planting is in good condition and is performing the screening "
            "function it was retained for. No storm damage was evident. The self-seeded ash "
            "on the eastern return has reached a size at which it will begin to lift the wall "
            "footing and should be taken out this winter while access is still open.",
            S["body"]),
    ])
    print("  wrote %s" % path)


if __name__ == "__main__":
    if not SOURCE.exists():
        sys.exit("Source capture missing: %s\n"
                 "It lives under the gitignored docs/screenshots/v1.14/raw/. Re-shoot the "
                 "image viewer, or re-download the CC0 original named in README.md." % SOURCE)
    survey_pdf()
