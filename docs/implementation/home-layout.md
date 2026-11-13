# Home on a computer: layouts that fit the count

> Status: **built** ✅ (decided 2026-11-13, option A of three mockups; built the same day, with
> phones' speed dials changed to full pages only, see below). Feature:
> [Home on a computer](../features/ui.md#home--explore).

## Why

Home makes one request, and each section has exactly what YouTube Music's home page sends in
that answer; nothing is cut off. An anonymous Home on 2026-11-13: *Quick picks* 20 songs,
playlist rows of 3 to 6 (*Party music* 6, *Today's biggest hits* 5, *All hits* 3), *New
releases* 10 albums. More would take a request per section, so the layout fits these counts
instead of fixed pages.

What looks off on a wide screen now:

- **Speed dial** (`SpeedDial.svelte`): pages of 9 (3×3) at a fixed `sm:w-[30rem]`, left-aligned
  with empty space beside them; 20 tiles are pages of 9 · 9 · 2.
- **Quick picks** (`SongCarousel.svelte`): 4 rows, columns `sm:auto-cols-[24rem]`, so about 3
  columns a screen: 20 songs are 12 + 8.
- **Card rows** (`CardGrid.svelte` with `row`): cards `sm:auto-cols-[11rem]` whatever the
  count, so a row of 3 playlists fills a third of the width.

## What was built

- `SpeedDial.svelte`: phones (`screen.wide` false, `screen.svelte.ts`: `min-width: 640px`):
  pages of 9; suggestions show only full pages (≥18 tiles: 18, ≥9: 9, fewer: all on one page),
  unless *Settings → Home → Speed dials on a phone* is *Everything* (`HomePrefs.fullDialPages`,
  default on; `all` defaults to its opposite); `all` (the *Pinned* section) shows every pin.
  `extras` (from `HomeView.dialExtras`: Liked music when signed in, key `playlistLM` like its
  pin; *Random song* from Home's songs not in the dial) fill a gap: a full page counts them
  (17 tiles + 1 = 18), on a computer the last row (20 at 7 columns + 1 = 21), with everything
  shown the last row of 3. They only show when there are enough of them for the whole gap. Computers: one grid, as many columns as fit at
  120 px, then fewer so the rows are even, `1fr` each; fewer tiles than fit: at most 176 px.
- `SongCarousel.svelte`: on a computer, no scrolling when the songs fit in at most 5 rows of
  columns ≥ 256 px (20 at 1440 px: 4 columns of 5; at 1024 px, 2 fit, so they scroll); otherwise
  4 rows that scroll, columns sized so whole columns fill a screen. Phones as before.
- `CardGrid.svelte` (`row`): on a computer, cards that fit at 11rem grow to fill (each at most
  16rem); otherwise whole cards a screen (about 11rem each).
- Both tell `HomeView` whether they scroll (`onScrolls`); ‹ › only show then.
- Checked in the browser at 1440×900, 1024×768 and 375×812 with an anonymous Home that day:
  quick picks 20, playlist rows 5 to 10, new releases 10.

## The plan (option A)

Wide screens (`sm:` and up, the desktop layout with the left side); phones keep what they have,
except that speed dial pages are split evenly.

1. **Speed dial**: one grid, no pages, as many columns as fit (`grid-cols-[repeat(auto-fill,minmax(…,1fr))]`,
   tiles about 8–9rem) and as many rows as the tiles need: 20 tiles at desktop width are 2
   rows of 10, 6 playlists one row of 6. Nothing is left on a page of its own. The name over
   the cover and the hover zoom stay. `PageDots` only on phones.
   - **Phones**: still pages of 3 columns swiped sideways, but evened out: `pages = ceil(n / 9)`,
     `per page = ceil(n / pages)`, each page `ceil(per page / 3)` rows (20 → 2 pages of 10: 3
     full rows and one tile on a 4th, or 7 + 7 + 6 with 3 pages; pick whichever keeps the
     tiles the same size and looks calmer on a real phone).
2. **Quick picks** (any song section shown as carousel): always 4 rows (fewer when there are
   fewer songs), and on wide screens the columns are `ceil(n / 4)` stretched to the full
   width (`grid-template-columns: repeat(cols, minmax(0, 1fr))`, no sideways scroll): 20
   songs are 4 × 5. When a column would get narrower than about 16rem (a narrow window, or
   many songs), fall back to today's sideways scroll with columns of that width.
3. **Card rows with few items**: when the cards of a row fit the width at their normal size,
   they grow to fill it (`grid-template-columns: repeat(n, minmax(0, 1fr))`, capped so a row of
   2 doesn't get huge, e.g. at most ~16rem each); more than fit: today's sideways scroll.
   The ‹ › buttons in the section header (`slide` in `HomeView.svelte`) only show when the row
   actually scrolls.
4. **Pinned as tiles** (the non-dial layout, `grid-cols-2 sm:grid-cols-3 lg:grid-cols-4` in
   `HomeView.svelte`) can stay; check it next to the new speed dial.

## Where

- `frontend/src/lib/components/SpeedDial.svelte`: the grid and the phone pages.
- `frontend/src/lib/components/SongCarousel.svelte`: quick picks' columns.
- `frontend/src/lib/components/CardGrid.svelte`: the `row` variant.
- `frontend/src/lib/components/HomeView.svelte`: which layout a section gets, the ‹ › buttons.
- Layout choices per section are `Layout` in `frontend/src/lib/home.svelte.ts` (`carousel`,
  `list`, `dial`); no new setting is needed.

## Checking it

- Browser at 1440×900 and 1024×768 (the desktop app's sizes), and a phone (375×812): a
  speed dial of 20, of 6 and of 3; quick picks of 20 and of 7; card rows of 3, 6 and 10.
- The Chromecast party screen doesn't show Home, so no Chrome 90 run (CLAUDE.md), unless
  shared components or `app.css` change.
- `docs/features/ui.md` (Home): drop *planned* and describe what was built.
