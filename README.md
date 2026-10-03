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

Publishing a GitHub release (Releases → Draft a new release → tag like `v1.2` → Publish) runs
`.github/workflows/release.yml`: it tests `:core`, builds a signed release APK and attaches it
to the release as `BookSound-1.2.apk`. It can also be run by hand from the Actions tab.

One-time setup — a release key (keep the file and passwords safe: updates must be signed with
the same key, otherwise the app has to be uninstalled first):

```
keytool -genkeypair -v -keystore booksound.jks -alias booksound -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 booksound.jks          # Windows PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("booksound.jks"))
```

Add repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (the
base64 output), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`booksound`), `KEY_PASSWORD`, and optionally
`GOOGLE_BOOKS_API_KEY`. To build the same signed APK locally, put into `local.properties`:

```
signing.storeFile=C:/path/to/booksound.jks
signing.storePassword=...
signing.keyAlias=booksound
signing.keyPassword=...
```

Design notes: [docs/DESIGN.md](docs/DESIGN.md).
