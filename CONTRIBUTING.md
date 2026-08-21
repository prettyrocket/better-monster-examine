# Contributing

Thanks for taking an interest in Better Monster Examine. Bug reports, ideas and pull
requests are all welcome.

## Before you write code

For anything larger than a small fix, **open an issue first** so we can agree on the
approach. That's not bureaucracy — this plugin renders OSRS Wiki data, and most of the
hard decisions are about *which* wiki source a feature should read from and how it
caches. Talking first saves you from building against the wrong one.

Small fixes (typos, an obvious bug, a broken link) — just send the PR.

## Building

Requires **JDK 11** (the plugin targets Java 11 and CI builds on Temurin 11).

```
./gradlew build          # compile, run checkstyle, run tests — this is what CI runs
./gradlew test           # tests only
./gradlew checkstyleMain checkstyleTest   # lint only
./gradlew run            # launch a dev RuneLite client with the plugin loaded
```

Run a single test class or method (JUnit 4):

```
./gradlew test --tests com.bettermonsterexamine.MonsterDataServiceTest
./gradlew test --tests 'com.bettermonsterexamine.MonsterDataServiceTest.matchNames*'
```

`run` has no `main` of its own — it launches through a test-classpath entrypoint, which
is why it needs the test source set to compile.

## Style — this is the one that bites

Checkstyle runs as part of `build` with **`maxWarnings = 0`**, so *any* style violation
fails the build. The two that catch people out:

- **Indent with tabs, not spaces.** The entire codebase uses tabs. An editor that
  helpfully converts them will fail CI on every line you touched.
- **Imports must be ordered, and unused imports removed.**

Otherwise it's standard RuneLite house style for braces and whitespace. Config lives in
`checkstyle.xml`, with `suppressions.xml` alongside it.

**Please run `./gradlew build` locally before pushing.** Both the build and the plugin-hub
packager verification run on every PR, and a formatting failure costs a round-trip.

## Architecture

`CLAUDE.md` in the repo root is the maintained architecture document — data flow, the
threading model, and why each layer sources what it does. Read the section covering the
area you're changing before you start. It is written for AI assistants but it's the same
document a human wants.

The one thing worth repeating here is the **threading model**, because it's the easiest
way to introduce a subtle bug:

- Game state, menus and lifecycle → the **client thread** (`clientThread.invoke`)
- Swing / panel updates → the **EDT** (`SwingUtilities.invokeLater`)
- Network I/O → a **background executor**, never blocking either of the above

## Tests

JUnit 4, under `src/test/java`. The pure-logic layers (parsers, formatters, the
view-model) are unit-tested and new logic there should come with tests. UI classes and
the dev launchers are not.

If you're fixing a parsing bug, a test with the shape of wiki markup that broke it is the
most useful thing you can add.

## Pull requests

- Branch off `main` and keep PRs focused on one thing.
- Write a commit message that says *why*, not just what.
- CI must be green: `gradle` and `hub-verify / packager` both run on every PR.
- A first-time contributor's workflow run needs a maintainer to approve it before it
  starts — so don't worry if your checks sit idle for a bit.

## Licence

This project is BSD 2-Clause (it began as a fork of
[Koitere/monster-stats](https://github.com/Koitere/monster-stats); the notice is retained
in `LICENSE`). By contributing you agree your work is licensed the same way.
