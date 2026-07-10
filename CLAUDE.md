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
./gradlew run            # launch a dev RuneLite client with the plugin loaded
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

### Drops data layer (`loot/`, #41 · epic #39)

Drops live in the `com.bettermonsterexamine.loot` sub-package. The design is deliberately **simple
and correct-by-construction** (see `bucket-api-playground/PLUGIN_DESIGN.md`): exact per-kill odds
straight from Bucket, **deterministic** sections stated by Bucket flags — never inferred — and (once
the panel lands, on the stacked `#45` branch) price/alch/icon from the RuneLite client for free. No
HTML scraping, no shared-sub-table reconstruction, no odds-consistency heuristics, nothing to tune or
that can drift. This branch (`#41`) is **data only** — the view is `#45`.

**Data-source split.** Two Bucket buckets (same `api.php?action=bucket` endpoint as stats) + the
client:
- **`dropsline`** (drop list, quantity, compound rarity, RDT flag, variant) — **on demand per
  monster**, cached per page. `page_name` is the only queryable filter (the monster lives in the
  non-indexed `drop_json`), so filter by the emitting page and read the variant back out of
  `drop_json`'s `Dropped from` (`<Page>#<Variant>`). ~10× the size of stats; the API hard-caps
  queries at 5000 rows.
- **`item_id`** (item page name → numeric client id) — **bulk**, cached once. Bridges wiki item
  names to the client ids the price/alch/icon lookups key on, and fixes untradeables that
  `ItemManager.search` misses.

So **stats = bulk index, drops = on-demand detail, item_id = bulk bridge**; don't unify.

- **`DropTableService`** (singleton) — `request(pageName)` fetches that page's drops off-thread
  (client-thread/EDT safe) and caches the merged response per page under
  `.runelite/better-monster-examine/drops/`; the fetch runs **synchronously on the executor**,
  **paginating** with `offset` past the 5000-row cap. `tableFor(pageName)` reads the concurrent
  by-page index without blocking (null until loaded) and returns the page's `DropTable`s (one per
  variant). Concurrent requests coalesce; an update listener notifies when a page lands. Apostrophes
  in the page name are escaped by **doubling** (`Vorkath's` → `Vorkath''s`). `MAX_AGE = 7 days`.
- **`ItemIdService`** (singleton) — bulk `item_id` name→id map, cached under
  `.runelite/better-monster-examine/item-ids.json`, paginated + refreshed weekly. Mirrors
  `MonsterDataService`'s bulk cache/refresh; `idFor(name)` returns null until it lands.
- **`DropRow`** — a Gson DTO for one drop, mapped from the row's nested `drop_json` **string**. Adds
  `variant()` (the part after `#` in `Dropped from`), `rarityProbability()` (`Always`→1.0, `a/b`
  fraction tolerating a leading `~` and commas, unparseable→`-1.0` so it sorts last), and `section()`
  (the deterministic label). `item` falls back to the row's `item_name`; `pageNameSub`, the
  `rareDropTable` flag (`""`/null sentinel, like `MonsterData.default_version`) and the resolved
  client `itemId` are stamped on post-parse.
- **`DropTable`** — one variant's rows grouped into the fixed **section order 100% → Other → Rare
  drop table** (a row is `100%` when `Always`, `Rare drop table` when the flag is set, else `Other`;
  100% wins over the flag), each section sorted most-common first. `group(rows)` splits a page's rows
  into one table per variant in first-seen order. Pure, so the engine is unit-tested.

Variant handling follows the design: the view will **render one block per variant** (split on
`drop_json`'s `#variant`), which sidesteps the fragile stats-side `page_name#version_anchor` join.

### Plugin + UI

- **`BetterMonsterExaminePlugin`** — lifecycle and game integration. Adds the nav button when
  `enableSidePanel` is on; adds a right-click **"Stats"** menu entry anchored on each NPC's
  Examine entry (gated on both config flags). Resolves the clicked NPC **by id, falling back to
  name + matching in-game combat level to a variant** — so it covers variant spawn ids the
  dataset doesn't carry (e.g. Hellhounds across dungeons). Caches the player's combat and HP
  levels each `GameTick` so the panel can read them safely off-thread.
- **`BetterMonsterExaminePanel`** (`PluginPanel`) — search field + variant dropdown + the
  wiki-style infobox card (built by **`MonsterCard`**), all in one vertical scroll. All fields
  render synchronously from the cached dataset (no async patch-in). Colour-codes player-relevant
  values (combat level vs yours, negative flat armour green / positive red, max hits above your
  HP red).
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
  `showStatsMenuOption`, `statHighlighting`, and `statsRenderTarget` (`RenderTarget`:
  panel / overlay / both — where the right-click **"Stats"** action renders). The Stats menu
  entry shows when its action is actually available: the overlay target always works, the panel
  target needs `enableSidePanel`. A second Stats click on the same monster toggles the overlay
  off. The overlay updates on the **client thread** (it draws there); the side panel on the EDT.

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
The `loot/` layer adds `DropRowTest` (the `drop_json` parsing shapes) and `DropTableServiceTest`
(the two-stage row → nested-`drop_json` parse). `BetterMonsterExaminePluginTest` and `OverlayPreview`
are dev launchers, not assertions.
