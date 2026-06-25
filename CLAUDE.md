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

Single package `com.bettermonsterexamine`. The plugin merges **two data sources** behind one
panel, both fetched off the client thread and cached so later launches work offline.

### Data flow

1. **`MonsterDataService`** (singleton) — the bulk dataset. Downloads the ~2.3 MB Weirdgloop
   DPS-calc `monsters.json` (same source the OSRS DPS calculator uses) once, caches it under
   `.runelite/better-monster-examine/`, and refreshes weekly (`MAX_AGE = 7 days`). Builds two
   indexes published atomically: **by NPC id** and **by lower-case base name → variant list**.
   A single name (e.g. Vorkath) can have several `MonsterData` variants distinguished by
   `version`. Accessors return empty until the async load lands. `matchNames` is a pure static
   helper kept unit-testable without the dataset.

2. **`WikiInfoboxService`** (singleton) — fills the gaps. Fields the Weirdgloop dataset lacks
   (aggressive, poisonous, XP bonus, full max-hit list, poison/venom/cannon/thrall immunities)
   are fetched **on demand, per monster**, by pulling the monster's OSRS Wiki page as raw
   wikitext (`?action=raw`) and parsing its infobox. Results (including empty/missing pages)
   are cached in-memory so re-renders don't re-fetch; only genuine network failures stay
   retryable. `parse`/`stripMarkup` are static and unit-tested.

3. **`MonsterData`** / **`WikiInfo`** — DTOs. `MonsterData` is the Gson-mapped Weirdgloop
   schema (nested `Skills`, `Offensive`, `Defensive`, `Weakness`, `Immunities`; Lombok
   `@Getter`). `WikiInfo` holds the parsed infobox params + a version-name→index map.

### Plugin + UI

- **`BetterMonsterExaminePlugin`** — lifecycle and game integration. Adds the nav button when
  `enableSidePanel` is on; adds a right-click **"Stats"** menu entry anchored on each NPC's
  Examine entry (gated on both config flags). Resolves the clicked NPC **by id, falling back to
  name + matching in-game combat level to a variant** — so it covers variant spawn ids the
  dataset doesn't carry (e.g. Hellhounds across dungeons). Caches the player's combat and HP
  levels each `GameTick` so the panel can read them safely off-thread.
- **`BetterMonsterExaminePanel`** (`PluginPanel`) — search field + variant dropdown + the
  wiki-style infobox card, all in one vertical scroll. Loads Weirdgloop fields immediately and
  patches in the async wiki fields when they arrive. Colour-codes player-relevant values
  (combat level vs yours, negative flat armour green / positive red, aggressive red, max hits
  above your HP red).
- **`MonsterIcons`** (singleton) — loads the stat/attack/skill icons bundled in `resources/`.
- **`MonsterCardOverlay`** (`Overlay`) — the in-game overlay option, modelled on the Monster
  Examine spell: a compact, tabbed box drawn directly with `Graphics2D` (not a snapshot of the
  Swing card) in the game viewport. Four **clickable** tabs — Combat / Aggressive / Defensive /
  Info — partition the stats. The plugin pushes the selected `MonsterData` in via `setMonster`;
  the async wiki fields land later via `setWiki` and appear on the next frame (overlays redraw
  every frame, so no re-snapshot is needed). It reads the highlight palette live, so a config
  change applies immediately. Tab clicks are routed from a `MouseManager` listener in the plugin:
  `tabAt` hit-tests a canvas point against the tab strip (using the overlay's renderer-maintained
  bounds) and `setActiveTab` switches tabs, consuming the click. Player-relevant colours come
  from **`StatColors`** (shared with the side panel); value formatting reuses the package-private
  static helpers on `BetterMonsterExaminePanel`.
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
(Swing panel), and **background executors / OkHttp callbacks** (data fetches). Shared state
crosses these boundaries: the plugin's nav button, panel, and cached player levels are
`volatile`; the data-service indexes are published atomically. When adding code, keep client
state reads on the client thread (`clientThread.invoke`), Swing updates on the EDT
(`SwingUtilities.invokeLater`), and never block either on network I/O.

## Tests

JUnit 4 under `src/test/java`. Pure-logic tests (`MonsterDataServiceTest`,
`WikiInfoboxServiceTest`) exercise the static helpers; `BetterMonsterExaminePanelTest` covers
panel rendering logic. `BetterMonsterExaminePluginTest` and `OverlayPreview` are dev launchers,
not assertions.
