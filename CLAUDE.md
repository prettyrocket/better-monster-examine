# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A RuneLite side-panel plugin ("Better Monster Examine") that searches any OSRS monster and
renders its full, wiki-style combat stats. Distributed via the RuneLite plugin hub. Began as a
fork of Koitere/monster-stats (BSD 2-Clause; notice retained in `LICENSE`); data and UI layers
have since been rewritten.

## Repo, project & issues

- **GitHub repo:** `prettyrocket/better-monster-examine` — <https://github.com/prettyrocket/better-monster-examine>
  (default branch `main`). The `origin` remote authenticates as `prettyrocket`.
- **Issue tracker / project board:** GitHub Project #1 "Better Monster Examine" (private) —
  <https://github.com/users/prettyrocket/projects/1>. All feature work and investigations live here.
- Don't go looking these up — use the `gh` CLI:

```
gh issue list --state open                 # open issues
gh issue view <n>                           # one issue
gh project item-list 1 --owner prettyrocket # the board, incl. status lane / Priority / Size
gh pr list                                  # open PRs
```

## Releases

A GitHub **Release** (publish a release, or run the `release.yml` workflow manually via
`workflow_dispatch`) triggers the plugin-hub update: it opens/updates a PR against
`runelite/plugin-hub` (pushed via the `prettyrocket/plugin-hub` fork, needs the
`PLUGIN_HUB_TOKEN` secret) that pins `plugins/better-monster-examine` to the released commit.
**No version bump** — the hub tracks the pinned commit, not a version string.

## Commands

```
./gradlew run            # launch a dev RuneLite client with the plugin loaded (uses your real ~/.runelite profile)
./gradlew runClean       # same, but a clean throwaway profile (build/dev-home) — no side-loaded hub plugins; lighter client
./gradlew build          # compile, run checkstyle, run tests
./gradlew test           # tests only
./gradlew checkstyleMain checkstyleTest   # lint only
./gradlew previewOverlay # dev-only: render the in-game overlay states to PNGs in previews/ (non-headless)
```

Run a single test class/method (JUnit 4):

```
./gradlew test --tests com.bettermonsterexamine.MonsterDataServiceTest
./gradlew test --tests 'com.bettermonsterexamine.MonsterDataServiceTest.matchNames*'
```

- `run` and `previewOverlay` execute via test-classpath `main()` entrypoints
  (`BetterMonsterExaminePluginTest`, `OverlayPreview`) — the plugin itself has no `main`.
- Targets Java 11. CI (`.github/workflows/build.yml`) runs `./gradlew build` on Temurin 11.

## Linting — strict, will fail the build

Checkstyle runs as part of `build` with `maxWarnings = 0` (config: `checkstyle.xml`,
suppressions: `suppressions.xml`). **Any** style violation fails CI. Notably:

- **Indent with tabs, not spaces** (the whole codebase uses tabs).
- Imports must be ordered and unused imports removed.
- Standard RuneLite-style braces/whitespace rules apply.

When editing, match the surrounding tab indentation exactly or the build breaks.

## Architecture

Mostly one flat package `com.bettermonsterexamine`, plus a `com.bettermonsterexamine.loot`
sub-package for the drops/loot module (the first sub-package; the existing flat classes were
**not** reorganised — a stats/shared split can come later). Both layers read the OSRS Wiki
**Bucket API**: stats bulk-load the whole bestiary once (cached, offline-first); drops fetch
**on demand per monster** and cache per page (the asymmetry is intentional — see the loot note
below). (Stats began as a two-source design — Weirdgloop `monsters.json` + per-page wikitext
scraping — cut over to Bucket in #26.)

### Data flow

1. **`MonsterDataService`** (singleton) — the dataset. Runs one uncapped query against the wiki's
   official **Bucket API** (`api.php?action=bucket`, the `infobox_monster` bucket; ~1.8 MB for the
   whole bestiary), caches it under `.runelite/better-monster-examine/bucket-monsters.json`, and
   refreshes weekly (`MAX_AGE = 7 days`). Builds two indexes published atomically: **by NPC id**
   (each Bucket row carries a repeated `id` array) and **by lower-case base name → variant list**.
   A single name (e.g. Vorkath) can have several `MonsterData` variants; each is assigned a unique
   display `version` from its `version_anchor`, disambiguated with the combat level when blank or
   colliding, and `default_version` drives the default-variant pick. Accessors return empty until
   the async load lands. `matchNames` is a pure static helper kept unit-testable without the dataset.

2. **`MonsterData`** — a flat Gson DTO mapped to the Bucket `infobox_monster` schema, capturing
   **all** fields (Lombok `@Getter`), including ones not yet rendered (slayer level/XP/category,
   members, freeze resistance, image — tracked in #31). TEXT and `max_hit` values keep their raw
   Bucket form and are cleaned on access via **`WikiSanitizer`**.

3. **`WikiSanitizer`** (static, unit-tested) — cleans the few non-uniform shapes Bucket leaves in
   TEXT/`max_hit` fields: MediaWiki strip-markers, `<div class="plainlist">` + `*` bullet wrappers,
   `<br>` line breaks, and `[[wikilinks]]`. This is what makes the old `{{template}}` max-hit
   garbage render correctly (#24).

The view-model (`MonsterStats`, from #23) sits between the DTO and both renderers: it resolves
which fields to show and their colour roles, so the panel and overlay stay in sync. Three fields
the old wikitext layer showed have **no usable Bucket source and were dropped** (aggressive,
poison/venom resistance) — tracked in #30.

### Drops feature (`loot/`, data #41 · panel #45 · epic #39)

Drops live in the `com.bettermonsterexamine.loot` sub-package. The goal is the **wiki's own drop
tables** — 100% / Weapons and armour / Runes / Herbs / **Gem drop table** / **Rare drop table** /
**Catacombs of Kourend** / **Wilderness Slayer Cave** / Tertiary / … — grouped exactly as a player
sees them on the wiki. That editorial grouping lives **only in the rendered wiki page**, not in the
structured Bucket data: the `dropsline` bucket has no section field and can't even see the region
tables (a Catacombs/Wilderness monster emits none of those rows). So drops are sourced by **parsing
the monster's page**, not the bucket. (An earlier `dropsline`-based `tables` mode from
`bucket-api-playground/PLUGIN_DESIGN.md` was tried and dropped — it can't produce these sections.)
The **data layer** is `#41`; the **panel** (this branch) is `#45`, stacked on it.

- **`DropPageService`** (singleton) — the source. `request(pageName)` fetches the monster's rendered
  wiki page via the MediaWiki **`action=parse`** API off-thread (client-thread/EDT safe), caches the
  raw response per page under `.runelite/better-monster-examine/droppages/`, and publishes parsed
  rows into a concurrent by-page index; `tableFor(pageName)` reads it without blocking (null until
  loaded). On-demand **per monster**, refreshed weekly (`MAX_AGE = 7 days`) — the same cache pattern
  the drops fetch always used, just from the page instead of the bucket. Its static `parse(html)` is
  pure (unit-tested): it restricts to the Drops section (`id="Drops"` → next `<h2>`), merges
  `<h3>/<h4>` headings and drop-table rows by document position so each row inherits the heading
  above it, and pulls `[item · quantity · rarity]` from the row's `item-col` / quantity / `table-bg`
  cells. Concurrent requests coalesce; an update listener notifies when a page lands.
- **`ItemIdService`** (singleton) — bulk OSRS Wiki Bucket `item_id` name→id map (the one Bucket use
  that remains), cached under `.runelite/better-monster-examine/item-ids.json`, paginated + refreshed
  weekly. Bridges the parsed item **name** to the client **id** so the RuneLite client can supply
  price / high-alch / **icon** for free; also covers untradeables `ItemManager.search` misses.
  `idFor(name)` returns null until it lands.
- **`DropRow`** — a plain DTO for one parsed row: item, quantity + rarity (as the wiki renders them),
  and the **section** heading it sits under. Price/alch/icon are not stored — they come from the
  client by id at render time.
- **`DropTable`** — a monster's rows grouped into sections **in wiki page order** (`of(rows)`,
  first-seen), preserving row order within each section. Pure, so it's unit-tested.

- **`DropsCard`** (`JPanel`, the Drops-tab body) — renders the wiki's sections **in page order** as a
  clean list: one row per drop, two lines — **icon** + name (with the **quantity right-aligned**) on
  top, the **rarity/odds right-aligned** below, **colour-coded by rarity tier** (common grey →
  uncommon → rare → ultra-rare, via `DropFormat.tierOf`; the palette follows `statHighlighting` and
  has a colour-blind-safe Okabe-Ito set). Rarity handles the wiki's compound cells — multi-roll
  `N × 1/M` and a `;`-separated combined per-kill rate (`DropFormat.effective`). Each row is
  **clickable** (opens the item's wiki page) and its **hover tooltip** carries the **GE / High Alch**,
  with the **larger of the two highlighted** (colour-blind-aware). Item id resolves on the client
  thread:
  `ItemIdService` first, then a small hand map for items the bucket returns `"N/A"` for (clue scrolls),
  then `ItemManager.search` for tradeables the bucket misses (e.g. dose potions). Icon/price come from
  the client with zero network — built blank, filled via a **single `ClientThread` hop** that reads
  `ItemManager`/`ItemComposition` by id (`getImage` returns an `AsyncBufferedImage` that repaints on
  load); **noted** drops render the item's noted graphic via `getLinkedNoteId()`.
- **`DropFormat`** — pure display shaping (rarity, quantity, compact `M`/`B` coin values, the
  `GE · Alch` tooltip line), no Swing, unit-tested like `StatFormat`. Displayed numbers drop thousands
  commas and normalise en/em dashes to a plain hyphen (the RuneScape font can't render `–`/`—`).

Item icon / GE price / High Alch come from the **RuneLite client by item id** (zero network); only the
*drop list + sections* come from the page parse.

### Plugin + UI

- **`BetterMonsterExaminePlugin`** — lifecycle and game integration. Adds the nav button when
  `enableSidePanel` is on; adds a right-click **"Stats"** menu entry anchored on each NPC's
  Examine entry (gated on both config flags). Resolves the clicked NPC **by id, falling back to
  name + matching in-game combat level to a variant** — so it covers variant spawn ids the
  dataset doesn't carry (e.g. Hellhounds across dungeons). Caches the player's combat and HP
  levels each `GameTick` so the panel can read them safely off-thread.
- **`BetterMonsterExaminePanel`** (`PluginPanel`) — search field over a card area: a shared
  **`MonsterHeader`** (name, favourite star, combat level, examine, variant selector, Wiki/DPS
  links) sits above a `MaterialTabGroup` **`Stats | Drops`** tab strip, whose body swaps between the
  stats **`MonsterCard`** and the **`DropsCard`** — so the selected monster + variant stay put while
  you toggle tabs. Exactly one of four sibling regions shows at a time (live results, the card area,
  a Recent/Favorites list, or the empty-state hint). Stats render synchronously from the cached
  dataset; colour-codes player-relevant values (combat level vs yours, negative flat armour green /
  positive red, max hits above your HP red). Selecting/switching a monster warms its drops
  (`DropPageService.request`) and re-renders the Drops tab when the page — or the bulk item-id map —
  lands async. `openMonster(name, version, drops)` is the entry point for the right-click menu: it
  selects the monster and opens straight to the Stats or Drops tab.
- **`MonsterHeader`** — the monster-identity header shared by both tabs (extracted from `MonsterCard`
  so it stays put across the tab swap); surfaces favouriting and variant switching as callbacks. The
  variant dropdown is **hidden on the Drops tab** (drops show every variant regardless, so it doesn't
  apply).
- **`MonsterCard`** — the stats **body only** now (attribute / combat / max-hit / stat / immunity /
  slayer blocks); the header moved to `MonsterHeader`.
- **`MonsterIcons`** (singleton) — loads the stat/attack/skill icons bundled in `resources/`.
- **`MonsterCardOverlay`** (`Overlay`) — the in-game overlay option, modelled on the Monster
  Examine spell: a compact, tabbed box drawn directly with `Graphics2D` (not a snapshot of the
  Swing card) in the game viewport. Four **clickable** tabs — Combat / Aggressive / Defensive /
  Info — partition the stats. The plugin pushes the selected `MonsterData` in via `setMonster`
  (every field is present synchronously). It reads the highlight palette live, so a config
  change applies immediately. Tab clicks are routed from a `MouseManager` listener in the plugin:
  `tabAt` hit-tests a canvas point against the tab strip (using the overlay's renderer-maintained
  bounds) and `setActiveTab` switches tabs, consuming the click. Content/semantics come from the
  shared **`MonsterStats`** view-model and player-relevant colours from **`StatColors`** (both
  shared with the side panel); value formatting reuses the static helpers on `StatFormat`.
- **`StatColors`** — the shared `HighlightMode` palette (danger / good / combat-level gradient)
  used by both the side panel and the overlay, so both honour the same colour-blind settings.
- **`BetterMonsterExamineConfig`** — config group `bettermonsterexamine`: `enableSidePanel`,
  `menuOptions`, `statHighlighting`, and `statsRenderTarget` (`RenderTarget`: panel / overlay / both —
  where the right-click **"Stats"** action renders). **`menuOptions`** (`MenuOption`: Stats only /
  Drops only / Both / None) picks which right-click entries appear on a monster's Examine: **Stats**
  renders per `statsRenderTarget`, **Drops** opens the side panel to its Drops tab. Each entry shows
  only when it can act — Stats needs the overlay target or (panel target + `enableSidePanel`); Drops
  needs `enableSidePanel`. A second Stats click on the same monster toggles the overlay off. The
  overlay updates on the **client thread** (it draws there); the side panel on the EDT.

### Threading model (important)

RuneLite splits work across the **client thread** (game state, menus, lifecycle), the **EDT**
(Swing panel), and **background executors / OkHttp callbacks** (the one-off bulk data fetch).
Shared state crosses these boundaries: the plugin's nav button, panel, and cached player levels
are `volatile`; the data-service indexes are published atomically. When adding code, keep client
state reads on the client thread (`clientThread.invoke`), Swing updates on the EDT
(`SwingUtilities.invokeLater`), and never block either on network I/O.

## Tests

JUnit 4 under `src/test/java`. Pure-logic tests exercise the static helpers and the view-model:
`MonsterDataServiceTest` (name matching), `WikiSanitizerTest` (the Bucket field-cleaning shapes),
`MonsterStatsTest` (view-model semantics), `StatFormatTest`, `StatColorsTest`, `LookupHistoryTest`.
The `loot/` layer adds `DropPageServiceTest` (the rendered-page HTML parse: rows inherit their
`<h3>/<h4>` section, the Drops region stops at the next `<h2>`, entity/footnote cleaning),
`DropTableTest` (section grouping in wiki page order), `ItemIdServiceTest` (the `item_id` name→id
parse), `DropRowTest` (the `isAlways` helper) and `DropFormatTest` (the drops display shaping).
`BetterMonsterExaminePluginTest` and `OverlayPreview` are dev launchers, not assertions.
