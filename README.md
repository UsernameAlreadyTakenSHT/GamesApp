# Games

Offline board games for Android, built with Jetpack Compose. Everything runs on the phone:
no account, no network, the engines are bundled in the APK.

> Personal project, built with AI assistance (Claude Code). No store release is planned: releases are
> published here on GitHub only (installable with [Obtainium](https://github.com/ImranR98/Obtainium)).

Package: `io.github.usernamealreadytakensht.games` · minSdk 29 · ABIs: `arm64-v8a` (phones), `x86_64` (emulator).

## Games

| Game | Status |
|------|--------|
| **Chess** | Playable against the engines below. Two-step setup (colour, clock and options; then opponent). Clocks (sudden death, Fischer, per move), takebacks (unlimited / once / off), move confirmation, auto-queen, engine thinking-time scale, resign, rematch with swapped colours, save/resume, last-move and check highlights, promotion picker. |
| **Checkers** | Placeholder (pieces bundled, rules not implemented yet). |

## Chess opponents

Picked in two steps: an engine, then a version / network, then a strength setting that
depends on how the engine can be limited.

| Engine | Versions / networks | Strength setting | Range |
|--------|--------------------|------------------|-------|
| **Stockfish** | 19 (NNUE), 11 (last classical evaluation) | `UCI_Elo` | 1320–3190 (SF 19), 1350–2850 (SF 11), or Max |
| **Leela Chess Zero** 0.32.1 | Bad Gyal 8 (128x10), T1 256x10 distilled | Search nodes per move | 1 → 1000 nodes, or time-based |
| | Maia (1100 → 1900, one network per rating) | Human rating | Elo 1100–1900, no search: plays like a human of that level |
| **Reckless** 0.9 (Rust) | — | Search depth | 1 → 20 plies, or time-based |
| **PlentyChess** 8.0 (C++) | — | Search depth | 1 → 20 plies, or time-based |
| **Berserk** 14 (C) | — | Search depth | 1 → 20 plies, or time-based |
| **Sunfish** 2026 | Kotlin port, runs in-process | Search depth | 1 → 20 plies, or time-based |
| **Maia 3** (5M) | Transformer exported to ONNX, runs in-process | Human rating | Elo 600–2600, no search: one model imitating any level |

Rough feel: the Maia models are the only opponents that play *like a human* (Maia 3 covers 600–2600, the Lc0 Maia networks 1100–1900). Stockfish's Elo
mode is adjustable but artificial (perfect moves with random errors). The depth-limited
engines stay solid even at depth 1 (~1600+) because their evaluation is strong; they simply
stop seeing tactics. Sunfish is the genuinely weak one.

Engines run as separate processes over UCI (`lib*.so` files executed from the app's native
library directory); Sunfish is Kotlin code. Only one engine process lives at a time.

## Building

The Android project builds with Android Studio as usual, **but the engine binaries and
networks are not committed** (they weigh ~280 MB per ABI). Generate them once with the
scripts below (Git Bash on Windows; they use the NDK from the Android SDK):

```
./stockfish/build.sh      # Stockfish 19 + 11
./lc0/build.sh            # Lc0 + Bad Gyal, T1 and Maia networks
./reckless/build.sh       # needs rustup (installed by the script's instructions)
./plentychess/build.sh    # needs a running x86_64 emulator (network pre-processing)
./berserk/build.sh
```

Maia 3 is exported from the PyTorch checkpoint with `maia3/export_onnx.py` (needs a Python venv
with torch, onnx, onnxruntime, python-chess and the `maia3` package); the script also writes
`maia3/reference.json`, which the unit tests use to check the Kotlin encoder against Python.

Each script explains its own quirks (cross-files, thread stack sizes, network layouts).
The build produces one APK per ABI (`app-x86_64-debug.apk` for the emulator,
`app-arm64-v8a-debug.apk` for a phone).

## Credits and licenses

See [THIRD_PARTY.md](THIRD_PARTY.md) for the full table. In short:

- Engines: [Stockfish](https://github.com/official-stockfish/Stockfish),
  [Leela Chess Zero](https://github.com/LeelaChessZero/lc0),
  [Reckless](https://github.com/codedeliveryservice/Reckless),
  [PlentyChess](https://github.com/Yoshie2000/PlentyChess),
  [Berserk](https://github.com/jhonnold/berserk),
  [Sunfish](https://github.com/thomasahle/sunfish) — all GPL-3.0.
- Networks: [Maia](https://github.com/CSSLab/maia-chess) (GPL-3.0),
  [Bad Gyal](https://github.com/dkappe/leela-chess-weights), T1 256x10 (lczero.org).
- Pieces: Cburnett chess set (GFDL / CC BY-SA 3.0), Antonsusi draughts stones (public domain),
  both from Wikimedia Commons — see [art/pieces/README.md](art/pieces/README.md).
- Rules: [chesslib](https://github.com/bhlangonijr/chesslib) (Apache-2.0).

This app itself is distributed under the GPL-3.0, as required by the engines it bundles.
