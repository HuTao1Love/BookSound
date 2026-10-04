# BookSound — Design

## Visual direction: "Midnight"

Dark-first, modern and content-focused. The book cover is the hero; the UI around it is quiet
graphite with one warm accent. Light theme is available in Settings (Appearance).

### Color

| Token | Dark (default) | Light | Use |
|---|---|---|---|
| background / surface | `#0A0B0F` | `#F6F6F9` | screens |
| surfaceContainer (low → highest) | `#111217` → `#282B36` | `#F0F1F5` → `#DDDEE6` | cards, sheets, mini player |
| onSurface / onSurfaceVariant | `#F2F2F5` / `#A0A3B1` | `#111217` / `#5B5F6E` | text |
| primary | `#FF9548` amber | `#E5641E` | accents, active states |
| accent gradient | `#FF9548 → #FF5E62` | same | play button, primary buttons, progress, seek bar |
| secondary | `#6EE7C8` mint | `#0E8A6E` | finished state, series progress |
| tertiary | `#AFA2FF` violet | `#5B4BD6` | sleep timer |

**AMOLED black** (Settings → Appearance) swaps the graphite surfaces of the dark theme for pure
black (`#000000` background, containers `#07080A` → `#1E2025`); accents stay the same.

Player, book details and series cards use a heavily blurred copy of the cover as a backdrop,
fading into the background.

### Typography

Manrope (variable, Cyrillic) everywhere: ExtraBold, tightly tracked headlines; Medium body text.

### Shape, spacing, icons

4-pt grid (4/8/12/16/24/32/48). Radii: 8 chips, 16 covers, 24 cards / mini player, 28 large
covers, 32 sheets; pill buttons. Material Symbols Rounded. Touch targets ≥ 48 dp; play button
80–88 dp with a soft glow.

## Library

Three layouts (toggle in the top bar): **Series** (default), Grid, List.
The top bar also holds a downloads button: its badge counts torrents in progress and turns to
the accent colour when one waits for its details to be reviewed.
Series view is a vertical list: first "Without series" (shelf of standalone books), then one card
per series with stacked-cover emblem, authors, book count, total length, finished count, overall
progress and a carousel ordered by book number (#1, #2…). Search, filters and sort apply to all
layouts; in Series view the sort decides which series comes first. Tapping a series card opens the
series screen: emblem, totals, progress, a play button for the next unfinished book, and every
book of the series as a list ordered by number (regardless of the library's search and filter).

## Navigation

```
Onboarding (choose library folder)
Library ──► Book details ──► Player ──► Chapters / Speed / Sleep (sheets)
   │             └──► Edit book (import editor in edit mode) ──► Cover picker
   ├──► Series (tap a series card) ──► Book details
   ├──► Import: pick files/folder ──► Review & edit ──► Cover picker
   │                                       └──► Imports (progress queue)
   ├──► Import: torrent (link or file) ──► Review & edit while downloading ──► Imports (Downloads)
   └──► Settings ──► Removed books
```

## Adaptive layout (Galaxy Z Fold)

* Width < 600 dp (cover screen): single pane, full-screen player.
* Width ≥ 600 dp (inner screen): library + book details side by side (list-detail; on two panes
  the library takes about half the width, 360–520 dp, and the mini player docks under it); two-pane
  player (cover + controls │ chapter list, or cover │ controls without chapters); two-column import editor; series cards in 2 columns
  on wide windows.
* Tabletop posture (half folded): player shows the cover above the hinge, controls below.
* Fold/unfold is handled as a configuration change without recreating the activity; screen state
  lives in ViewModels and app-scoped stores, so nothing resets.

## Torrent imports

```
add link/file ─► (magnet: fetch file list) ─► validate file list ─► DOWNLOADING ──► DOWNLOADED
                        │                          │                     │  user confirms review
                        └─ no peers: FAILED        └─ not one book:      ▼
                                                      rejected      VERIFYING ─► CONVERTING ─► COMPLETED
                                                                         │            │
                                                                         └── FAILED ◄─┘ (retry)
```

* Each torrent is a persisted `TorrentRecord` (`noBackupFilesDir/torrents`), with the .torrent
  metadata, libtorrent resume data and the chosen cover next to it. A reconcile loop drives every
  record from its stored phase, so app restarts, crashes and network loss resume where they were.
* A torrent may hold several books: sub-folders below the folder with all audio ("Series/Book 1",
  "Series/Book 2") or several M4B files. Folders named like discs or parts ("CD1", "Disc 2",
  "Часть 3", "02") still make one book. Each book gets its own record (title from its folder, the
  torrent's title as series); the records share a `group` key, the .torrent file, the download
  folder and one engine download, and are then reviewed, verified and converted one by one. The
  downloaded files are deleted once no book of the torrent needs them.
* The editor of a torrent is an import session with a deterministic id (`torrent-<id>`), rebuilt
  from the record if the process was killed while it was open. Edits are saved as they are typed.
  Fields the user left as suggested may be filled from the downloaded files' tags; edited ones win.
* Conversion waits while the editor is open and starts automatically once the download is done
  and the details were confirmed. The library never sees a book that failed a check.
* The Imports screen shows a "Downloads" section (progress, speed, peers, pause/resume, review,
  retry, remove); the library shows a banner while torrents are active or need a review.
