# Changelog

## 1.1 — 2026-09-27

- **Full screen**: the status and navigation bars are hidden in every screen; swipe from the top or bottom edge to show them briefly. The area around the camera cutout stays clear.

## 1.0 — 2026-09-27

First release of GamesApp: board games played offline against real engines, everything bundled in the APK.

### Games
- **Chess**: standard chess or **Chess960**. Opponents: Stockfish 19 / 11, Leela Chess Zero (Bad Gyal, T1, Maia networks), Maia 3, Rodent V personalities, Reckless, PlentyChess, Berserk, Sunfish; Chess960 against Fairy-Stockfish.
- **Draughts**: International 10x10 (Scan, Moby Dam) and English checkers 8x8 (Marcher).
- **Shogi**: against Fairy-Stockfish with its shogi NNUE network. Kanji or letter pieces, drops, promotions, sennichite, byoyomi clocks.
- **Nine Men's Morris** and **Lasker Morris** (Sanmill).
- **Hnefatafl**: Copenhagen, Fetlar, Tawlbwrdd, Tablut, Sea Battle, Brandub (OpenTafl).
- **Fox games**: Fox and Hounds, Fox and Geese (13 and 17), Two Foxes, Asalto.

Every game has clocks (sudden death, Fischer, per move; byoyomi for shogi), undo, resign, rematch and save/resume.

The APK is large (~530 MB) because the engines' neural networks are bundled, so everything works offline.

Licenses and credits: see README.md and THIRD_PARTY.md (the app is GPL-3.0, as required by the engines it bundles).
