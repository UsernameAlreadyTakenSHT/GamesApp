# Third-party components

Engines are built from source by the `*/build.sh` scripts; networks and
piece art are downloaded / copied by those scripts and `art/pieces/README.md`.

| Component | Source | License |
|-----------|--------|---------|
| Stockfish 19 and 11 | https://github.com/official-stockfish/Stockfish | GPL-3.0 |
| Leela Chess Zero (lc0) v0.32.1 | https://github.com/LeelaChessZero/lc0 | GPL-3.0 |
| Bad Gyal 8 network | https://github.com/dkappe/leela-chess-weights | as published by the author |
| T1 256x10 distilled network | https://lczero.org (networks-contrib) | GPL-3.0 (Lc0 project) |
| Maia networks (1100–1900) | https://github.com/CSSLab/maia-chess | GPL-3.0 |
| Cburnett chess pieces | Wikimedia Commons | GFDL / CC BY-SA 3.0 |
| Antonsusi draughts stones | Wikimedia Commons | Public domain |
| chesslib | https://github.com/bhlangonijr/chesslib | Apache-2.0 |
| Reckless 0.9.0 | https://github.com/codedeliveryservice/Reckless | AGPL-3.0 |
| PlentyChess 8.0.0 (+ network 0178r) | https://github.com/Yoshie2000/PlentyChess | GPL-3.0 |
| Berserk 14 (dev 2026-09, + network 9b84c340af7e) | https://github.com/jhonnold/berserk | GPL-3.0 |
| Sunfish 2026 (Kotlin port in app/.../engine/Sunfish.kt) | https://github.com/thomasahle/sunfish | GPL-3.0 |
| Maia-3 5M model (exported to ONNX) | https://github.com/CSSLab/maia3 · https://huggingface.co/UofTCSSLab/Maia3-5M | AGPL-3.0 (repository; the Hugging Face model card states no license) |
| ONNX Runtime (Android) | https://github.com/microsoft/onnxruntime | MIT |
| Rodent V 1.2 (+ personalities, networks and opening books) | https://github.com/nescitus/Rodent-V | GPL-3.0 |
| Scan 3.1 (+ book and eval data) | https://github.com/rhalbersma/scan (mirror of Fabien Letouzey's engine) | GPL-3.0 |
| Moby Dam (+ eval tables and book) | https://github.com/rhalbersma/mobydam (mirror of Harm Jetten's engine) | GPL-3.0 |
| Sanmill engine (`tgf uci`, Rust) | https://github.com/calcitem/Sanmill | AGPL-3.0 |
| Sanmill icon | https://github.com/calcitem/Sanmill (`fastlane/metadata/android/en-US/images/icon.png`) | AGPL-3.0 (part of the repository) |
| Stockfish icon | https://github.com/official-stockfish/stockfish-web (`static/images/logo/`) | designed by Klein Maetschke; website MIT, no separate license stated for the icon |
| Leela Chess Zero logo | https://github.com/LeelaChessZero/lczero.org (`static/images/logo.svg`) | no license stated in the website repository |
| Reckless mascot | https://github.com/codedeliveryservice/Reckless (README image) | AGPL-3.0 (part of the project) |
| Maia icon | https://github.com/CSSLab/maia-platform-frontend (`public/maia-ios-icon.png`) | GPL-3.0 (part of the repository) |
| Sunfish logo | https://github.com/thomasahle/sunfish (`docs/logo/`) | GPL-3.0 (part of the repository) |
| Rodent V logo | https://github.com/nescitus/Rodent-V (`logo.png`) | GPL-3.0 (part of the repository) |

## Papers to cite

The Maia authors ask that work using their models cite the papers (not a license requirement,
but the polite thing to do):

- Maia (1100–1900 networks): McIlroy-Young, Sen, Kleinberg, Anderson.
  *Aligning Superhuman AI with Human Behavior: Chess as a Model System.* KDD 2020.
  https://arxiv.org/abs/2006.01855
- Maia 3: Monroe, Eilender, Chalmers, Tang, Anderson.
  *Chessformer: A Unified Architecture for Chess Modeling.* ICLR 2026.
  https://arxiv.org/abs/2605.19091
