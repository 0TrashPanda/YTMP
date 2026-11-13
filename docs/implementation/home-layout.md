# Home on a computer: layouts that fit the count

> Status: **planned** (decided 2026-11-13, option A of three mockups). Feature:
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

## What to build (option A)

Wide screens (`sm:` and up, the desktop layout with the left side); phones keep what they have,
except that speed dial pages are split evenly.

1. **Speed dial**: one grid, no pages, as many columns as fit (`grid-cols-[repeat(auto-fill,minmax(…,1fr))]`,
   tiles about 8–9rem) and as many rows as the tiles need: 20 tiles at desktop width are 2
   rows of 10, 6 playlists one row of 6. Nothing is left on a page of its own. The name over
   the cover and the hover zoom stay. `PageDots` only on phones.
   - **Phones**: still 3×3 pages swiped sideways, but evened out: `pages = ceil(n / 9)`, then
     `per page = ceil(n / pages)` (20 → 10 + 10 with a 4th row on short pages… or simpler:
     columns of 3, `ceil(per page / 3)` rows). Pick what keeps the tiles the same size.
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
