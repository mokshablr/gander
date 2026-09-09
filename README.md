<p align="center">
  <img src="docs/social-preview.png" alt="Gander: take a gander at any file. Open source Android file viewer for PDF, DOCX, XLSX, PPTX, JPG, MP4, MP3 and Markdown. 100% offline, 5 MB APK, zero permissions, no ads or trackers.">
</p>

# Gander 🪿

**Take a gander at any file.** A tiny, open source, fully offline **file viewer for Android** that opens
PDF, Word (`.docx`), Excel, PowerPoint (`.pptx`), photos, videos, audio, Markdown, text and code
in one app, with **zero permissions, no ads, no tracking and no internet access at all**.

> [!IMPORTANT]
> ## 🪿 Gander needs 12 testers to reach the Play Store
>
> Google will not let a personal developer account publish until **twelve people
> have used the app for fourteen continuous days**. That is the only thing left
> between Gander and everyone who will never install an APK by hand.
>
> **Two minutes, then forget about it:**
>
> 1. Join [**the tester group**](https://groups.google.com/g/gander-testers). It must be the same Google account you use on the Play Store, and this is the step that quietly goes wrong.
> 2. Open the [**opt-in link**](https://play.google.com/apps/testing/com.arjun.gander) and accept.
> 3. Install it, and **set it as your default for PDFs**.
>
> Step 3 is the one that counts. Google checks the app was genuinely used, not
> just installed, so twelve untouched installs fail. Making it your default costs
> you nothing after the first tap.
>
> It is the same app, the same signing key and the same zero permissions, and
> the GitHub releases are not going anywhere.
> **[Full details and questions →](https://github.com/mokshablr/gander/discussions/14)**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/mokshablr/gander)](../../releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/mokshablr/gander/build.yml?branch=main)](../../actions)
![Min API](https://img.shields.io/badge/minSdk-26%20(Android%208)-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-100%25-purple)

Every phone ships with a dozen half-viewers that bounce your documents to cloud services.
Gander is the opposite: one small APK (about 5 MB) that renders everything **on the device**.
It cannot phone home because it does not even hold the INTERNET permission.

**[arjun.maniyani.com/gander](https://arjun.maniyani.com/gander/)** &middot;
[Privacy policy](https://arjun.maniyani.com/gander/privacy.html)

<p align="center">
  <img src="docs/demo.gif" width="300" alt="Gander demo: thumbnail recents, folder browsing, PDF, Word, Excel and Markdown viewing">
</p>

## Screenshots

| Home: recents and folders | PDF | The same PDF, night mode |
| :---: | :---: | :---: |
| ![Recent files with thumbnail previews and granted folders](docs/screenshots/home.png) | ![PDF viewer rendering a site survey report with a photographic plate](docs/screenshots/pdf.png) | ![The same page with night mode on: the paper is black and the text white, the blue headings are still blue, and the photograph is left exactly as it was printed](docs/screenshots/pdf-night.png) |

| Photos | Word (.docx) | PowerPoint (.pptx) | Excel (.xlsx) |
| :---: | :---: | :---: | :---: |
| ![Full-size photo in the zoomable image viewer](docs/screenshots/photo.png) | ![Word document viewer](docs/screenshots/docx.png) | ![PowerPoint slides viewer](docs/screenshots/pptx.png) | ![Excel spreadsheet viewer with sheet tabs](docs/screenshots/xlsx.png) |

## Features

- **One viewer for everything**: documents, spreadsheets, slides, images, video, audio, Markdown, code
- **Pinch zoom and smooth scrolling** everywhere, with deep zoom into huge photos (tiled decoding)
- **Recent files** with thumbnail previews (image, video frame, PDF first page)
- **Folder browsing** through one-time system grants, still without any storage permission
- **Share sheet and "Open with" integration**: share a file from any app (chat, mail, browser) into Gander, or tap it in a file manager
- **Find in document**: search inside PDF, Word, Excel, slides, Markdown, text and code with match navigation
- **Select and copy text in a PDF**, and read one with a screen reader
- **Night mode for PDFs**: turns the page over for reading in the dark, keeping each colour's hue, turning scans and figures over with the text, and leaving photographs exactly as they were printed
- **Share and locate**: send the open file to any app, or jump to its folder in the file manager
- **Private by construction**: no permissions, no INTERNET, no analytics, no accounts, nothing leaves the phone
- **Checks its own promise**: the About screen asks Android what the app requests and shows you the answer, next to the full licence text for every bundled library
- **Modern Android**: Material 3, dark mode, edge to edge, works on Android 8.0+

## Supported formats

| Category | Formats | Renderer |
| --- | --- | --- |
| Documents | PDF | pdf.js, offline in a sandboxed WebView |
| | Word `.docx` | docx-preview, offline in a sandboxed WebView |
| Spreadsheets | `.xlsx` `.xls` `.xlsm` `.xlsb` `.csv` `.ods` | SheetJS, offline |
| Slides | PowerPoint `.pptx` | PPTXjs, offline |
| Photos | JPG, PNG, WebP, BMP, HEIC/HEIF | Tiled deep-zoom image view, EXIF aware |
| | GIF (animated), SVG, AVIF, ICO | WebView |
| Video | MP4, M4V, MOV, MKV, WebM, 3GP, AVI, FLV, MPEG-TS | Media3 ExoPlayer |
| Audio | MP3, M4A, AAC, FLAC, WAV, OGG, Opus, AMR | Media3 ExoPlayer |
| Markdown | `.md` rendered as formatted HTML | marked + DOMPurify, offline |
| Text and code | `.txt` `.json` `.xml` logs, most source files | Text viewer |

Anything else, including files with no extension at all, offers **View as text**, which
shows the raw contents without renaming the file. Large files load 5 MB at a time with a
**Show more** button, so they open instantly and can still be read end to end.

Legacy binary `.doc` and `.ppt` are not supported (no faithful offline renderer exists);
the app explains this and suggests re-saving as `.docx` / `.pptx`. Binary `.xls` works.

## Install

Runs on **Android 8.0 (API 26) and up**.

Viewing PDFs also needs Android System WebView 125 or newer (May 2024). Any phone
still receiving WebView updates is well past that; if yours is not, Gander says so
when you open a PDF rather than failing quietly.

1. Download the latest APK from [Releases](../../releases/latest):
   `Gander-x.y.apk` runs on every architecture, since the app ships no native code.
2. Copy it to your phone, tap it, and allow "install unknown apps" when asked.
3. Optional: Play Protect may warn about an unknown developer; that is what
   sideloaded open source looks like. Tap "Install anyway".

Updating: install the new APK over the old one; recents and folder grants survive.

**Automatic updates without a store**: install
[Obtainium](https://github.com/ImranR98/Obtainium) and add
`https://github.com/mokshablr/gander` as an app source. It follows the tagged
GitHub releases here and updates Gander like a store would.

**Verify before installing**: every release is signed with the same key, so you can
confirm an APK really came from this repo. Obtainium can pin the fingerprint below,
and for a file you have already downloaded:

```sh
apksigner verify --print-certs Gander-x.y.apk
```

Signing certificate SHA-256:

```
5B:5C:F6:4A:94:23:7C:D5:F0:E0:85:76:00:38:BC:1C:EB:DF:18:DA:BA:5C:B3:EA:CA:7C:15:9F:22:A7:E2:4B
```

## How the zero-permission trick works

Gander receives files through the Storage Access Framework and "Open with" intents,
so the OS hands it exactly the documents you chose and nothing else. Office formats
render inside a locked-down WebView whose every request is intercepted by
`WebViewAssetLoader`: bundled JS libraries load from app assets and the document
streams from the content URI. No network stack is ever touched, and the app does
not declare the INTERNET permission, so there is nothing to audit or trust.

Folder browsing uses `ACTION_OPEN_DOCUMENT_TREE` grants. Note that Android itself
refuses to grant the Downloads root to any app; grant Documents, DCIM or a
subfolder of Downloads instead.

## Build from source

To build it yourself you need JDK 17+ and the Android SDK (platform 36). These are
build requirements only. The installed app runs on Android 8.0 (API 26) and up.

```sh
./gradlew assembleDebug        # installable debug build
./gradlew assembleRelease      # unsigned without a keystore
```

Release signing expects a local, untracked keystore at `keystore/gander.jks`,
alias `gander`. Generate your own with:

```sh
keytool -genkeypair -keystore keystore/gander.jks -alias gander \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass gander-local -keypass gander-local -dname "CN=Gander"
```

That builds and signs with no further setup, because `gander-local` is the
fallback password in `app/build.gradle.kts`. To use a different one, set it in
`~/.gradle/gradle.properties` rather than in the build file:

```properties
GANDER_STORE_PASSWORD=…
GANDER_KEY_PASSWORD=…
```

The keystore is gitignored on purpose: it is a personal signing key and must
never land in a public repo. Neither should its password, which is why the real
one lives outside the tree. Builds signed with your own key will not update an
install of a release from here; the official signing certificate is above.

## Architecture in one paragraph

`ViewerActivity` routes by file extension first, MIME type second (`FileKind.kt`),
into one of three surfaces: a tiled `SubsamplingScaleImageView` for photos, Media3
ExoPlayer for video and audio, or a sandboxed WebView for everything rendered by
vendored JS libraries (`app/src/main/assets/viewer/`), PDF included. Documents
under 16 MB are handed to the WebView whole; larger ones are served in ranges so
only the pages being read are held in memory. The home screen (`MainActivity`) lists recents
(persisted SAF grants) and granted folders (DocumentsContract child queries), with
thumbnails generated off-thread and cached (`Thumbs.kt`).

Vendored viewer libraries and their licenses: pdf.js (Apache-2.0), JSZip (MIT),
docx-preview (Apache-2.0), SheetJS CE (Apache-2.0), PPTXjs + divs2slides (MIT),
jQuery 1.11 (MIT), D3 3.x + NVD3 (BSD/Apache), marked (MIT), DOMPurify
(Apache-2.0/MPL). The app ships no native code.

## Roadmap

- F-Droid listing
- Legacy `.doc` / `.ppt` support if a usable offline renderer appears
- iOS companion (thin QuickLook wrapper)

## Contributing

Issues and small PRs are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).
If Gander is useful to you, a star helps other people find it. There is a
[sponsor page](https://github.com/sponsors/mokshablr) as well, though a good bug
report is worth more.

## License

[MIT](LICENSE), Copyright (c) 2026 [Arjun Maniyani](https://arjun.maniyani.com/).
Vendored viewer libraries keep their own licenses, listed above; all are
MIT/Apache/BSD and compatible. The full text of every one of them ships inside
the app, in `app/src/main/assets/licences.md`, reachable from About.
