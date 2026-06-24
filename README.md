# Better Monster Examine

A RuneLite side-panel plugin to search any Old School RuneScape monster and view its
**full, wiki-style combat stats** — defences, offensive bonuses, weakness, immunities,
max hits and more — without leaving the client.

## Screenshots

| Boss showcase | Threat colour-coding | Combat-level colour |
|:--:|:--:|:--:|
| ![Vorkath](previews/vorkath.png) | ![Blue Moon](previews/blue_moon.png) | ![Goblin](previews/goblin.png) |
| **Vorkath** — size and attributes on one line (*7x7, Draconic, Undead, Fiery*), and a **max hit above your Hitpoints level flagged red** (hover it for why). | **Blue Moon** — negative **flat armour in green** (−5: it takes extra damage), and **Aggressive: Yes in red**. | Combat level coloured against yours like the in-game hover (green = far below), with examine text. |

## Features

- **Searchable side panel** — type a monster name, or right-click any monster in game and
  pick **Stats**. Variants are selectable from a dropdown.
- **Wiki-style infobox layout** — mirroring the OSRS Wiki with important values highlighted.
- **Quick links** — open the monster's **Wiki** page or the **DPS calculator**
  in one click.
- **Accessible highlighting** — colour coding is configurable: the default red/green palette, 
  a **colour-blind-friendly** palette with redundant cues, or off entirely.

[ws]: https://oldschool.runescape.wiki/w/RuneScape:WikiSync

## Data sources

- The bulk of the stats come from the [Weirdgloop OSRS DPS-calc dataset][wg]
  (`monsters.json`), keyed by NPC id. It's fetched on first use from the OSRS Wiki
  DPS-calc CDN — the same source the calculator itself uses — and cached under
  `.runelite/better-monster-examine/`, refreshing weekly, so later launches work offline.
- Fields the dataset doesn't carry (aggressive, poisonous, XP bonus, the full max-hit
  list, and poison/venom/cannon/thrall immunities) are fetched **on demand and cached**
  from the monster's OSRS Wiki infobox.

[wg]: https://github.com/weirdgloop/osrs-dps-calc

## Building / running

```
./gradlew run            # launch a dev client with the plugin loaded
./gradlew build          # build
```

## Credits & licence

This plugin began as a fork of [Koitere/monster-stats][orig] by **Liam King**, which is
released under the **BSD 2-Clause Licence**. That notice is retained in [`LICENSE`](LICENSE).
The data layer and UI have since been substantially rewritten.

[orig]: https://github.com/Koitere/monster-stats
