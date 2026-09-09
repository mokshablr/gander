#!/usr/bin/env bash
# Re-fetch the vendored viewer libraries from their upstreams.
# See docs/VENDORED.md for the provenance table.
set -euo pipefail

LIB="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/viewer/lib"
mkdir -p "$LIB/pptx"

get() { curl -sfL --retry 2 -o "$LIB/$1" "$2" && echo "fetched $1"; }

get jszip3.min.js       "https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js"
# The legacy build, which is transpiled for older system WebViews.
# Needs Chromium 125+; read "Before upgrading pdf.js" in docs/VENDORED.md first,
# because Android 8 and 9 top out at 138 and cannot go past it.
PDFJS_VER="5.7.284"
PDFJS="https://cdn.jsdelivr.net/npm/pdfjs-dist@$PDFJS_VER/legacy/build"
get pdf.min.mjs         "$PDFJS/pdf.min.mjs"
get pdf.worker.min.mjs  "$PDFJS/pdf.worker.min.mjs"

# Adobe's predefined CMap tables, which pdf.js loads on demand.
# A PDF whose CJK font is not embedded names an encoding like UniGB-UCS2-H
# instead of carrying a glyph mapping of its own. Without these tables pdf.js
# cannot turn its character codes into glyphs, and the failure is silent: the
# text is dropped from both the canvas and the text layer, so the page renders
# looking complete while whole paragraphs are missing. Nothing throws.
# Kept on the same version as the build above, because the table names are
# read out of the worker.
# 169 small files, so this comes from the npm tarball in one request.
CMAPS="$LIB/cmaps"
rm -rf "$CMAPS"
mkdir -p "$CMAPS"
curl -sfL --retry 2 "https://registry.npmjs.org/pdfjs-dist/-/pdfjs-dist-$PDFJS_VER.tgz" \
  | tar xz -C "$CMAPS" --strip-components=2 package/cmaps
echo "fetched cmaps/ ($(ls "$CMAPS" | wc -l | tr -d ' ') files)"

# The image decoders pdf.js keeps in WebAssembly rather than in the bundle, which
# the worker fetches the moment it meets an image needing one. openjpeg decodes
# JPEG 2000 and jbig2 decodes the bitonal format scanners produce. Without them
# the worker warns and returns nothing, so the image is left out of the page with
# no error and no placeholder. Kept on the same version as the build above.
# The pure-JS fallbacks are deliberately not fetched: openjpeg_nowasm_fallback.js
# alone is 452 KB, larger than both binaries together.
mkdir -p "$LIB/wasm"
for w in openjpeg.wasm jbig2.wasm \
         LICENSE_OPENJPEG LICENSE_JBIG2 \
         LICENSE_PDFJS_OPENJPEG LICENSE_PDFJS_JBIG2; do
  get "wasm/$w" "https://cdn.jsdelivr.net/npm/pdfjs-dist@$PDFJS_VER/wasm/$w"
done

get docx-preview.min.js "https://cdn.jsdelivr.net/npm/docx-preview/dist/docx-preview.min.js"
get xlsx.full.min.js    "https://cdn.sheetjs.com/xlsx-0.20.3/package/dist/xlsx.full.min.js"
get marked.min.js       "https://cdn.jsdelivr.net/npm/marked@15/marked.min.js"
get purify.min.js       "https://cdn.jsdelivr.net/npm/dompurify@3/dist/purify.min.js"

P="https://cdn.jsdelivr.net/gh/meshesha/PPTXjs@master"
get pptx/jquery.min.js  "$P/js/jquery-1.11.3.min.js"
get pptx/jszip2.min.js  "$P/js/jszip.min.js"
get pptx/filereader.js  "$P/js/filereader.js"
get pptx/d3.min.js      "$P/js/d3.min.js"
get pptx/nv.d3.min.js   "$P/js/nv.d3.min.js"
get pptx/pptxjs.js      "$P/js/pptxjs.js"
get pptx/divs2slides.js "$P/js/divs2slides.js"
get pptx/pptxjs.css     "$P/css/pptxjs.css"
get pptx/nv.d3.min.css  "$P/css/nv.d3.min.css"

echo "done"
