# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
It describes the code as it is. History belongs in git and on GitHub; it appears here only where a
past decision would otherwise get reopened.

## What this is

A RuneLite side-panel plugin ("Better Monster Examine") that searches any OSRS monster and
renders its full, wiki-style combat stats and drop tables. Distributed via the RuneLite plugin hub.
Began as a fork of Koitere/monster-stats (BSD 2-Clause; notice retained in `LICENSE`); data and UI
layers have since been rewritten.

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

- Don't put issue or PR numbers in code comments or javadoc. Say the reason in words.

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
manifest commit is new, so the push is always clean. `release.yml` can't do the sync itself: the
fast-forward is a workflow-file change, which needs the `workflow` scope that `PLUGIN_HUB_TOKEN`
doesn't have. A local `gh` token with the scope can do it. Skip it and the release fails after
tagging, with the fix printed in the log.

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

Three packages:
- `com.bettermonsterexamine`: stats, UI and plugin wiring, flat.
- `com.bettermonsterexamine.loot`: the Drops tab.
- `com.bettermonsterexamine.wiki`: the copyable wiki client.

Stats bulk-load the whole bestiary from the OSRS Wiki's **Bucket API** once, cached and
offline-first. Drops are fetched **on demand per monster** from the rendered page and cached per
page. The asymmetry is intentional; see the Drops section.

### The wiki client (`wiki/`)

Every wiki request goes through `com.bettermonsterexamine.wiki`, a folder meant to be **copied into
other projects**. It depends only on OkHttp, Gson and the JDK, so keep RuneLite, Lombok and the
plugin's own classes out of it.

- **`WikiClient`**: blocking GETs as JSON. Every request carries `maxage`/`smaxage`, so the wiki's
  Cloudflare serves one answer to every user who sends the same URL. They go straight after
  `action`/`format`, because Cloudflare matches the exact query string.
- **`WikiCache`**: one cached file with a maximum age. Use a fresh file as-is; use a stale file at
  once and refresh behind it; apply a download *before* writing it. A page-backed cache (drop
  pages, the Superior page) also stores the page's revision id in a `.rev` sidecar. When the file
  ages out, it checks the revision (about 1.5 KB) and re-downloads only an edited page. Template edits
  don't change a page's revision, so there is a full download every `WikiApi.FULL_REFETCH` (30 days)
  regardless. Don't use `touched` or template revisions as the change signal instead: a bot edits
  `Module:GEPrices/data.json` daily, so both fire on every page every day.
- **`BucketQuery`**: a Bucket `select`, fetched a page at a time. Bucket clamps a query to 5000
  rows **silently**, with no error or continuation, so only a full page means there's more.
- **`TitleResolver`**: 50-title batches, which page answered each title after normalisation and
  redirects, and current revision ids.
- **`WikitextTemplates`**: templates, named parameters and `{{efn}}` footnotes read out of raw
  wikitext by bracket matching, not regex.
- **`WikiApi`** is the one plugin-specific file: the OSRS Wiki URL, our User-Agent, the CDN age (one
  hour) and the backstop. The User-Agent includes the repo URL because a descriptive User-Agent
  with contact details is the one thing the wiki asks of API users.

Services run requests on the shared executor, so they go out one at a time, as MediaWiki's API
etiquette asks. Fetching only a page's Drops section (`section=N`) was measured and rejected: it
skips the parser cache, and a template-generated heading's `T-` index silently returns the wrong
section.

### Data flow

1. **`MonsterDataService`** (singleton) — the dataset. Pulls the whole `infobox_monster` bucket
   (about 3,260 rows; 3.1 MB decoded, 250 KB gzipped) through `BucketQuery`, caches it under
   `.runelite/better-monster-examine/bucket-monsters.json`, and refreshes weekly (`MAX_AGE = 7 days`).
   A cache missing the newest selected field counts as stale whatever its age. Builds two indexes,
   published atomically: **by NPC id** (each row carries a repeated `id` array) and **by lower-case
   base name → variant list**. Accessors return empty until the async load lands.
   - **Variants.** A name (e.g. Vorkath) can have several `MonsterData` variants. Each gets a unique
     display `version` from its `version_anchor`, disambiguated by combat level when blank or
     colliding, and `default_version` drives the default pick.
   - **A name group can span pages.** An infobox may set a name that isn't its page title, so a boss
     article and its quest fight both emit rows named "Shellbane gryphon" with a blank anchor and
     the same combat level. A blank-anchor row from a foreign page is labelled from that page's
     qualifier ("Troubled Tortugans"), and `defaultVariant` sets foreign rows aside so a bare name
     means the monster's **own article**.
   - **A name is not a page title.** "Cave goblin" is a disambiguation page and the monster lives
     at "Cave goblin (monster)". `wikiPage` resolves each name once at index time: its own article if
     any row comes from one, else the default form's page. Every row carries it as
     `MonsterData.getWikiPage()`, which the Drops tab and the Wiki link use. It is per name, not per
     variant, so the Drops tab stays independent of the variant dropdown it hides. Never build a
     wiki URL or page request from `getName()`.
   - **`relevantVariants`** reduces each name to the variants a player can act on. The wiki carries
     a row per **sprite**, so about 25% of the bestiary differs in nothing rendered (Guard 124→26,
     Crystal impling 17→1, Hill Giant 14→2). Those collapse by `MonsterData.statKey()`, and the
     survivor **absorbs the others' spawn ids**, so right-click still resolves by id. `(historical)`
     rows are dropped as removed content, but only when a live sibling remains; a wholly historical
     name (Barbarian woman) would otherwise vanish from search. `Realm of Memories` is **live quest
     content** despite the name, and is kept.
   - **`variantForLevel`** (the right-click fallback when a spawn id isn't in the dataset) requires
     an exact live combat-level match and ranks matches through `defaultVariant`. The exact match
     stops a cosmetic NPC sharing a monster's name (the Rock golem pet versus Rock Golem) from
     selecting the monster. 240 name+level buckets hold rows with genuinely different stats
     (Alchemical Hydra's four phases at 426), so the ranking matters.
   - `matchNames`, `assignVersions`, `defaultVariant`, `relevantVariants` and `wikiPage` are pure
     statics, unit-tested without the dataset.

2. **`MonsterData`** — a flat Gson DTO mapped to the `infobox_monster` schema, capturing **all**
   fields (Lombok `@Getter`), including ones not yet rendered (slayer level/XP/category, members,
   freeze resistance, image). Every string arrives already clean, because the Bucket parse runs
   through **`WikiSanitizer`**, so its getters return fields as stored.

3. **`WikiSanitizer`** (static, unit-tested) — turns the markup Bucket leaves in its strings into
   plain text, **once, at parse time**: `MonsterDataService` parses rows with
   `WikiSanitizer.bucketGson(gson)`, which registers deserializers for `String` and `List<String>`.
   Bucket stores fields **after templates expand**, so any template in an infobox arrives as rendered
   HTML (`{{sic}}` → a `<sup class="noprint">` note, a footnote → a strip-marker, a thin space →
   `&thinsp;`). Rather than chase each template, `text` applies generic rules: drop strip-markers and
   `noprint` notes whole, unwrap `[[wikilinks]]`, `<br>`/`<div>` → line break, strip every other tag
   and wikitext `'''`/`*`, decode entities. A list element that packs several values (max hit's
   plainlist, Vespula's `Ranged <br/> Typeless`) splits into one entry each. Only the Bucket parse
   uses it; the shared `gson` stays raw for the wikitext `InfoboxLevels` reads.

4. **`InfoboxLevels`** (static, unit-tested) — recovers the values Bucket **structurally cannot
   carry**. The wiki's `Module:Infobox Monster` writes each level with `tonumber()`, so a level that
   isn't a plain integer is **omitted from the row entirely**; there is no Bucket field to widen or
   clean. **Vardorvis is the only monster this costs**: his Strength and Defence are HP-scaling
   ranges (`|str1 = 270-<br />360`). So the five level fields on `MonsterData` are **boxed**
   (`Integer`): null means "Bucket has no value", distinct from a real `0`.
   - **Attack speed** has the same hole with a different symptom: the module passes the raw string
     to an INTEGER column, so `Varies` / `Random` / `N/A` arrive as **`0`** and `No` as **`-1`**. A
     speed `<= 0` therefore counts as a gap. The recovered word renders as written (Basilisk Knight's
     "Varies"); a placeholder (`N/A`/`None`/`No`, e.g. an impling that doesn't attack) stays a dash.
   - **The gap-fill.** `MonsterDataService` takes the rows with a hole (about 120 pages), fetches
     their wikitext in batches of 50, one batch at a time, parses the infobox here and re-indexes. It
     goes per **row**: it reads each row's own `page_name` and keys what it recovers by page + version
     anchor, since the levels belong to that row's infobox (Venenatis (PvM Arena)'s "Varies" speed is
     on its own page). It is cached beside the dataset (`infobox-gaps.json`) and refreshed with it,
     so stats stay **offline-first and synchronously rendered**. Most gap rows are genuinely blank on
     the wiki and keep rendering a dash.
   - The wiki's own `{{efn}}` footnote rides along as the panel tooltip; a Defence that counts
     *down* (215→145) otherwise reads as a bug.
   - **No source:** **Respawn time** is in every infobox but never written to Bucket, so it has no
     source short of parsing every monster page.

5. **`SuperiorService`** (singleton) — which superior slayer monster each monster spawns. No Bucket
   carries the pairing, so it parses the table on the wiki's *Superior slayer monster* page
   (wikitext, tracking `rowspan`: Cockatrice and Moonlight cockatrice share one Cockathrice cell),
   then resolves the linked titles through `TitleResolver`, because the table links some monsters by
   a redirect ("Rock slug" → the dataset's Rockslug). The resolved map is cached as `superiors.json`.
   The stats card's Slayer block shows it as a **Superior** row that opens the superior's stats.

The view-model **`MonsterStats`** sits between the DTO and both renderers: it resolves which fields
to show and their colour roles, so the panel and overlay stay in sync. Poison and venom resistance
come from `poison_resistance` / `venom_resistance` (`0` / `100` / `200` / venom-only `Poisons`),
shown in the immunities block as Immune / `200% resistance` / Converts to poison; 0 takes no row.
**Aggressive** comes from `is_aggressive`, a TEXT field holding the infobox's own wording, and sits
in Combat info (the overlay's Aggressive tab). It keeps the wording whole, conditions included
("Yes, unless wearing a Zamorak-affiliated item"), and is flagged danger when it starts with Yes.
`N/A` marks an NPC that never attacks (an impling), so it and a blank read as a dash, not as No.

### Drops feature (`loot/`)

The goal is the **wiki's own drop tables** — 100% / Weapons and armour / Runes / Herbs / **Gem
drop table** / **Rare drop table** / **Catacombs of Kourend** / **Wilderness Slayer Cave** /
Tertiary / … — grouped exactly as a player sees them on the wiki. That grouping lives **only in the
rendered page**. Don't source drops from the `dropsline` bucket: it has no section field and can't
see the region tables at all (a Catacombs/Wilderness monster emits none of those rows). A
`dropsline`-based design was tried and can't produce these sections.

- **`DropPageService`** (singleton) — the source. `request(pageName)` fetches the monster's
  rendered page via `action=parse` (`redirects=1`, so `Hill giant` → `Hill Giant` still resolves)
  off-thread, caches the raw response per page under `.runelite/better-monster-examine/droppages/`
  with its revision, and publishes parsed rows into a concurrent by-page index. `tableFor(pageName)`
  reads it without blocking (null until loaded). Refreshed weekly per page, with the revision check
  above. Concurrent requests coalesce; an update listener notifies when a page lands.
  Its static `parse(html)` is pure (unit-tested). It reads each `<h2>` region whose title contains
  "drop" (one generic "Drops", or several like "Level 99 drops" / "Drop table 2"), merges headings and
  table rows by document position so each row inherits the headings above it, and pulls `[item ·
  quantity · rarity]` from the row's `item-col` / quantity / `table-bg` cells.
  Heading **depth is load-bearing**: an `<h3>` with `<h4>`s under it is a **group** (a location or
  combat level, e.g. Cyclops' Warriors' Guild top floor vs basement, Abyssal demon's Catacombs vs
  Wilderness Slayer Cave) and the `<h4>` is the section; an `<h3>` with no `<h4>`s *is* the section;
  a non-generic `<h2>` is itself a group. Flattening the levels merges like-named tables across
  locations, which makes the basement-only Dragon defender read as a drop from every Cyclops.
- **`ItemIdService`** (singleton) — the bulk Bucket `item_id` name→id map (about 17,700 rows), cached
  under `.runelite/better-monster-examine/item-ids.json` and refreshed weekly. Bridges the parsed
  item **name** to the client **id**, so the RuneLite client supplies price / High Alch / **icon**,
  including untradeables `ItemManager.search` misses. `idFor(name)` returns null until it lands.
- **`DropRow`** — one parsed row: item, quantity + rarity (as the wiki renders them), and the two
  headings it sits under, the **section** and its optional **group** (`""` when the page doesn't
  split its drops). Price/alch/icon aren't stored; they come from the client by id at render time.
- **`DropTable`** — a monster's rows grouped by **group → section**, both **in wiki page order**,
  preserving row order within each section. Sections merge only *within* a group, so a Cyclops' two
  `100%`/`Herbs` tables stay distinct. Pure, so it's unit-tested.
- **`DropsCard`** (`JPanel`, the Drops-tab body) — renders the sections **in page order**, each
  group under a **band** naming its location/level, so a table that belongs to one variant is never
  read as the monster's drops as a whole. Bands and sections collapse. One row per drop, two lines:
  **icon** + name with the **quantity right-aligned** on top; the **rarity** below, **colour-coded by
  tier** (common grey → uncommon → rare → ultra-rare via `DropFormat.tierOf`, following
  `statHighlighting`, with a colour-blind-safe Okabe-Ito set), and the stack's **GE value** under the
  quantity (the `showDropValues` setting).
  - The GE value is coloured by **`ValueTiers`**, the way RuneLite's Ground Items plugin colours a
    dropped stack. It reads the player's own tier prices and colours from the `grounditems` config
    group by key, with RuneLite's defaults where unset. That's no code dependency, so it works with
    Ground Items disabled. A value colours when strictly above a tier's price, highest tier first; a
    tier priced 0 is off; a ranged quantity is judged by its midpoint. A change to those settings
    re-renders the card.
  - Rarity handles the wiki's compound cells: multi-roll `N × 1/M` and a `;`-separated combined
    per-kill rate (`DropFormat.effective`).
  - Each row is **clickable** (see the cross-plugin click roles) and its **tooltip** carries the
    **GE / High Alch**, with the **larger of the two highlighted** (colour-blind-aware).
  - Item id resolves on the client thread: `ItemIdService` first, then a small hand map for items
    the bucket returns `"N/A"` for (clue scrolls), then `ItemManager.search` for tradeables the bucket
    misses (e.g. dose potions). Icon and price come from the client with no network: built blank,
    filled via a **single `ClientThread` hop** that reads `ItemManager`/`ItemComposition` by id
    (`getImage` returns an `AsyncBufferedImage` that repaints on load). **Noted** drops render the
    noted graphic via `getLinkedNoteId()`.
- **`DropFormat`** — pure display shaping (rarity, quantity, compact `K`/`M`/`B` values, the
  `GE · Alch` tooltip line), no Swing, unit-tested. Displayed numbers drop thousands commas and
  normalise en/em dashes to a plain hyphen (the RuneScape font can't render `–`/`—`).

### Plugin + UI

- **`BetterMonsterExaminePlugin`** — lifecycle and game integration. Adds the nav button when
  `enableSidePanel` is on; adds right-click **Stats** / **Drops** entries anchored on each NPC's
  Examine entry. Resolves the clicked NPC **by id, falling back to name + matching in-game combat
  level to a variant**, so it covers variant spawn ids the dataset doesn't carry (e.g. Hellhounds
  across dungeons). When the Examine summary is enabled, it snapshots the compact lines on the
  native click and waits for the matching `NPC_EXAMINE` chat response before queueing them, so the
  vanilla text always appears first. Followers are rejected before resolution, and examines record
  in Recent history when enabled. Caches the player's combat, HP and Slayer levels each `GameTick`
  so the panel can read them safely off-thread.
- **`BetterMonsterExaminePanel`** (`PluginPanel`) — search field over a card area: a shared
  **`MonsterHeader`** (name, favourite star, combat level, examine, variant selector, Wiki/DPS
  links) above a `MaterialTabGroup` **`Stats | Drops`** strip, whose body swaps between
  **`MonsterCard`** and **`DropsCard`**, so the selected monster and variant stay put across tabs.
  Exactly one of four sibling regions shows at a time (live results, the card area, a
  Recent/Favorites list, or the empty-state hint). Stats render synchronously from the cached
  dataset and colour player-relevant values (combat level vs yours, negative flat armour green /
  positive red, max hits above your HP red). Selecting a monster warms its drops and re-renders the
  Drops tab when the page, or the item-id map, lands. `openMonster(name, version, drops)` is the
  right-click entry point: it selects the monster and opens straight to Stats or Drops.
- **`MonsterHeader`** — the monster-identity header shared by both tabs; surfaces favouriting and
  variant switching as callbacks. The variant dropdown is **hidden on the Drops tab**, since drops
  show every variant regardless.
- **`MonsterCard`** — the stats body: attribute / combat / max-hit / stat / immunity / slayer blocks.
- **`MonsterIcons`** (singleton) — loads the stat/attack/skill icons and the Slayer masters'
  chatheads (`resources/slayer/`, listed in `SLAYER_MASTERS`) bundled with the plugin.
- **`MonsterCardOverlay`** (`Overlay`) — the in-game overlay, modelled on the Monster Examine
  spell: a compact, tabbed box drawn directly with `Graphics2D` (not a snapshot of the Swing card).
  Four **clickable** tabs — Combat / Aggressive / Defensive / Info. The plugin pushes the selected
  `MonsterData` in via `setMonster`. It reads the highlight palette live, so a config change applies
  immediately. Tab clicks are routed from a `MouseManager` listener in the plugin: `tabAt` hit-tests
  a canvas point against the tab strip (using renderer-maintained bounds) and `setActiveTab` switches
  tabs, consuming the click. Content comes from **`MonsterStats`** and colours from **`StatColors`**,
  both shared with the panel; formatting reuses `StatFormat`.
- **`StatColors`** — the shared `HighlightMode` palette (danger / good / combat-level gradient), so
  the panel and overlay honour the same colour-blind settings.
- **`BetterMonsterExamineConfig`** — config group `bettermonsterexamine`.
  - `enableSidePanel`, `enableHistory`, `statHighlighting`, `showDropValues`, and
    `statsRenderTarget` (`RenderTarget`: panel / overlay / both — where Stats renders).
  - **`showDefenceRolls`** swaps both renderers' defence bonuses for `DefenceRolls.rolls` (the
    bonus moves to the panel tooltip). It falls back to bonuses wholesale when a monster lacks any
    roll input, so a card never mixes the two. The chat summary's **All defences** mode follows it
    and drops the Weakness line there: the styles it would name (`DefenceRolls.roles`) are coloured
    in place, with darker shades for the parchment chatbox.
  - What hangs off a monster's Examine is **independent checkboxes**, not one enum:
    **`statsMenuEntry`** (right-click **Stats**, per `statsRenderTarget`), **`dropsMenuEntry`**
    (right-click **Drops**, opening the panel's Drops tab), **`examineSummaryEnabled`** (a compact
    combat block after the game's Examine text, with `examineSummaryDetail` — `ExamineSummaryMode`:
    Weaknesses only / All defences), and **`examineOpensStats`** (a native Examine also renders the
    monster per `statsRenderTarget`). Each menu entry appears only when it can act: Stats needs the
    overlay target or (panel target + `enableSidePanel`); Drops needs `enableSidePanel`. The summary
    is independent of all of it, so it works with every menu entry off. `examineOpensStats` is
    separate from the summary because a chat line and a panel opening are different enough that
    wanting one shouldn't force the other.
  - Sections split by **trigger**: **Right-click menu** owns the two entries and `requireShift`;
    **Examine** owns the three settings that hang off the game's own Examine. `statsRenderTarget` is
    deliberately **sectionless**, above both, because the Stats entry and `examineOpensStats` both
    render through it. An **Integrations** section holds the cross-plugin links
    (`notEnoughRunesLink`).
  - `openStats` takes a `toggleOverlayOff` flag. A second **Stats click** on the same monster closes
    the overlay, but a second **Examine** doesn't: Examine is a repeat action, and closing the card
    mid-fight reads as a bug. `openStats` records the lookup itself, so the Examine handler records
    only when it *doesn't* run; otherwise one Examine would land in Recent twice.
  - The summary has no header line of its own. The monster's **name is written onto the game's own
    Examine line**, underlined: RuneLite hands the live `MessageNode` to the `ChatMessage`
    subscriber, so the row is rewritten in place (`setRuneLiteFormatMessage` + `refreshChat()`).
    `ExamineSummary.chatName` escapes it: Jagex tags can't recolour the row, newlines are flattened,
    and null leaves the game's line alone. `ExamineSummaryQueue` carries the name beside the block,
    because the click is where the monster is known and the chat response is where it's needed.
    Underline rather than colour, since the row already carries the game's own.
  - `migrateMenuOptions` converts the retired `menuOptions` enum key (`Stats only / Drops only /
    Both / None`) into the two booleans and unsets it, in `startUp` and on **`ProfileChanged`**
    (profiles carry their own config). Without it, players who had narrowed or disabled the entries
    would silently get both back. The new keys are constants on the config interface, shared with
    the `@ConfigItem` annotations, so a rename can't leave the migration writing a dead key.
  - The overlay updates on the **client thread** (it draws there); the side panel on the EDT.

### Cross-plugin links

Plugin-hub plugins each load in their own `PluginHubClassLoader` **parented to the client loader**, so
any two hub plugins are **siblings and cannot see each other's classes**. That rules out the obvious
routes: `@PluginDependency` takes a `Class` literal, and a shared event type would be a different
`Class` object on each side, so `EventBus.post` (which dispatches on exact class identity) would never
deliver it. The only channel is the core **`PluginMessage`** event — namespace, name, and a
`Map<String, Object>` of **core types only** (no shared DTOs).

- **`NotEnoughRunesLink`** — the outbound half: posts `notenoughrunes`/`displayItemById` with an
  `Integer` `itemId`, which Not Enough Runes subscribes to, plus `openUses = true` to land on its
  Uses tab. NER doesn't read `openUses` yet; an unknown key is ignored, so it takes effect when NER
  ships support, with no change here. Presence is decided by matching the plugin class **by name**
  (`com.notenoughrunes.NotEnoughRunesPlugin`) and then **`isPluginActive`** — *not*
  `isPluginEnabled`, which only reads the "start on boot" flag, whereas event-bus registration
  happens in `startPlugin`. Resolved per call, so installing or enabling NER mid-session works
  without a restart. Posting when NER is absent is harmless, so the check only gates what the UI
  *offers*.
- **`MonsterLookupMessage`** — the inbound half: parses a `bettermonsterexamine`/`displayMonster`
  request (`name` + optional `level`, `npcId`, `tab`) into a value object, type-checking every read so
  a malformed message from another plugin is ignored rather than thrown on the event bus. Numbers are
  read as `Number`, unknown keys ignored, so the contract can grow without both sides shipping in
  step. Pure and unit-tested. The plugin's `onPluginMessage` resolves `npcId` → `name` + `level` →
  `name` and is **ungated by config**: a switch we own but the sender can't read would leave a live,
  correct-looking button in *their* UI that silently does nothing. Always renders to the **side
  panel**, ignoring `statsRenderTarget` (the overlay is for in-game NPC context).
- **`BetterMonsterExaminePanel.openMonsterRequested`** — stricter than `openMonster`, which
  auto-selects the best fuzzy hit. That's right for a name read off the game and wrong for one that
  crossed a plugin boundary, where a near-miss would silently show the wrong monster. Only an exact
  name opens a card; anything else goes into the search field, which shows live results without
  selecting.
- **`DropsCard` click roles** — with the link on, primary click hands the item to NER and
  right-click opens the wiki; with it off, NER not running, or the item id unresolved, the wiki stays
  on the primary click. Decided **per click**, never baked in at render. The id is armed inside
  `fill()`, where it is already resolved on the client thread, which is why `PriceCell` carries a
  mutable `itemId` (EDT-only, so unsynchronised).

### Threading model (important)

RuneLite splits work across the **client thread** (game state, menus, lifecycle), the **EDT**
(Swing panel), and **background executors**. The wiki services make blocking requests on RuneLite's
shared `ScheduledExecutorService`, never on the client thread or the EDT. Shared state crosses these
boundaries: the plugin's nav button, panel, and cached player levels are `volatile`; the
data-service indexes are published atomically. When adding code, keep client state reads on the
client thread (`clientThread.invoke`), Swing updates on the EDT (`SwingUtilities.invokeLater`), and
never block either on network I/O.

## Tests

JUnit 4 under `src/test/java`. Pure-logic tests exercise the static helpers and the view-model:
- **Stats:** `MonsterDataServiceTest` (name matching, variant labelling, default pick, relevant
  variants, wiki page resolution), `WikiSanitizerTest` (the Bucket field-cleaning shapes),
  `InfoboxLevelsTest` (recovering a value Bucket dropped; blanks stay a dash),
  `SuperiorServiceTest` (the superior-table parse), `MonsterStatsTest` (view-model semantics),
  `DefenceRollsTest`, `ExamineSummaryTest` (compact combat strings), `ExamineSummaryQueueTest`
  (native/injected ordering), `StatFormatTest`, `StatColorsTest`, `RenderTargetTest`,
  `LookupHistoryTest`.
- **`loot/`:** `DropPageServiceTest` (the rendered-page parse: rows inherit their `<h3>/<h4>` section,
  a drops region stops at the next `<h2>`, entity/footnote cleaning), `DropTableTest` (group →
  section grouping in page order; like-named sections in different groups stay distinct),
  `ItemIdServiceTest` (the `item_id` parse), `DropRowTest`, `DropFormatTest` (display shaping,
  values and their midpoint), and `ValueTiersTest` (Ground Items' tiers: defaults, the strict
  comparison, a disabled tier).
- **`wiki/`:** `WikiClientTest` (URL shape and the CDN parameters), `WikiCacheTest` (read/write,
  revisions, and every case that must mean "download"), `BucketQueryTest`, `TitleResolverTest`
  (normalise-then-redirect, loops, batching), `WikitextTemplatesTest` (nesting, bars inside links,
  footnotes).
- `MonsterLookupMessageTest` covers the inbound cross-plugin contract: precedence, defaults, and
  above all that a wrongly-typed or empty payload is ignored rather than thrown.
- `BetterMonsterExaminePluginTest` and `OverlayPreview` are dev launchers, not assertions.
