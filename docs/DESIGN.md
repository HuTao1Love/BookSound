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
Series view is a vertical list: first "Without series" (shelf of standalone books), then one card
per series with stacked-cover emblem, authors, book count, total length, finished count, overall
progress and a carousel ordered by book number (#1, #2…). Search, filters and sort apply to all
layouts; in Series view the sort decides which series comes first.

## Navigation

```
Onboarding (choose library folder)
Library ──► Book details ──► Player ──► Chapters / Speed / Sleep (sheets)
   │             └──► Edit book (import editor in edit mode) ──► Cover picker
   ├──► Import: pick files/folder ──► Review & edit ──► Cover picker
   │                                       └──► Imports (progress queue)
   └──► Settings ──► Removed books
```

## Adaptive layout (Galaxy Z Fold)

* Width < 600 dp (cover screen): single pane, full-screen player.
* Width ≥ 600 dp (inner screen): library + book details side by side (list-detail); two-pane
  player (cover │ controls + chapter list); two-column import editor; series cards in 2 columns
  on wide windows.
* Tabletop posture (half folded): player shows the cover above the hinge, controls below.
* Fold/unfold is handled as a configuration change without recreating the activity; screen state
  lives in ViewModels and app-scoped stores, so nothing resets.
