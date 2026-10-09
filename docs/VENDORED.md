# Vendored viewer libraries

Gander renders Office formats, Markdown and spreadsheets with open source
JavaScript libraries bundled under `app/src/main/assets/viewer/lib/`. They are
vendored (not fetched at runtime) because the app has no network access at all.

`scripts/fetch-viewer-libs.sh` re-downloads every file below from its upstream.

This table is the provenance record. The *shipped* notice is
`app/src/main/assets/licences.md`, reachable from the app's About dialog, and it
carries the full licence texts because Apache-2.0 §4(a) and the MIT and BSD
notice clauses require them to travel with the binary rather than sit in a repo.
Adding, dropping or upgrading a library here means editing that file in the same
commit.

| File | Project | Version | License | Upstream |
| --- | --- | --- | --- | --- |
| `pdf.min.mjs` | pdf.js (legacy build) | 6.3.289 | Apache-2.0 | https://github.com/mozilla/pdf.js |
| `pdf.worker.min.mjs` | pdf.js worker (legacy build) | 6.3.289 | Apache-2.0 | https://github.com/mozilla/pdf.js |
| `cmaps/` (169 files) | Adobe CMap resources, redistributed by pdf.js | 1990-2009, via pdf.js 6.3.289 | BSD-3-Clause | https://github.com/adobe-type-tools/cmap-resources |
| `wasm/openjpeg.wasm` | OpenJPEG, compiled and redistributed by pdf.js | via pdf.js 6.3.289 | BSD-2-Clause | https://github.com/uclouvain/openjpeg |
| `wasm/jbig2.wasm` | PDFium's JBIG2 decoder, compiled and redistributed by pdf.js | via pdf.js 6.3.289 | BSD-3-Clause and Apache-2.0 | https://pdfium.googlesource.com/pdfium/ |
| `wasm/LICENSE_*` (4 files) | licence texts for the two decoders above | via pdf.js 6.3.289 | see above | https://github.com/mozilla/pdf.js |
| `jszip3.min.js` | JSZip | 3.10.1 | MIT or GPL-3.0 dual | https://github.com/Stuk/jszip |
| `docx-preview.min.js` | docx-preview | 0.4.0 | Apache-2.0 | https://github.com/VolodymyrBaydalka/docxjs |
| `xlsx.full.min.js` | SheetJS Community Edition | 0.20.3 | Apache-2.0 | https://git.sheetjs.com/sheetjs/sheetjs |
| `marked.min.js` | marked | 15.0.12 | MIT | https://github.com/markedjs/marked |
| `purify.min.js` | DOMPurify | 3.4.12 | Apache-2.0 or MPL-2.0 dual | https://github.com/cure53/DOMPurify |
| `pptx/pptxjs.js` | PPTXjs | 1.21.1, with one change (below) | MIT | https://github.com/meshesha/PPTXjs |
| `pptx/divs2slides.js` | divs2slides (PPTXjs) | 1.3.2 | MIT | https://github.com/meshesha/PPTXjs |
| `pptx/filereader.js` | FileReader.js (PPTXjs bundle) | 0.99 | MIT | https://github.com/meshesha/PPTXjs |
| `pptx/jquery.min.js` | jQuery | 1.11.3 | MIT | https://github.com/jquery/jquery |
| `pptx/jszip2.min.js` | JSZip 2.x (PPTXjs bundle) | 2.x | MIT or GPL-3.0 dual | https://github.com/meshesha/PPTXjs |
| `pptx/d3.min.js` | D3 | 3.5.10 | BSD-3-Clause | https://github.com/d3/d3 |
| `pptx/nv.d3.min.js` | NVD3 | 1.8.1 | Apache-2.0 | https://github.com/novus/nvd3 |
| `pptx/pptxjs.css`, `pptx/nv.d3.min.css` | PPTXjs / NVD3 styles | see above | see above | see above |

## The CMap tables

`cmaps/` is 1.6 MB of Adobe's predefined CMap tables, which pdf.js loads from
the assets on demand. They are not optional decoration: a PDF that uses a CJK
font without embedding it carries no glyph mapping of its own and names an
encoding like `UniGB-UCS2-H` instead, and without the matching table pdf.js
cannot resolve its character codes.

What makes it worth the weight is how it fails. There is no error, no exception
and no fallback boxes; the text is dropped from the canvas and the text layer
both, so the page renders looking finished while whole paragraphs are missing.
That was issue #21, reported against 1.13 as "all Chinese text is missing".

Keep them on the same version as the build above. Dropping the directory to
save space, or trimming it to the encodings that look current, re-opens the bug
silently for whatever was trimmed.

## The wasm image decoders

`wasm/` holds the two image decoders pdf.js keeps in WebAssembly rather than in
its bundle. Since pdf.js 4 the JPEG 2000 decoder (`openjpeg.wasm`, 252 KB) and
the JBIG2 one (`jbig2.wasm`, 105 KB) are fetched by the worker at the moment it
meets an image that needs one, and the `wasmUrl` option in `pdf.mjs` is the
only way to say where they are.

They fail exactly the way the CMaps do. The worker warns to the console, returns
nothing, and the image is left out of the page: no error, no placeholder, and no
gap that looks like anything other than the document's own layout. That was
issue #24, reported against 1.15 as "some PDFs not showing images", where the
sample was a book whose 190 images were 186 JPEG 2000.

The pure-JS fallbacks upstream ships beside them are deliberately not vendored.
They are the expensive route, not the cheap one: `openjpeg_nowasm_fallback.js`
alone is 452 KB, larger than both binaries together.

`qcms_bg.wasm`, which pdf.js uses for ICCBased colour spaces, is deliberately
**not** here. It is gated on pdf.js's `useWorkerFetch`, which also requires
`standardFontDataUrl` to be set, and neither is wired up; shipping the binary
alone would add weight that nothing could reach. Wiring up ICC is its own job.

**The licences.** Neither binary is copyleft. `openjpeg.wasm` is BSD-2-Clause
(UCLouvain and contributors). `jbig2.wasm` is built from **PDFium's** decoder,
not from Artifex's jbig2dec as the name suggests, and `LICENSE_JBIG2` carries
PDFium's BSD-3-Clause notice followed by the Apache-2.0 text. Mozilla's own
wrapper notices are the two `LICENSE_PDFJS_*` files. All four are attribution
licences whose notices have to travel with the binaries, which is why they are
fetched into `wasm/` beside them and repeated in
`app/src/main/assets/licences.md`.

## The Word and Markdown floors

`docx.html` and `md.html` show the same card as `pdf.html` when the WebView is too
old for the library that draws them: below Chromium 80 for docx-preview and 92 for
marked, which are `DOCX_PREVIEW_MIN_CHROMIUM_MAJOR` and `MARKED_MIN_CHROMIUM_MAJOR`
in `app/src/main/java/com/arjun/gander/WebViewFloor.kt`. Until issue #31 they had no
floor, and on WebView 64 a .docx said "docx is not defined" and a .md "marked is not
defined".

Neither library publishes a minimum, so both numbers are measurements, and a
measurement is of one file. `VendoredLibsTest` pins the SHA-256 of every file the
two pages load, JSZip and DOMPurify included, and fails when a refetch changes one,
which the newest-in-the-major fetches of marked and DOMPurify make likely. Measure
the new file before updating its fingerprint:

    node scripts/js-floor.mjs app/src/main/assets/viewer/lib/marked.min.js

It lists every construct newer than ES2015 with the Chromium that shipped it, which
is exact, and the newer runtime names the file mentions, which are only candidates:
a name in the file is not a call every document makes. marked's
`Array.prototype.at` is one that counts. Without it a single line of prose fails,
which is why its floor is 92 rather than the 80 its syntax alone would give. JSZip
and DOMPurify parse as ES2015 and reach for newer names only behind `typeof` checks,
so neither sets a floor today.

## Before upgrading docx-preview

docx-preview stays as upstream ships it, so `docx.js`, `docx-pages.js` and `docx-lines.js`
change it from outside, through the `h` option, which it calls as the renderer's own method.
From there they replace eight renderer methods by name: `renderRun` (subscript runs drawn
twice, docxjs#233, and the page-number fields), `splitBySection`, `groupByPageBreaks`,
`createPageElement`, `renderHeaderFooter`, `renderParagraph` and `renderTableRow` (Word's
own pages, issue #47), and `styleToString` (lines as Word sets them). The names survive
minification. Check that each still exists in a new file, with the same arguments, before
upgrading.

`docx-lines.js` also rewrites the style objects `h` receives for `p` and `span`, so it reads
the three forms `parseSpacing` writes for `line-height`: a bare number for "auto",
`calc(100% + <length>)` for "atLeast" and a length for "exact". It reads the theme's fonts
from the `--docx-<name>-font` properties `renderTheme` writes, the paragraph mark's
`runProps.fontSize`, and the class `processStyleName` gives a style, `docx_` and its id.
`docx.js` draws through `parseAsync` and `renderDocument`, the two halves of `renderAsync`,
with `keepOrigin` set, so that `docx-lines.js` reads `contextualSpacing` from the styles
part's `_xmlDocument` rather than parsing `styles.xml` again, then clears every part's in
`document.parts`. `test_docx_lines.py` fails if any of these change shape.

They also read the shapes of the parsed document: a break whose `break` is
`"lastRenderedPageBreak"`, a run's `fieldRun` with `complexField` (by `charType`) and
`instruction` children, a section's `pageNumber.start`, `pageNumber.format` and
`columns.numberOfColumns`, a row's `isHeader`, which is null for Word's `<w:tblHeader/>`,
and the page count Word saved, `document.extendedPropsPart.props.pages`.
`test_docx_pages.py` fails if the pages stop splitting or numbering. Once upstream
releases the fix for #233 (PR #234), `drawRunsOnce` can go.

## PPTXjs's one change

`pptx/pptxjs.js` is upstream's 1.21.1 with two catches added for issue #48, each marked
`Gander, #48`. One is in `processNodesInSlide`, which every shape comes through, a group's
members and a layout's or master's included; the other is around each chart nv.d3 draws
once the slides are built. A shape or chart PPTXjs throws on is left out and the rest of the
deck drawn, where the throw put the error card up in place of every slide, and
`window.vwLeftOut` lists what was left out. The first catch keeps the shape's place as an
empty block marked with what it was (`missingPlace`), and the second marks the chart's own
box, so that `pptx.js` can put a box there saying what is missing. The change is
`scripts/pptxjs.patch`, which `fetch-viewer-libs.sh` applies after each fetch, stopping if
upstream's file has moved under it. Everything else is still put right from outside, in
`pptx.js`, as the package opens. `test_pptx.py` fails if either catch goes, or either mark.

Upstream's `js/dingbat.js` is not shipped. PPTXjs looks a Wingdings 2 or 3 bullet up in its
global `dingbat_unicode` and throws when there is none, and many of the characters upstream's
table gives are ones a phone has no glyph for. `pptx.js` makes that table from
`symbol-fonts.js` instead, and since it rewrites such bullets as the package opens, only a
part DOMParser cannot read still reaches it.

The file keeps upstream's Windows line endings, and so does the patch. A script that writes
it back with Unix ones, as Python's text mode does, leaves git showing every line changed.

## Before upgrading pdf.js

`pdf.html` is the only viewer loaded as an ES module, and a module the WebView
cannot parse never runs, so the page has no way to report the problem from inside
itself. That makes the Chromium floor a fact to check rather than a preference.

- The legacy build of 6.3.289 supports **Chromium 125 and newer**: pdf.js 6.0
  set that floor in PR 21152, the same number the FAQ gave for 5.7.284. That
  number is `PDFJS_MIN_CHROMIUM_MAJOR` in
  `app/src/main/java/com/arjun/gander/WebViewFloor.kt`, compared against the
  WebView actually in use. Below it, `pdf.html` shows a card explaining that
  Android System WebView needs updating instead of loading the renderer.
- **Every viewer page runs under the Content Security Policy in `ViewerPolicy.kt`.**
  It allows no eval and no fetch off the page's own host, and allows WebAssembly,
  which the worker compiles for its JPEG 2000 and JBIG2 decoders and for every
  PostScript function a PDF carries. A new version that needs anything else is
  refused quietly: the browser reports it and the page carries on without it. The
  viewer tests fail on any such report from a page, but the worker takes its policy
  from the header ViewerActivity sends, which the test server does not, so only the
  device tests see what the worker is refused.
- **Chromium 138 is the ceiling on Android 8.0, 8.1 and 9.0.** Chromium 139
  requires Android 10, so those releases will never receive a newer WebView, and
  minSdk here is 26. A pdf.js version needing more than 138 therefore does not
  degrade on API 26 to 28, it ends PDF support there. Check the new version's
  floor first: if it is above 138, this is a decision about dropping PDFs on
  Android 8 and 9, not a version bump.

- **The text layer CSS in `pdf.html` is a contract, and it fails silently.** pdf.js
  writes only `left`, `top`, `font-family` and three custom properties on each span:
  `--font-height`, `--scale-x` and `--rotate`. It never writes `font-size` or
  `transform`; the stylesheet has to turn those properties into both, and the scale
  hook in 6.3.289 is `--total-scale-factor` (the older `--scale-factor` is not read).
  Get any of it wrong and nothing throws and nothing looks broken, because the text is
  transparent and the picture underneath is still correct. It shows up only as a
  selection covering the wrong words or a search highlight stopping short of the word
  it found. The upstream rules are in `web/pdf_viewer.css` of the same version; diff
  the `.textLayer` block against the one in `pdf.html` on any upgrade.

- **The text layer's font is a second contract, and it fails the same silent way.**
  `pdf.html` rewrites `textContent.styles[key].fontFamily` to the PDF's own embedded
  face as the chunks stream past, so pdf.js measures each run in the typeface the page
  was set in rather than in a generic. Without it, `--scale-x` corrects a run's total
  width and leaves every character position inside it wrong, which is a search
  highlight landing short. Five things about 6.3.289 make it work, and none is
  documented API:
  - `textContent.styles` is keyed by `font.loadedName`, and `item.fontName` is that
    same key, so the mapping is direct rather than a lookup.
  - Embedded faces are registered as `new FontFace(font.loadedName, font.data, {})`,
    awaited inside `page.render()`. `pdf.html` builds the text layer only after the
    canvas render resolves, which is what makes the face present.
  - The name spaces are disjoint: embedded fonts are `g_d<n>_f<n>`, substituted system
    fonts `g_d<n>_s<n>`, failures `g_font_error`. That is what makes the
    `document.fonts` test exact rather than a guess, and what keeps a non-embedded
    document behaving as it did before.
  - `adjustMapping()` remaps every glyph into a Private Use Area for the canvas, but
    also carries a `toUnicodeExtraMap` that `createCmapTable()` writes into the same
    cmap. That second set of entries is the only reason the face can render the text
    layer's Unicode at all. If it goes, this stops working and nothing says so.
  - The rebuilt font keeps no `GSUB`, `GPOS` or `kern`, so the browser applies no
    ligatures and no kerning and lays a run out on bare `hmtx` advances, the model
    the PDF itself used. Do not add `font-kerning` or `font-variant-ligatures`: pdf.js
    measures on a canvas whose settings the stylesheet cannot reach, so disabling
    shaping on one side only would desynchronise the measurement from the paint.

  Upstream proposed the same idea as PR #19230 and rejected it, on the grounds that it
  breaks for non-embedded fonts and that a face need not carry every glyph. Both are
  answered here by checking `document.fonts` on the display side instead of asserting
  the name in the worker, and by keeping the generic behind the face as a real
  fallback. Read that PR before undoing this, not instead of this note.

- **Night mode's image coordinates are a third contract of the same shape.** Night mode
  turns a finished page bitmap over and clips the photographs out of the pass, so they
  stay as they were printed. Where the photographs are comes from `page.imageCoordinates`,
  filled when a render is asked for with `recordImages: true`. Neither is documented API,
  and the shape of the answer is not obvious:
  - **Six numbers per image, and they are fractions, not pixels** - each one is a
    proportion of the canvas that recorded them. That is what lets a zoom tile clip by
    the numbers the full-page render produced, and what lets them survive `page.cleanup()`.
  - **They are three corners of a parallelogram, not a rectangle**, so a rotated or
    sheared placement is exact. The first corner is the one *between* the other two, so
    the fourth is `B + C - A` and the area is the cross product of the two edges leaving
    `A`. Read them as `[minX, minY, maxX, maxY]` and the clip lands somewhere else on the
    page entirely.
  - **Recording happens once per page object**, guarded by `!this.imageCoordinates`, and
    the answer is kept. A second render of the same page measures nothing.
  - **The array is `Float16Array` above Chromium 135 and `Float32Array` below it**, so the
    precision available is not the same on every device the app supports. `imageQuads()`
    rounds to whole pixels partly for that and partly because an unrounded clip edge is
    antialiased, which leaves a grey hairline round every photograph.
  - **Only `paintInlineImageXObject` records, and `paintImageXObject` delegates to it.**
    `paintImageMaskXObject` does not, which is right: a stencil mask is bilevel line art
    and has to keep turning over with the text. `paintImageXObjectRepeat` does not either,
    so a tiled pattern turns over; that fails in the harmless direction.

  The failure mode is the usual one for this file: nothing throws. Pictures quietly start
  turning over, or a rectangle of the page stops. What the rectangles are *used* for is not
  pdf.js's business and is decided in `looksLikePaper()`, which measures each one and turns
  over the ones that look like paper rather than like a photograph; two simpler rules were
  tried and withdrawn first, and the comment above `imageQuads()` says which and why. Note
  that the page is read back **once** for that, not once per image: the per-image version
  measured 11 ms each on a phone against 0.04 ms on a desktop, so a page carrying sixty small
  figures spent 684 ms deciding what they were and nothing on the desktop said so.

Bumping pdf.js means editing together the two `pdf.*.mjs` rows above, `PDFJS` in
`scripts/fetch-viewer-libs.sh`, and `PDFJS_MIN_CHROMIUM_MAJOR`. The card's
wording lives in `app.js`, called from `pdf-setup.js`, and reads both version
numbers out of the query string, so it needs no edit. It also reads `locked`,
which says the reader has no way to update the WebView and selects wording that
does not ask them to; that flag is about the phone rather than about pdf.js, so
a version bump does not affect it either.

Notes for packagers (F-Droid and friends): the minified files are unmodified
upstream distribution artifacts. If unminified sources are required, every
project above publishes them at the linked repository, and the fetch script can
be pointed at the unminified dist files where upstream provides them.
