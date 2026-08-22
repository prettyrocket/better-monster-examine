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

**Sync the fork first** — this is a required step, not housekeeping:

```
gh repo sync prettyrocket/plugin-hub    # before publishing a release
```

The release branch is built on **upstream** master and force-pushed to the fork, so every upstream
commit the fork lacks rides along on that push — and GitHub rejects the whole push when one of them
touches `.github/workflows/`, which upstream changes often. Keeping the fork level means only the
manifest commit is new, so the push is always clean. `release.yml` tries the fast-forward itself, but
that call needs the `workflow` scope `PLUGIN_HUB_TOKEN` doesn't have (the fast-forward is itself a
workflow-file change, so the guard can't guard itself — #59). A local `gh` token with the scope can do
it, which is why the manual step works. Skip it and the release fails after tagging, with the fix
printed in the log.

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
   A name group can also span **pages** — an infobox may set a name that isn't its page title, so a
   boss article and its quest fight both emit rows named "Shellbane gryphon" with a blank anchor and
   the same combat level, leaving nothing to tell them apart. So `page_name` is selected too: a
   blank-anchor row from a foreign page is labelled from that page's qualifier ("Troubled Tortugans")
   rather than a bare `#N`, and `defaultVariant` sets foreign rows aside so a bare name means the
   monster's **own article** (#60). Groups from a single page are untouched. `assignVersions` and
   `defaultVariant` are pure statics, unit-tested like `matchNames`.
   Before labelling, **`relevantVariants`** reduces each name to the variants a player can act on:
   the wiki carries a row per **sprite**, so ~25% of the bestiary differs in nothing rendered
   (Guard 124→26, Crystal impling 17→1, Hill Giant 14→2). Those collapse by `MonsterData.statKey()`
   and the survivor **absorbs the others' spawn ids**, so right-click still resolves by id (#62).
   `(historical)` rows are dropped as removed content, but only when a live sibling remains — a name
   that is entirely historical (Barbarian woman) would otherwise vanish from search (#63). Note
   `Realm of Memories` is **live quest content** despite the name, and is kept.
   `variantForLevel` (the right-click fallback when a spawn id isn't in the dataset) requires an
   exact live combat-level match and ranks matches through `defaultVariant` rather than taking the
   first. The exact requirement prevents a cosmetic NPC sharing a monster's name (the Rock golem
   pet versus Rock Golem) from silently selecting the monster's default form; 240 name+level buckets
   still hold rows with genuinely different stats (Alchemical Hydra's four phases at 426), so the
   ranking avoids whichever row Bucket happened to order first.

2. **`MonsterData`** — a flat Gson DTO mapped to the Bucket `infobox_monster` schema, capturing
   **all** fields (Lombok `@Getter`), including ones not yet rendered (slayer level/XP/category,
   members, freeze resistance, image — tracked in #31). TEXT and `max_hit` values keep their raw
   Bucket form and are cleaned on access via **`WikiSanitizer`**.

3. **`WikiSanitizer`** (static, unit-tested) — cleans the few non-uniform shapes Bucket leaves in
   TEXT/`max_hit` fields: MediaWiki strip-markers, `<div class="plainlist">` + `*` bullet wrappers,
   `<br>` line breaks, and `[[wikilinks]]`. This is what makes the old `{{template}}` max-hit
   garbage render correctly (#24).

4. **`InfoboxLevels`** (static, unit-tested) — recovers the levels Bucket **structurally cannot
   carry**. The wiki's `Module:Infobox Monster` writes each level with `tonumber()`, so a level that
   isn't a plain integer yields `nil` and the field is **omitted from the row entirely** — there is
   no Bucket field to widen or clean. **Vardorvis is the only monster in the bestiary this costs**
   (his Strength and Defence are HP-scaling ranges, `|str1 = 270-<br />360`), and it's why they
   rendered as a dash. So the five level fields on `MonsterData` are **boxed** (`Integer`): null =
   "Bucket has no value", distinct from a real `0`. On load, `MonsterDataService` takes the rows with
   such a hole (~50 pages bestiary-wide), pulls their wikitext in **one batched `action=query`**,
   parses the infobox here, and re-indexes — so stats stay **offline-first and synchronously
   rendered**, cached beside the dataset (`level-ranges.json`) and refreshed with it. The rest
   are genuinely blank on the wiki and must keep rendering a dash; only a non-integer value is
   recovered. The wiki's own `{{efn}}` footnote rides along as the panel tooltip — a Defence that
   counts *down* (215→145) otherwise reads as a bug.

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
  wiki page via the MediaWiki **`action=parse`** API off-thread (client-thread/EDT safe, `redirects=1`
  so redirect page names like `Hill giant` → `Hill Giant` still resolve), caches the
  raw response per page under `.runelite/better-monster-examine/droppages/`, and publishes parsed
  rows into a concurrent by-page index; `tableFor(pageName)` reads it without blocking (null until
  loaded). On-demand **per monster**, refreshed weekly (`MAX_AGE = 7 days`) — the same cache pattern
  the drops fetch always used, just from the page instead of the bucket. Its static `parse(html)` is
  pure (unit-tested): it restricts to the Drops section (`id="Drops"` → next `<h2>`), merges
  headings and drop-table rows by document position so each row inherits the headings above it, and
  pulls `[item · quantity · rarity]` from the row's `item-col` / quantity / `table-bg` cells.
  Heading **depth is load-bearing**: an `<h3>` with `<h4>`s under it is a **group** (a location or
  combat level — Cyclops' Warriors' Guild top floor vs basement, Abyssal demon's Catacombs vs
  Wilderness Slayer Cave) and the `<h4>` is the section, while an `<h3>` with no `<h4>`s *is* the
  section; a non-generic `<h2>` ("Level 99 drops") is itself a group. Flattening the two levels
  merges like-named tables across locations — same names, different drops and rates — which is what
  made the basement-only Dragon defender read as a drop from every Cyclops. Concurrent requests
  coalesce; an update listener notifies when a page lands.
- **`ItemIdService`** (singleton) — bulk OSRS Wiki Bucket `item_id` name→id map (the one Bucket use
  that remains), cached under `.runelite/better-monster-examine/item-ids.json`, paginated + refreshed
  weekly. Bridges the parsed item **name** to the client **id** so the RuneLite client can supply
  price / high-alch / **icon** for free; also covers untradeables `ItemManager.search` misses.
  `idFor(name)` returns null until it lands.
- **`DropRow`** — a plain DTO for one parsed row: item, quantity + rarity (as the wiki renders them),
  and the two headings it sits under — the **section** and its optional **group** (`""` when the page
  doesn't split its drops). Price/alch/icon are not stored — they come from the client by id at
  render time.
- **`DropTable`** — a monster's rows grouped by **group → section**, both **in wiki page order**
  (`of(rows)`, first-seen), preserving row order within each section. Sections only merge *within* a
  group, so a Cyclops' two `100%`/`Herbs` tables stay distinct. Pure, so it's unit-tested.

- **`DropsCard`** (`JPanel`, the Drops-tab body) — renders the wiki's sections **in page order** as a
  clean list, each group under a **band** naming its location/level (so a table that belongs to one
  variant is never read as the monster's drops as a whole): one row per drop, two lines — **icon** +
  name (with the **quantity right-aligned**) on
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
  dataset doesn't carry (e.g. Hellhounds across dungeons). When the Examine summary is enabled,
  snapshots its named, colour-labelled compact lines on the native click and waits for the matching
  `NPC_EXAMINE` chat response before queueing them, so the vanilla text always appears first.
  Followers are rejected before resolution, and examines record in the existing Recent history when
  enabled. Caches the player's combat and HP levels each `GameTick` so the panel can read them safely
  off-thread.
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
  `enableSidePanel`, `statHighlighting`, and `statsRenderTarget` (`RenderTarget`: panel / overlay /
  both — where the right-click **"Stats"** action renders). The three things the plugin can attach to
  a monster's Examine are **three independent checkboxes**, not one enum: **`statsMenuEntry`** adds
  the right-click **Stats** entry (rendering per `statsRenderTarget`), **`dropsMenuEntry`** adds
  **Drops** (opening the side panel on its Drops tab), and **`examineSummaryEnabled`** appends a
  compact combat block after the game's own Examine text — with `examineSummaryDetail`
  (`ExamineSummaryMode`: Weaknesses only / All defences) controlling how much it shows. Each entry
  appears only when it can act — Stats needs the overlay target or (panel target +
  `enableSidePanel`); Drops needs `enableSidePanel` — while the summary is independent of all of it,
  so every menu entry can be off and the summary still works (its whole point).
  **`examineOpensStats`** is the fourth: a native Examine also renders the monster per
  `statsRenderTarget`, making the Stats entry redundant for players who'd rather not carry it. It's
  separate from the summary checkbox because a chat line and a panel opening are different enough
  that wanting one shouldn't force the other. The overlay updates on the **client thread** (it draws
  there); the side panel on the EDT. An **Integrations** section holds the cross-plugin links —
  currently `notEnoughRunesLink` (see below).

  Sections split by **trigger**: **Right-click menu** owns the two entries and `requireShift`;
  **Examine** owns the three settings that hang off the game's own Examine. `statsRenderTarget` is
  deliberately **sectionless**, rendering above both — the Stats entry and `examineOpensStats` both
  render through it, so filing it under either heading would misdescribe it.

  `openStats` takes a `toggleOverlayOff` flag rather than always toggling. A second **Stats click**
  on the same monster still closes the overlay, but a second **Examine** doesn't: Examine is a repeat
  action in a way a deliberate menu click isn't, and closing the card under the player mid-fight
  reads as a bug. `toggleOverlay` now defers to a plain `showOverlay` for the non-toggling half.
  `openStats` records the lookup through its own path, so the Examine handler records only when it
  *doesn't* run — otherwise a single Examine would land in Recent twice.

  The checkboxes replaced a `menuOptions` enum (`Stats only / Drops only / Both / None`) that had
  already shipped, so `migrateMenuOptions` in `startUp` reads the retired key once, sets the two
  booleans from it, and unsets it. Without that, everyone who had narrowed or disabled the entries
  would silently get both back on update, since the new booleans would just fall to their defaults.

### Cross-plugin links (`NotEnoughRunesLink`, #69 · inbound #70)

Plugin-hub plugins each load in their own `PluginHubClassLoader` **parented to the client loader**, so
any two hub plugins are **siblings and cannot see each other's classes**. That rules out the obvious
routes: `@PluginDependency` takes a `Class` literal, and a shared event type would be a different
`Class` object on each side, so `EventBus.post` (which dispatches on exact class identity) would never
deliver it. The only channel is the core **`PluginMessage`** event — namespace, name, and a
`Map<String, Object>` of **core types only** (no shared DTOs).

- **`NotEnoughRunesLink`** — the outbound half: posts `notenoughrunes`/`displayItemById` with an
  `Integer` `itemId`, which Not Enough Runes already subscribes to, plus `openUses = true` to land on
  its Uses tab (**agreed but not read on NER's side yet** — an unknown key is ignored, so it switches
  on with her release rather than needing both plugins to ship together). Presence is decided by matching
  the plugin class **by name** (`com.notenoughrunes.NotEnoughRunesPlugin`) and then
  **`isPluginActive`** — *not* `isPluginEnabled`, which only reads the "start on boot" flag, whereas
  event-bus registration happens in `startPlugin`. Resolved per call, so installing or enabling NER
  mid-session works without a restart. Posting when NER is absent is harmless (no subscribers), so the
  check only gates what the UI *offers*.
- **`MonsterLookupMessage`** — the inbound half: parses a `bettermonsterexamine`/`displayMonster`
  request (`name` + optional `level`, `npcId`, `tab`) into a value object, type-checking every read so a
  malformed message from another plugin is ignored rather than thrown on the event bus. Numbers are read
  as `Number`, unknown keys ignored, so the contract can grow without both sides shipping in step. Pure,
  so it's unit-tested. The plugin's `onPluginMessage` resolves `npcId` → `name` + `level` → `name` and is
  **ungated by config** — a switch we own but the sender can't read would leave a live, correct-looking
  button in *their* UI that silently does nothing. Always renders to the **side panel**, ignoring
  `statsRenderTarget` (the overlay is for in-game NPC context).
- **`BetterMonsterExaminePanel.openMonsterRequested`** — stricter than `openMonster`, which auto-selects
  the best fuzzy hit (`matchNames` floats an exact match to the top). That's right for a name read off
  the game and wrong for one that crossed a plugin boundary, where a near-miss would silently show the
  wrong monster. Only an exact name opens a card; anything else goes into the search field, whose
  document listener shows live results without selecting.
- **`DropsCard` click roles** — with the link on, primary click hands the item to NER and right-click
  opens the wiki; with it off, NER not running, or the item id unresolved, the wiki stays on the
  primary click. Decided **per click**, never baked in at render. The id is armed inside `fill()`,
  where it is already resolved on the client thread, rather than in `makeClickable` — which is why
  `PriceCell` carries a mutable `itemId` (EDT-only, so unsynchronised).

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
`InfoboxLevelsTest` (recovering a level Bucket dropped; blanks stay a dash),
`MonsterStatsTest` (view-model semantics), `ExamineSummaryTest` (compact combat strings),
`ExamineSummaryQueueTest` (native/injected ordering), `StatFormatTest`, `StatColorsTest`,
`LookupHistoryTest`.
The `loot/` layer adds `DropPageServiceTest` (the rendered-page HTML parse: rows inherit their
`<h3>/<h4>` section, the Drops region stops at the next `<h2>`, entity/footnote cleaning),
`DropTableTest` (group → section grouping in wiki page order; like-named sections in different groups
stay distinct), `ItemIdServiceTest` (the `item_id` name→id parse), `DropRowTest` (the `isAlways`
helper) and `DropFormatTest` (the drops display shaping).
`MonsterLookupMessageTest` covers the inbound cross-plugin contract — precedence, defaults, and above
all that a wrongly-typed or empty payload is ignored rather than thrown.
`BetterMonsterExaminePluginTest` and `OverlayPreview` are dev launchers, not assertions.
