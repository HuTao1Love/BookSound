# BookSound

Local-first Android audiobook player. Imports M4B/M4A files or folders of MP3s, lets you review
and edit the metadata, and stores every book as a single M4B (chapters, cover, tags) in a folder
you choose.

## Modules

| Module | Contents |
|---|---|
| `:core` | Pure Kotlin/JVM, no Android: MP4/ID3/MP3 parsing, M4B tag & chapter writer, metadata guessing, file naming and library layout, search/sort/series grouping, torrent content validation and review merging, sync contracts and conflict resolution. Reusable by a future server. |
| `:app` | Android app: Room, DataStore, SAF storage, import pipeline (Media3 Transformer), torrent downloads (libtorrent4j), playback (Media3 ExoPlayer + MediaSessionService), Compose UI. |

## Key behaviour

* **Storage** — the library is a user-picked folder (Storage Access Framework), laid out as
  `Author/Series/NN - Title.m4b`. File names are sanitized for ext4/FAT/exFAT/NTFS, keep Unicode,
  never overwrite without confirmation, and fall back deterministically when metadata is missing.
* **Import** — single AAC M4B: copied without re-encoding; compatible AAC parts: joined without
  re-encoding; anything else: transcoded to AAC. Output is written as a hidden
  `.booksound-partial-*` file, verified (parsed back + SHA-256), then atomically renamed; only then
  is the book added to the database. Cancellation, failures and crashes clean up temporary files
  (crash recovery via an import journal at startup). Free space is checked up front.
* **Torrents** — add a magnet link, a link to a .torrent file or a .torrent file (also opened from
  other apps); several links can be pasted at once, one per line. Before anything is downloaded the file list must be one audiobook: MP3 files with
  optional cover images, or a single M4B; harmless extras (.nfo, .txt, .cue, playlists) are skipped,
  anything else rejects the torrent. The review editor opens right away and the user edits the
  details while only the audio and cover files download (libtorrent via libtorrent4j, no seeding).
  When the download is done and the details are confirmed, the files are checked again (every
  file must parse, match its format and have a plausible size for its length) and converted with
  strict validation (decoded length must match the sources), so a damaged book never reaches the
  library. Torrents, their review and libtorrent resume data are persisted: closing the app, a
  crash or losing the connection only pauses the work, which continues on next start. Downloads
  run as a user-initiated data transfer job (Android 14+), which — unlike a `dataSync` foreground
  service — has no 6-hour daily limit on Android 15+; Android 13 (or a refused job) falls back to
  the foreground service. Downloads are deleted once the book is in the library.
* **Identity** — each book has a UUID embedded in the M4B (`----:com.zyagodin.booksound:BOOK_ID`),
  independent of its path; folder rescans re-link moved files and adopt files copied in manually.
* **Playback** — background playback with MediaSession (notification, lock screen, Bluetooth),
  audio focus, pause on headphone disconnect, chapter navigation, per-book speed, sleep timer
  (minutes, end of chapter or book, fade-out, shake to start over), smart rewind (configurable
  amount and pause length, also after the app was closed), voice equalizer presets for the
  narrator's timbre (remembered per book, DSP in `:core`), position saved continuously.
* **Sync-ready** — records carry revision/updatedAt/device/dirty stamps; `SyncBackend`,
  `SyncEngine` and `PlaybackConflictResolver` live in `:core`. No backend is required or
  implemented yet (`NoBackend`).

## Build

```
./gradlew :core:test :app:assembleDebug
```

## Releases (APK on GitHub)

`.github/workflows/release.yml` tests `:core`, builds a signed release APK and publishes it:

* **Every push to `main`** (each merged pull request) becomes a release tagged
  `<appVersion>.<run number>`, e.g. `1.0.57`, with `BookSound-1.0.57.apk` attached.
  `appVersion` lives in `gradle.properties`; raise it for a new major/minor version.
* **A release published by hand** gets its APK attached (tag `v1.2` → `BookSound-1.2.apk`).
* **Actions → Release APK → Run workflow** keeps the APK under the run's Artifacts.

The run number is also the APK's `versionCode`, so each build installs over the previous one.

### One-time setup

**1. Create a release key.** `keytool` comes with the JDK; on Windows it is inside Android
Studio. In PowerShell:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore booksound.jks -alias booksound -keyalg RSA -keysize 4096 -validity 10000
```

(macOS/Linux: `keytool -genkeypair -v -keystore booksound.jks -alias booksound -keyalg RSA -keysize 4096 -validity 10000`.)
It asks for a password and a few name fields (any values). Keep `booksound.jks` and the
password safe and out of the repository: every update must be signed with this same key,
otherwise Android makes you uninstall the app (and lose its library data) first.

**2. Copy the key as text.** PowerShell (puts it into the clipboard):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("booksound.jks")) | Set-Clipboard
```

(macOS/Linux: `base64 -w0 booksound.jks`.)

**3. Add repository secrets** — GitHub → the repository → Settings → Secrets and variables →
Actions → New repository secret:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | the text from step 2 |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `booksound` |
| `KEY_PASSWORD` | the key password (the same as the keystore password unless you chose another) |
| `GOOGLE_BOOKS_API_KEY` | optional, for Google Books cover search |

**4. (Optional) the same signed APK on your PC** — add to `local.properties`, then run
`gradlew :app:assembleRelease` (output: `app/build/outputs/apk/release/app-release.apk`):

```
signing.storeFile=C:/path/to/booksound.jks
signing.storePassword=...
signing.keyAlias=booksound
signing.keyPassword=...
```

### Publishing a version

GitHub → Releases → Draft a new release → Choose a tag → type e.g. `v1.2` → Create new tag →
Publish release. A few minutes later `BookSound-1.2.apk` appears under the release's Assets;
open that link on the phone to install or update.

Design notes: [docs/DESIGN.md](docs/DESIGN.md).
