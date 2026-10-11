# Changelog

## 0.5.1 — 2026-10-11

- Built with Kotlin 2.3.20 (was 2.2.10). No change in how the app plays.

## 0.5.0 — 2026-10-11

- **Two players** in every game: a new Mode choice on the New game screen (vs Engine / Two players) lets two people take turns on one phone, with the clocks, Undo (one move at a time), Resign by the side to move, Hint and Share. Two-player games are kept in the history but left out of your stats.

## 0.4.0 — 2026-10-11

- **Hint button** in every game: the engine suggests a move at full strength (whatever level you play against); the move is highlighted on the board and written under it until you play.
- App icon: the queen is sized to fit the launch screen too.

## 0.3.2 — 2026-09-28

- **App icon**: just the chess queen, large and centred, so it reads the same on the home screen and in app lists.

## 0.3.1 — 2026-09-28

- **New app icon**: the chess queen and the draughts king side by side on a wood board.

## 0.3.0 — 2026-09-28

### New
- **History and stats**: every finished game is kept (up to 500). The History screen, from the home page, shows your overall score, the score per game and per opponent, and the list of games; each one opens with its moves, Share and Delete.
- **Share a game**: a share button in every game sends it as a file and as text, in the standard format of the game: **PGN** for chess and Chess960, **PDN** for draughts and English checkers, **KIF** for shogi, plain text for Nine Men's Morris, hnefatafl and fox games.

### Improvements
- International draughts and English checkers now have separate saves, so starting one no longer erases a game in progress in the other; Resume opens the most recent.
- Clocks refresh only when the displayed time changes, which saves battery.

## 0.2.0 — 2026-09-27

### Reliability
- An engine that stopped (killed in the background, or stuck) is restarted automatically; if it still fails, the error is shown with a **Retry** button and Undo stays available.
- Leaving a game stops its engine, so it no longer takes memory in the background; it restarts at the next move.
- Rotating the phone or switching dark mode no longer sends you back to the home screen.
- A saved game that cannot be read is discarded instead of crashing Resume.
- Hnefatafl and fox games no longer crash if the AI fails, and the hnefatafl AI respects very short clocks.

### Games
- Draughts: Scan and Moby Dam now get the right position after king moves (they could search a wrong one).
- Undo no longer gives back the time spent on the current move.
- Shogi: faster move handling, and leaving the app during byoyomi no longer refills the period.

### Size and security
- The APK is about 135 MB smaller (compressed networks, optimised code).
- Only saved games and settings are backed up, not the engine files.
- Engine sources are pinned to exact revisions and downloaded networks are checked by SHA-256.

## 0.1.1 — 2026-09-27

- **Full screen**: the status and navigation bars are hidden in every screen; swipe from the top or bottom edge to show them briefly. The area around the camera cutout stays clear.

## 0.1.0 — 2026-09-27

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
