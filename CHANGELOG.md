# Changelog

## Unreleased

- A PDF whose images are JPEG 2000 shows them. Since pdf.js 4 the JPEG 2000 and JBIG2
  decoders have lived in WebAssembly rather than in the bundle, fetched at the moment a
  page meets an image needing one, and Gander shipped neither binary and never told
  pdf.js where to look for them. The failure had nothing to notice it by: the worker
  warns to a console nobody is reading, returns nothing, and the image is simply left
  out, so a document arrives looking like its own layout with holes in it rather than
  like something that went wrong. Issue #24 was a fitness book whose 190 images were 186
  JPEG 2000, so most of it came up blank.

  Scanned pages were failing the same silent way and are fixed with it. pdf.js keeps
  JBIG2 and CCITT fax in one module, and CCITT is what a scanner or a fax reaches for
  most of the time, so a black and white scan had been arriving as an empty frame since
  the version of pdf.js that moved them out of the bundle. Nobody had reported that one.

## 1.16 (2026-09-08)

- Dragging a text selection to the bottom of a PDF now scrolls the document, so a selection
  can run past the fold without letting go and starting again. Holding a handle against an
  edge never scrolled anything, and that is not particular to this app: Chrome for Android
  does the same. What made dragging upward look as though it worked is the browser pulling a
  part-visible line into view a line at a time, which dragging downward can never trigger,
  because the deepest thing under the finger is a line that is already fully on screen. The
  page now follows the end of the selection into the last few millimetres of the screen, in
  both directions, at a speed set by how far past the edge the finger is.

- Dragging to select text in a PDF follows your finger. It used to freeze partway and then
  jump, taking a heading, the rest of a paragraph or a whole page with it. Two things were
  behind it. WebView floors rendered text at 8 pixels where Chrome does not, and pdf.js
  measures that floor and lays every word out eight times too large to compensate, which
  left the selection resolving to the page rather than to the words on it. And a text layer
  only covers the words themselves, so the space between lines, beside a short line, and in
  a page's margins belonged to nothing a finger could land on; a touch that missed every
  word got the same answer wherever it was, which is what the freeze was. Lines are now
  given the space around them, out to the next word along and to the page edges, and the
  margin between two pages is bridged so a selection can cross it. Nothing moves on the
  page: the words are where they always were, and a highlight still hugs the text.

- Removing several recents or folders quickly shows one "Removed" badge rather than one per
  removal. The framework queues badges and plays each for its full couple of seconds, so a
  dozen removals in a burst kept a badge on screen for another half a minute after the last
  one. A removal now replaces the badge in front of the reader instead of joining a queue
  behind it.

- Removing a granted folder asks first. It is the only thing on the home screen that cannot
  be undone, since Android has no way to hand a released permission back, and the route to
  one removed by accident was a fresh trip through the system picker. A recent file still
  goes on the long-press alone: it costs a tap to open again, and the list prunes itself at
  twenty-five. Cancel is set in a neutral rather than in the app's own colour, which is a
  burnt red near enough to the error red on Remove that the two together marked nothing and
  only made Cancel look dangerous too.

- Screen readers name the gesture. A row that can be removed now reads as "double tap and
  hold to Remove" rather than an unnamed long-press, and the headings and Add a folder have
  stopped offering a press that did nothing.

- The line shown while a document is being built sits in the middle of the screen. It was a
  card in the flow at the top, which on an otherwise empty screen read less as the state of
  the screen than as a notice about something else. Errors and the PDF password prompt sit
  there too, which also means an error raised after a long document is already open is now
  in front of the reader rather than at a top they have scrolled past.

- That card is legible in Word, slide, spreadsheet and image documents. Those pages are laid
  out at 980 pixels and zoomed down to fit the screen, which had been shrinking the card and
  its text to about 40% of the size they were written at. The PDF viewer already corrected
  for this; the rest now do the same.

## 1.15 (2026-09-06)

- A document that fits on the screen sits in the middle of it. A PDF or Word page is about
  two thirds of a phone screen tall and a slide is under a third, so a one-page document or
  a short deck stood at the top with a band of empty ground below it rather than in the
  middle, where the eye rests and where a photograph in Gander has always gone. Anything
  longer than the screen is exactly where it was.

- A Word page fills the width of the screen. It was drawn at its real paper width, 816
  pixels for US Letter against the 980 the viewer lays out at, so a page sat at 83% of the
  screen with a strip of ground down each side, and the text inside it, once Word's own
  margins were taken off, was under sixty per cent. It is now scaled to fit, the way a PDF
  page and a slide always have. No line break moves: it is the same page, about a fifth
  larger.

- Night mode for PDFs, from the viewer's menu. A page stayed white at midnight however the
  phone was set, which is right for a Word page and wrong for reading a long PDF in bed.
  Paper goes black and text goes white, and colours keep their hue rather than swapping to
  the opposite one, so a blue heading comes back blue rather than orange. Photographs are left
  exactly as they were printed, while the things that only look like images - a scanned page, a
  figure exported as a picture, a logo on a letterhead - turn over with the text around them,
  so none of them is left as a white rectangle on a dark page. The one thing it gets wrong is a
  photograph pale enough to pass for paper itself, which it will turn over. Nothing is written
  to the file, and the setting is remembered.
  (thanks @MaxKash-06, who asked for this)

- Zoomed-in PDFs are sharp. A page was drawn once at a fixed size, and pinching in
  magnified that picture rather than drawing a better one, so anything taken past about
  twice its fitted size went soft. That is why a tube map or a site plan was unreadable at
  the magnification it actually needs. The part of the page you are looking at is now drawn
  again at the zoom you are at, about a third of a second after you stop pinching, however
  far in you go. Pages are also drawn at the screen's own resolution rather than at twice
  it, now that they no longer need to hold spare detail for pinching, so each one keeps
  about a third of the picture it used to.

## 1.14 (2026-08-30)

- The website asks for something now. A link to it rendered as a bare URL wherever it was
  shared, because the page carried no preview card at all; it has one now, and so does the
  privacy policy. Get it leads with joining the closed test rather than mentioning Play in
  passing, the zero-permission section shows Android's own App info screen for Gander with
  the permissions row greyed out and nothing behind it, four people are quoted on what they
  made of it, and GitHub and LinkedIn are linked. The Play badge is already written into the
  page behind a switch, so the day the listing goes live is a one-word edit to one file.
- PDFs have text in them now, as far as the rest of the app is concerned. Pages were drawn
  as pictures and nothing else, so there was nothing to select, nothing to copy, nothing for
  a screen reader to read out and nothing for the search box to look through, which is why
  the search button was hidden for PDFs from 1.9 onwards. Each page now carries an invisible
  layer of the words it contains, laid exactly over the picture of them, so a PDF selects and
  copies like any other document and a screen reader can read one aloud.
- Find in document works in PDFs. It searches the whole file rather than the handful of pages
  currently drawn, which is the reason it could not simply use the same machinery the other
  formats do: Gander only keeps about eleven pages of a document in memory at a time, and a
  search that only looked at those would quietly miss the rest. Counting, next and previous
  behave as they do everywhere else. On a long document the count climbs while the file is
  read, rather than the box waiting for the end of it.
- Back closes the search bar rather than the document. Opening the find box on a long PDF,
  typing, and pressing Back used to close the file outright, losing both the query and where
  you had got to, and from Android 15 on it did that while playing the animation that slides
  the app away, so it looked like the phone had decided to leave. Back now closes the box and
  leaves you where you were reading; a second press leaves the document, as before. With the
  keyboard up it takes one press more, because the keyboard goes first.
- A PDF tells you which page you are on. A pill at the foot of the screen reads "128/357"
  while you scroll and fades once you stop, so a long document is no longer something you
  move through blind. It appears as soon as the file opens rather than waiting for you to
  cross a page boundary, and it is measured against what is actually on screen, so it stays
  right when you have pinched in to read something small.
- You can jump to a page in a PDF. Tap the page pill, or pick Go to page from the menu,
  type a number, and you are there. It goes straight to the page rather than travelling
  through everything in between, which on a long document is the difference between
  arriving and waiting.
- Long documents have a scroll thumb you can drag. It appears at the right edge once there
  is more than a couple of screens to move through and fades when you stop, and the page
  readout at the foot of the screen keeps count as you go. It works in Word documents,
  spreadsheets, slides, Markdown and text files as well as PDFs, where it shows how far
  through you are rather than a page number. On a phone using gesture
  navigation it takes its own strip of the edge, so reaching for it is not read as going
  back. Gander still opens every document at the beginning rather than where you left off,
  which is a separate thing and not done yet.
- Browsing a granted folder no longer freezes the screen. Gander asked the folder for its
  contents on the same thread that draws, so opening one holding a few thousand files, or
  one on an SD card or a USB stick, stopped the app dead until the answer came back: over
  a second of frozen screen on a folder of 1,500 files, and the sort of thing Android
  eventually reports as "app isn't responding". The reading happens off to one side now.
  The folder name and the back arrow change at once, the list you were looking at stays up
  until the new one is ready rather than blinking through empty, and a thin progress line
  appears only when the wait is long enough to be worth mentioning. Naming a granted folder
  was also costing a lookup every time two of them were compared while sorting; that is one
  each now, and remembered.
- Gander looks like Gander now. The website, the launcher icon and the social preview have
  run one palette for a while: warm paper, cool ink, a terracotta accent. The app never got
  it. It set exactly one of Material's thirty-five colour roles, so the other thirty-four
  fell back to Material's own defaults, which are built on purple, and that is why the
  "Open a file" button was lavender and the white behind it faintly violet, under headings
  that were blue. All thirty-five are set now, from the same red the website uses, and the
  night palette is derived from it rather than guessed at. Four of the file badges moved
  too: three were failing the contrast standard against their own white lettering, the amber
  folder badge worst of anything in the app, and the PowerPoint orange sat close enough to
  the new accent that a slide deck and the app's own colour would have read as one thing.
- Documents follow your dark mode. Opening a spreadsheet at night used to be a full screen
  of white: only the Markdown and text viewers had ever been told about dark mode, so the
  loading and error cards, a Word document's surround, the unknown-format card and the sheet
  tabs along the top of a workbook stayed light whatever the phone was set to. They all turn
  over now, in the colours the rest of the app uses at night. The document itself does not,
  and that is deliberate: a Word page is paper, and paper is white at midnight too. Turning
  the contents of a PDF dark is a different thing and is still to come. The light theme moved
  as well, off the blue-grey these viewers were built on and onto the warm ground the app now
  has, so the surround stops changing temperature at the bottom edge of the toolbar.
- The last two cool surfaces went warm with it. A PDF page and a slide were read against a
  blue grey chosen back when the accent was blue, and they were the only part of the app left
  on the old palette. Both grounds were matched on lightness rather than picked by eye, so a
  page stands off its surround by exactly as much as it did before and only the temperature
  has moved. The prompt that asks for a locked PDF's password got the brand colour on its
  button while the file was open.
- An audio file gets a screen of its own. An MP3 used to be handed the video player: a black
  rectangle whose controls took themselves away after two and a half seconds and left nothing
  at all behind, with the display held awake for the length of the track, so an hour of
  podcast lit an hour of blank screen. The controls stay put now, because on a file with no
  picture they are the only thing on it. The screen is allowed to sleep, because nothing is
  being looked at. And the track's own cover art is shown if it carries any, or a plain note
  if it does not, with the seek bar and the clock sitting under it rather than pinned to the
  bottom edge of the screen, which is where a video puts them to keep them off the picture.
  Playback still stops when you leave Gander, and always will: carrying on in the background
  needs a foreground service, a service needs a permission, and a permission is the one thing
  this app will not add.
- Turning the phone no longer loses your place in a folder. Browsing three folders into a
  granted tree and rotating dropped you back to the top of the list with no way to tell why,
  and so did changing the system font size, switching to dark mode, or resizing the app in
  split screen. Where you had got to survives all four now. It was hard to meet on a phone,
  which is rarely turned mid-browse, and constant on a tablet, which is.
- Gander's own name is written in Gander's own typeface. The title at the top of the home
  screen was Roboto, the system default, because the geometric face the website has used
  from the start is one Android does not ship and quietly substitutes
  something else for. The app was showing whatever the phone happened to have, and so, it
  turns out, has the website, on every phone that has ever visited it. The name is drawn now
  rather than typed, so it looks the same on every device, and it sits in the middle of the
  bar with the app icon beside it. Go into a folder and the bar shows that folder's name
  instead, on the left, next to the way back out.
- The home screen lays itself out for a tablet. It was a phone screen stretched: one column
  of filenames with about nine tenths of the width empty beside them, and a first-run block
  drawn at phone size in the middle of a much larger screen. Files and folders sit in two
  columns on a large screen now, section headings still run the full width, and the welcome
  block is drawn to suit the screen it is on. Nothing about a phone changes.
- A fresh install says what Gander is, in about twenty words. The home screen used to open on
  an empty Recent files heading, an empty Folders heading, and three sentences about Android
  refusing to grant the Downloads root, so the first thing anybody read about the app was a
  limitation of somebody else's software. It opens now on a single line, the nine kinds of
  file it handles shown as the same coloured tiles the file list uses further down, the
  promise that nothing is uploaded, and the two ways to begin: open a file, or add a folder.
  The paragraph naming every extension is gone for good, and so is a tip that was coloured
  like a link and was not one, though that tip still reads under About, which is where
  somebody who wants the detail goes looking. The whole block leaves for good the moment you
  open a file or grant a folder. The Downloads note is gone entirely: Android says the same
  thing itself, in the picker, at the moment it matters.
- Chinese, Japanese and Korean text shows up in PDFs that do not carry their own fonts.
  A document written in one of these scripts often names a standard encoding rather than
  embedding the characters it uses, and Gander was missing the tables that turn those names
  back into readable text. The result was not an error message or a row of empty boxes: the
  page opened looking perfectly finished, with entire paragraphs silently absent, so there
  was nothing to tell you that you were reading a document with pieces taken out of it. The
  tables ship with the app now, all of them, so this holds for older documents as well as
  current ones and for all three scripts rather than the one that happened to be reported.
  Carrying every table costs 1.6 MB on disk, and the download grows with it: the APK is
  4.7 MB against 1.13's 3.7 MB. That is the price of not having to guess in advance which
  scripts a reader is going to open.

## 1.13 (2026-08-25)

- Gander is a good deal smaller to download. Its own code is under 1,700 lines, but the
  Android UI libraries underneath it were being packaged whole whether or not anything
  called into them: 14.8 MB of compiled code in the 1.12 build, of which 2.4 MB is all
  the app can reach. R8 now runs over the release build and keeps the reachable part,
  and the same pass drops the resources nothing asks for. The APK goes from 8.3 MB to
  3.7 MB and the Play bundle from 7.6 MB to 5.1 MB. Nothing is missing from it: every
  screen, every format and every menu item is the one that shipped in 1.12. Class names
  are shortened on the way through, which buys no secrecy for an app whose source is
  public, but file names and line numbers are kept deliberately, so a crash pasted into
  an issue still points at a real line.

## 1.12 (2026-08-23)

- Large PDFs no longer bring the phone to its knees. Gander lays out an empty box for
  every page in a document before it draws any of them, so the scrollbar is the right
  length from the moment it opens, and until now those boxes had no size: each was
  300 by 150 pixels, a business card standing in for a page. That was visible as a
  column of small white rectangles whenever you scrolled faster than pages could be
  drawn, and it was the cause of a good deal more besides. Gander decides which pages
  are close enough to be worth drawing by looking a fixed distance ahead of you, and
  at that size about fifty of them fitted in the distance meant to hold three or four,
  so it started fifty at once, kept every one it had ever finished, and kept the
  artwork it decoded for them on top. On a 357-page illustrated rulebook, one flick
  through it took the renderer past 900 MB and Android killed it, sometimes taking
  other apps' browser windows down with it. The boxes are now the shape of the page
  they stand for, no more than three pages are ever drawn at once, a page you have
  flung past stops being drawn rather than finishing into a bitmap nobody will see,
  and a page far enough behind you gives back both its bitmap and its artwork. How far
  ahead Gander draws is now counted in pages rather than in screenfuls, which is the
  only version of that number that means the same thing when you turn the phone
  sideways. The rulebook now sits flat at about 180 MB however it is read, and a
  520-page, 114 MB ebook at about 300 MB, neither of them climbing
  (thanks @logannc, who found this and worked out that scrolling made it worse, and
  @mjschwart, who reproduced it on quite different hardware with a much heavier file)

## 1.11 (2026-08-21)

- A PDF that wants a password now asks you for it, rather than showing you the error
  its renderer raised. Type it and the document opens; get it wrong and it says so and
  lets you try again, as often as you like, without rereading the file. The prompt is
  drawn in the page rather than as an Android dialog, because Gander deliberately has
  no bridge between a document and the app in either direction and a password is not
  worth opening one for. Nothing is kept: it lives as long as the document is on screen
  and goes when you leave. A file encrypted in a way the renderer cannot read at all
  now says so in a sentence too, instead of printing the exception at you
  (thanks @juxuanu, who reported this and pointed at GrapheneOS's PdfViewer)

- The cards the PDF viewer puts on screen, including the one explaining that a phone's
  browser engine is too old for PDFs, were being drawn at about a third of the size
  they were meant to be. A PDF page is laid out at a fixed width and zoomed to fit,
  which is what keeps it sharp, and the cards had been quietly riding along with it.
  They are sized for that zoom now rather than against it.

- The file you are looking at can be kept, through **Save a copy** in the viewer menu.
  It is meant for the file that came from somewhere else: an attachment, or a document
  another app hands over, which Gander is allowed to read only for as long as that
  screen is open and which is out of reach once you leave. Where the copy goes is
  chosen in Android's own picker, so this asks for no permission and Gander is handed
  the one file it just wrote and nothing around it. A large copy reports its progress
  under the toolbar, and one that fails takes its half-written file with it rather than
  leaving something that opens and looks complete (thanks @sebestyn)

- A document left open no longer takes the whole app down with it when Android runs
  short of memory. Everything drawn by the sandboxed viewer, PDFs included, is
  rendered in a process of its own, and Android is free to end that process while
  Gander is in the background. Sharing a file to a large app is exactly that
  situation: the other app starts up in front, and the process holding the document
  is left sitting there with nobody looking at it. Gander had never answered that
  notification, and not answering it does not mean nothing happens, it means the
  framework kills the application, so the app disappeared with no crash of its own
  behind it and nothing on screen to account for it. It now stays open, says that
  Android closed the document to free up memory, and offers to load it again
  (thanks @rosyzc7 for the report that led here)

## 1.10 (2026-08-13)

- There is an About screen now, in the menu on the home screen. Rather than repeat
  a promise typed into it months earlier, it asks Android what permissions this
  install actually requests and shows you the answer. The build has always failed
  if a dependency slipped one in; this is the same check somewhere you can see it
- The full licence text for every bundled rendering library now ships inside the
  app and opens from About, drawn by Gander's own Markdown viewer. It was in the
  repository before, which is not the same as travelling with the app, and
  travelling with the app is what those licences ask for
- Gander no longer takes part in Android's app backup. The recents list was the
  only thing there was to copy, and a restored one is always empty anyway, because
  the folder permissions it points at do not survive being moved to another phone.
  So it was putting your file names into a Drive backup in exchange for nothing
- Phones whose WebView is too old for PDFs are recognised properly now. The check
  read the version off the WebView package, and a manufacturer that numbers its
  own builds 15.0.4.326 was read as version 15, dismissed as not a real version,
  and let through. Those phones then failed on the PDF with a parser error naming
  a file nobody has heard of. The version now comes from the browser engine itself,
  which reports it the same way no matter who makes the phone (thanks @sdiddssew)
- On a phone whose WebView cannot be updated, because the manufacturer supplies it
  and allows no replacement, the PDF message no longer tells you to go and update
  it. It says PDFs will not work on this phone and leads with the fact that every
  other format still opens, which is the part you can act on

## 1.9 (2026-08-06)

- Files Gander cannot render now offer a **View as text** button instead of a
  dead end, so a file with no extension can be read without renaming it to
  `.txt` first (thanks @immanuelfodor)
- The text viewer reads large files in 5 MB pages with a **Show more** button
  at the end, so a big log opens straight away instead of stalling while the
  whole thing loads, and none of it is out of reach
- Text files that start with a byte order mark, including UTF-16, now decode
  correctly instead of showing a stray character between every letter
- Gander now targets Android 16 (API 36), so it keeps working as newer
  releases tighten how apps draw behind the status and navigation bars
- The permission list is genuinely empty again. Media3 had been quietly
  adding ACCESS_NETWORK_STATE, which never did anything here because Gander
  only plays local files and has no internet access at all
- PDFs now render with pdf.js in the same sandboxed viewer that already handled
  Word and Excel, so the app ships no native code at all. The download is one
  APK for every phone instead of four, and about 8 MB instead of 15
- Very large PDFs are read a piece at a time as you scroll rather than loaded
  whole, so a 50 MB scan opens sooner and does not sit in memory while you read it
- The PDF viewer background now fills the screen behind a short document, the
  same fix the other viewers got in 1.8. It showed up most on a PDF that could
  not be opened, where the message sat on a dark band with a lighter one below
- PDFs open a little slower than before, by roughly half a second on a fast
  phone. The renderer that was faster cannot be shipped any more; it stopped
  being maintained and no longer meets current Play requirements
- The search button no longer appears for PDFs. PDF pages are drawn as images
  with no text behind them, so a search could only ever come back empty. Search
  in Word, Excel, slides, Markdown, text and code is unchanged
- On a phone whose Android System WebView is too old to run the PDF renderer,
  the viewer now says so and names the version needed, instead of sitting on
  "Rendering document…" with nothing to explain it. Updating Android System
  WebView fixes it, and no other format is affected

## 1.8 (2026-08-04)

- Short documents no longer show a grey band below the content. The viewer
  background now fills the screen in the Markdown, text, spreadsheet and
  slide views (thanks @lalalasupa0)

## 1.7 (2026-08-02)

- PDF zoom now reaches 10x instead of the previous 3x, enough to read a small
  QR code on a full page (thanks @neuos)
- OpenDocument spreadsheets (`.ods`) now appear in the Open-with list; they
  already rendered, but the MIME type was never registered (thanks to sgc on
  Hacker News)
- Screen reader support: the back button, the photo viewer, web-rendered
  images and the file rows on the home screen are all labelled now, and the
  search match count is announced as "Match 2 of 7" rather than "two sevenths"
  (thanks @freedomben for asking)

## 1.6 (2026-07-23)

- Share the open file to any app straight from the viewer toolbar
- "Show in file manager" opens the file's folder in the system Files app
  (appears when the folder can be worked out from where the file came from)

## 1.5 (2026-07-21)

- Find in document: search inside Word, Excel, PowerPoint, Markdown, text
  and code files with match count and next/previous navigation
  (PDF search is not included yet; the PDF renderer does not expose text)

## 1.4 (2026-07-19)

- Gander now appears in the Share sheet: share a document, photo, video or
  audio file from any app (WhatsApp, Gmail, a browser) straight into Gander
- Shared plain text opens in the text viewer

## 1.3.1 (2026-07-19)

- New app icon: fanned file cards on paper, matching the project artwork
  (the old eye mark read as surveillance, the opposite of what Gander is)

## 1.3 (2026-07-19)

- Thumbnail previews in Recent files and folder browsing: images (EXIF
  corrected), video frames, and PDF first pages, cached in memory and on disk

## 1.2 (2026-07-19)

- Useful home screen: Recent files (tap to reopen, long-press to remove) and
  Folders granted once via the system picker, browsable in-app with type
  badges, sizes and dates, all with zero permissions
- Note: Android refuses folder grants for the Downloads root; grant Documents,
  DCIM or a Downloads subfolder instead

## 1.1 (2026-07-19)

- Fixed toolbar sitting under display cutouts (edge-to-edge insets)
- Fixed sideways photos: EXIF orientation is now applied
- Markdown files render as formatted HTML (offline, sanitized)
- Video and audio playback via Media3 ExoPlayer, plus video/audio Open-with

## 1.0 (2026-07-19)

- First release as Gander (formerly ViewAll)
- PDF, Word (.docx), Excel (.xlsx .xls .csv .ods), PowerPoint (.pptx), photos,
  GIF/SVG, Markdown/text/code viewing, fully offline with zero permissions
- Per-ABI release APKs
