# Architecture

How Gander is put together, for anyone about to change it: the parts, how a file reaches the screen, the one channel between Kotlin and the viewer pages, the dependencies, and what guards each constraint. A change that moves a part updates this page in the same commit.

## Shape

One Android module in Kotlin, minSdk 26, target and compile SDK 36, R8 on with resource shrinking. It requests no permissions, has no network access and ships no native libraries. The release APK is about 5 MB, one build for every processor. There is no backend.

- `GanderApp` starts the WebView on a background thread as the process starts, best effort, so the first document opens sooner.
- `MainActivity` is the launcher: recent files (`Recents`, persisted SAF read grants), added folders (tree grants) browsed with DocumentsContract child queries, sort and filter (`Settings`, `Listing`), thumbnails off the main thread (`Thumbs`, and `PdfThumbs` for PDFs), swipe to remove with Undo (`SwipeToRemove`), a first-run explainer with the Open with tip, and About with the licences and a File types page, each copied from assets to the cache and opened in the viewer.
- `ViewerActivity` shows one file. It is exported for other apps' VIEW and SEND intents. Gander's own screens open it as `InternalViewer`, a non-exported subclass, so it can tell the two apart.

## What the viewer accepts

From another app it takes only a `content://` URI on that app's own provider, read with the grant that came with it (`mayOpenFromOutside`). File paths and Gander's own providers are refused, `%40` and `0@` spellings included. Shared text becomes `cache/shared-text.txt`. Only a file opened from Gander's own screens goes into Recents, never a file inside a zip.

## Routing and surfaces

`FileKind.detect` routes by extension first and MIME type second. A `.dot` that reads as text opens as text (Graphviz, #58), and a file of no known format whose first 8,000 bytes read as text opens as text with a line saying so (#51, `ReadsAsText.kt`). Each kind names a surface:

- IMAGE: `SubsamplingScaleImageView`, tiled, zoomable, rotated by EXIF. An animated WebP or PNG (`AnimatedPicture.kt`), or one that fails to decode, goes to `imgweb.html`.
- PLAYER: `PlayerScreen`, over Media3 ExoPlayer. Video gets `VideoChrome` (bars that float and hide, full screen for wide video). Audio gets a fixed transport and cover art, plays on with the screen off, and shows play and pause on the lock screen (`LockScreenControls`). An error after five seconds of playing gets another go.
- ARCHIVE: `ArchiveBrowser` lists a zip without extracting it. `ZipReader` parses it (stored, deflate, `Deflate64`, ZipCrypto and WinZip AES in `ZipEncryption`), `ZipNames` guesses the code page of names, and `ArchiveProvider`, not exported, serves each entry to the other surfaces as a window on the file or a pipe. Limits: a 32 MB central directory and 200,000 entries.
- PDF: `pdf.html` with pdf.js where the WebView is Chromium 125 or newer. Below that, `pdfRoute` gives a card asking for a WebView update, with a button for the simple view, or goes straight to the simple view when the WebView can't be updated (`com.huawei.webview`). Never the simple view on Android 8.
- PDF_SIMPLE: `pdfsimple.html` shows each page as a PNG that Gander makes from rows drawn by Android's `PdfRenderer` (PDFium) in an isolated process: see below.
- The rest are WebView pages in `app/src/main/assets/viewer/`: DOCX `docx.html` (docx-preview, with Gander's own page splitting in `docx-pages.js`, tab and line fixes in `docx-lines.js`, night mode in `docx-night.js`), XLSX `xlsx.html` (SheetJS for the cells, with their looks, each sheet's layout, its tables and its pictures read in `xlsx-styles.js`, Excel's built-in table styles in `xlsx-tables.js`, made by `scripts/table-styles/make.py`, conditional formatting worked out in `xlsx-rules.js`, and the grid, frozen panes, tapped cell's bar and zoom drawn by `xlsx.js`), PPTX `pptx.html` (PPTXjs, patched from outside in `pptx.js`), MD `md.html` (marked and DOMPurify), TEXT `text.html`, PROSE `prose.html` (Gander's own .doc, .odt and .rtf readers), MODEL `model.html` (Gander's own WebGL STL viewer), EPUB `epub.html` (Gander's own reader over JSZip and DOMPurify, a chapter at a time in a shadow root, with a CSS tokenizer in `epub-css.js` that keeps every address inside the book), and UNSUPPORTED `unsupported.html`, which offers to show the file as text.

## The WebView, and how a document reaches it

The viewer loads `https://appassets.androidplatform.net/assets/viewer/<page>?name=&ext=&ranged=&length=&night=&resume=&at=&type=&top=` plus floor parameters. Its `shouldInterceptRequest` hands every request to `DocumentServer`, which answers everything:

- `/doc/...` is the open document, streamed from the ContentResolver with `nosniff`. Ranges are offered from 16 MB (`DocRange.kt`), but on phones the WebView sends `range` in lower case, which `docResponse` misses, so big PDFs are read whole.
- `/render/...` serves the simple view's pages, only to `pdfsimple.html`.
- `/assets/...` comes from `WebViewAssetLoader`, with the Content Security Policy from `ViewerPolicy.kt` added as a header. Every page also carries it in a meta tag, and `ViewerPolicyTest` keeps the copies identical.
- Any other http or https request gets an empty 404.

JavaScript is on. File and content access, DOM storage and cookies are off. Navigation is allowed only to Gander's own `.html` pages. If the renderer process dies, or the phone has no working WebView to make one with, the viewer shows a card with Reload, which recreates the activity.

Shared page code: `app.js` (status and error cards, the WebView floor card, fetching the document, link disarming, fit height, the screen's width in device pixels), `app.css` (colour tokens, light and dark), `pdf-pages.css` (the page frame and night mode both PDF views draw), `find.js` (search inside non-PDF pages, across parts for books and workbooks). `pdf.mjs` is the only ES module.

## The isolated renderer

`PdfRenderService` runs in an isolated process: no storage, network or Gander data. Five services share its code: `PdfThumbService` for thumbnails and four slots for the simple view (`RendererSlots`, least recently used gives way). `PdfRenderClient` binds one, hands it a regular file open read-only, and makes every call one-way, with the answer as counted bytes on a pipe Gander made. `PdfPipe` caps page sizes (8,192 px a side, 8 megapixels, less on low-memory phones) and turns rows into PNGs. A crash, or 20 s with no answer, while it works shows the renderer-gone card (`SimplePdf`); one Android reclaims while idle is restarted when a page is next asked for.

## The channel between Kotlin and a page

There is no `addJavascriptInterface`. On page finished, Kotlin creates one HTML message channel and posts one end to the page, addressed to the asset origin, as `vw-search-port`. Pages that search themselves get it, and so does any paged document (PDF, simple view, Word, EPUB) for its readout and night mode.

- Kotlin to page (`PortCommand`): `q` plus a query, `n` next, `p` previous, `c` clear, `g` plus a page, `i1` or `i0` night mode, `t` and `b` for the title bar's height and how much of it is hidden, `o` a book's contents, `x` Back over the book.
- Page to Kotlin (`parsePortMessage`): `page N M`, `at total done` for the search counter, `at chapter past` for a book's place, and the words `locked`, `cover 1` and `cover 0`, `reflow 1` and `reflow 0`, and `simple`. Anything else is dropped.

Night mode, the page or place to resume at, and the title bar's height also travel on the URL, so they survive process death and a renderer restart.

## The viewer's chrome

A native fast-scroll thumb serves every WebView page but `imgweb.html`, reading the scroll through a `WebView` subclass, and keeps the back gesture off itself. A page readout shows "N / M" for PDFs and Word, or a percentage for books, and opens Go to page. The title bar scrolls away with a document (`DocumentChrome`). A double tap zooms PDFs, Word, prose, slides and pictures to twice the fitted width (`DoubleTapZoom`); a sheet zooms itself, pinches and double taps both, so its headings and frozen panes stay on screen. Night mode darkens the parts around a turned-over page (`NightChrome`). Print sends a PDF's file, or a Word or prose page, to Android's print screen (`Print.kt`). The PDF page or book place is saved on stop under a fingerprint of the file (`Positions`), never for a file inside a zip. Save a copy writes the file wherever the picker points (`SaveCopy`).

## WebView floors

`WebViewFloor.kt` reads the Chromium version from the user agent. Below a page's floor the page shows a card instead of failing: PDF needs 125, Markdown 93, Word 80. Search in the page needs 105, below which the WebView's own find is used.

## Dependencies

Gradle: androidx core-ktx 1.15.0, appcompat 1.7.0, recyclerview 1.3.2, webkit 1.16.0, exifinterface 1.3.7, Material 1.12.0, Media3 ExoPlayer and UI 1.5.1, subsampling-scale-image-view 3.10.0. AGP 8.13.2, Kotlin 2.1.0, Gradle 8.14.6. Tests: JUnit 4, Truth, Robolectric 4.16.1, Espresso.

Vendored JavaScript in `assets/viewer/lib/`, recorded in `docs/VENDORED.md` and fetched by `scripts/fetch-viewer-libs.sh`: pdf.js 6.3.289 legacy build with its CMaps and wasm decoders, docx-preview 0.4.1 with JSZip 3.10.1, SheetJS CE 0.20.3, marked 18.1.1, DOMPurify 3.4.17, and PPTXjs 1.21.1 with jQuery 1.11.3, D3 3.5.10, NVD3 1.8.1-dev and JSZip 2.4.0. PPTXjs is unmaintained and carries one patch (`scripts/pptxjs.patch`). `VendoredLibsTest` pins every library file's SHA-256.

## Constraints and their guards

- No permissions: `check<Variant>Permissions` fails `assemble` and `bundle` if the merged manifest asks for any, and Media3's ACCESS_NETWORK_STATE is stripped.
- Nothing leaves the device: no INTERNET permission, a 404 for every foreign request, the CSP, WebView metrics and Safe Browsing off, `allowBackup` false, and data extraction rules that exclude everything.
- Small: about 5 MB. pdf.js and its CMaps are the largest part; price every new format or library in bytes first.
- minSdk 26, no native libraries, few dependencies. PDFium is Android's own, not shipped.

## Tests and CI

- Unit tests (Robolectric, `app/src/test`), covering routing, ranges, the port protocol, the zip reader and its fuzzer, the renderer's pipes, both activities, the policy and release hygiene.
- Viewer tests (pytest and Playwright, `tests/viewer`): every page in a real Chromium against a server that imitates the app, with reference screenshots on Linux only.
- Device tests (`app/src/androidTest`): nightly on an emulator and on tags; the PDF ones need WebView 125 or newer.
- CI: `build.yml` on every push and pull request runs the viewer tests in the Playwright container, then unit tests, lint against `app/lint-baseline.xml` with warnings as errors, and the debug and release builds, with Kotlin's warnings as errors too. Dependabot offers newer Gradle dependencies and Actions monthly. `device.yml` runs nightly and on tags.
