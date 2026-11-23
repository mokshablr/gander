# Google Play Data safety answers

Internal reference for filling in the Play Console. Written to match
[the privacy policy](privacy.html) exactly, because Google reviews the two against
each other and a disagreement between them is a common reason for rejection.

Last checked against the code: 26 October 2026, after version **2.7 (versionCode 35)**.

Note for anyone comparing against an older build: the backup section below relies
on `android:allowBackup="false"`, which arrived in 1.10 (`2c3076d`). The released
1.9 shipped `true`, verified against tag `v1.9` (`7527ad6`). 1.10 is the first
build to reach Play, so it is the artifact Google reviews these answers against.

The short version: **no data collected, no data shared.** Everything below explains
why that is the honest answer and not a shortcut.

---

## App content > Privacy policy

**Privacy policy URL**

`https://arjun.maniyani.com/gander/privacy.html`

Source of truth is `docs/privacy.html` in this repo; the live copy is uploaded
by hand to the `/gander/` folder on arjun.maniyani.com. GitHub Pages is
deliberately *not* enabled, so there is exactly one live copy: two would drift,
and Google reviews the policy against the answers below.

The page must stay publicly reachable with no login for as long as the app is
listed, so re-upload it whenever `docs/privacy.html` changes.

---

## Data safety > Data collection and security

**Does your app collect or share any of the required user data types?**

**No.**

Play defines collection as transmitting user data off the device, and sharing as
passing it to a third party. Gander does neither, and cannot:

- The merged manifest requests exactly one permission,
  `com.arjun.gander.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It is signature level,
  self-granted, declared by `androidx.core` so libraries can register a non-exported
  receiver, and it is never shown to the user. It gives access to nothing.
- There is no `INTERNET` permission, so the app has no way to make a network request
  at all. `app/build.gradle.kts` fails the build if any dependency adds a permission
  that has not been signed off, and it runs that check on the bundle as well as the
  APK, so this cannot regress silently into a Play upload.
- No analytics, advertising, crash reporting or network SDK is present. The full
  dependency list is AndroidX core, appcompat, recyclerview, webkit and
  exifinterface, Material Components, Media3 ExoPlayer, and one image view library.
- No accounts, no sign-in, no server.

**Is all of the user data collected by your app encrypted in transit?**

**Yes.** Vacuously: nothing is transmitted, so there is no unencrypted transmission.
Answering No would put "Data isn't encrypted" on the store listing, which would be
actively misleading. The Console may skip this question once collection is answered
No; if it does, nothing to do.

**Do you provide a way for users to request that their data is deleted?**

**No.**

There is no account and no server-side copy, so there is nothing for a deletion
request to reach. The user deletes the local data themselves, and the privacy policy
spells out how: clear cache, clear storage, or uninstall. The Console may also skip
this question when collection is No.

**Independent security review (optional badge)**

Skip. No third-party review has been done, and claiming one would be false.

---

## Data safety > Data types

Not reachable once collection is answered No. Recorded here so the reasoning survives
if a reviewer asks, and because "Files and docs" is the category someone will
reasonably expect a file viewer to declare.

**Files and docs: not declared.**

Gander reads a file only when the user points it at one, through the Storage Access
Framework picker, a folder the user granted with `ACTION_OPEN_DOCUMENT_TREE`, or an
"Open with" intent. Rendering happens on the device: PDF, Word, Excel and PowerPoint
go to a WebView whose every request Gander answers itself, with the JavaScript
libraries loaded from app assets, the document streamed straight from the content
URI, and an empty 404 for anything else. That is on-device processing, which Play explicitly distinguishes
from collection. No copy of any document is transmitted anywhere.

**Photos and videos: not declared.** Same reasoning. Decoded and displayed on device.

**Every other category** (personal info, financial info, health and fitness, location,
messages, audio recordings, contacts, calendar, app activity, web browsing, app info
and performance, device or other IDs): **not declared.** None of it is touched.

### What is stored on the device, and why none of it is collection

| What | Where | Notes |
| --- | --- | --- |
| Recents: file name, content URI, timestamp, max 25 | `SharedPreferences` (`Recents.kt`) | Filtered at read time against the URIs still holding a persisted read grant |
| Reading positions: a PDF's page, or a book's place (its chapter and how far into it), and a timestamp, max 100 | `noBackupFilesDir/positions` (`Positions.kt`) | Keyed by a SHA-256 of the file's length and first 64 KiB, so no file name or URI is stored. A PDF's first and last pages are not kept; a book's end is. `BackupAgent` always excludes `no_backup`, from cloud backup and device-to-device transfer alike |
| Viewer settings: night mode, the folder sort order, the text size of books, text and Markdown, and whether Delete deletes for good | `SharedPreferences` (`Settings.kt`) | Four values, no file names |
| Folder grants | Held by the system, not by the app (`MainActivity.kt`) | Released when the folder is removed from Gander |
| Thumbnails, about 192 px | `cacheDir/thumbs/` (`Thumbs.kt`), a folder's in a subfolder of their own | From photos, a video's first frame, or a PDF's first page. Dropped when their file is removed from Recents or their folder from Gander |
| Shared-text temp file | `cacheDir/shared/shared-text.txt` (`ViewerActivity.kt`) | Deleted when its viewer closes |

All of it is inside the app sandbox and none of it is transmitted. On-device-only
storage is not collection under Play's definition, so declaring it would be wrong in
the other direction: it would make the listing say Gander collects files when it does
not.

### The Share button

Sending the open file to another app through the share sheet is a user-initiated
transfer to a destination the user picks, with a read grant handed over for that one
share. Play does not treat a transfer the user explicitly asks for as the app
collecting or sharing data, so it is not declared.

### Android Auto Backup: closed, not argued

This used to be the one grey area. `android:allowBackup="true"` meant Android copied
`shared_prefs/recents.xml` into the user's Google Drive backup, which is file names and
content URIs leaving the device. The answer would still have been No collection (the
platform does it, not the app; it goes to the user's own account, never to the
developer; it is a user-controlled setting), but it was the one question with an
argument against it.

It is now moot. `android:allowBackup="false"` since commit `2c3076d`, verified in the
merged release manifest rather than only in source. Backing up recents achieved nothing
anyway: `Recents.all()` filters entries against `contentResolver.persistedUriPermissions`,
and SAF grants do not survive a restore, so a restored list renders empty.

Nothing the app stores is transmitted by any path, so the privacy policy claims that
without qualification and this form declares no collection with nothing to defend.

---

## App content > Target audience and content

**Target age groups:** **18 and over**, only.

**Do NOT tick any group under 13.** That enrols the app in the Families programme,
which brings its own policy, ads and content requirements, plus a separate review, for
no benefit here. Ticking 13-15 or 16-17 does not trigger Families but does add review
surface, so 18+ alone is the cleanest declaration for a general-purpose utility.

**Could your app unintentionally appeal to children?** **No.** It is a file viewer.
No games, characters, bright cartoon styling or child-oriented content. The icon and
screenshots show documents and spreadsheets.

**Does your store listing target children?** **No.**

---

## Adjacent declarations that must agree

| Declaration | Answer |
| --- | --- |
| Contains ads (store listing) | No. No ad SDK is present |
| Ads (App content > Ads) | No, my app does not contain ads |
| Content rating questionnaire | Category: Utility. Answer No to everything: no violence, sexual content, profanity, drugs, gambling, user-generated content sharing, or user-to-user communication. Should land at IARC "Everyone" / PEGI 3 |
| Data safety: does the app have a data deletion mechanism | No, matching the answer above |
| Government apps | No |
| Financial features | None |
| Health apps | No |
| News apps | No |
| App access (login credentials for review) | Not needed. Every screen is reachable with no account |

The store listing full description already says "no ads, no trackers, no analytics,
no accounts" (`fastlane/metadata/android/en-US/full_description.txt`), so all three
surfaces, listing, policy and Data safety form, say the same thing.

---

## If a reviewer pushes back

The likely challenge is "this is a file viewer, so it must handle Files and docs".
The answer in one line: Gander reads files on the device and renders them on the
device, and it does not hold the `INTERNET` permission, so there is no mechanism by
which a file could be transmitted. Point at the permission list in the store listing
and at the source.

## Re-check this before each release

- **The version stamp at the top of this file.** It is the only line here with no
  automated check behind it, and it silently described 1.10's backup behaviour
  under a 1.9 heading for four days. Update it in the `vX.Y:` release commit,
  alongside the other three files that commit already touches.
- The permission list is still just the signature-level one. The Gradle
  `check<Variant>Permissions` task enforces this and fails the build otherwise.
- No dependency has been added that phones home.
- The privacy policy URL still resolves.
- The privacy policy still describes what the code actually stores.
