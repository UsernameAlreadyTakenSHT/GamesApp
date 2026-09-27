#!/usr/bin/env bash
# Builds Fairy-Stockfish (github.com/fairy-stockfish/Fairy-Stockfish, GPL-3) for Android
# (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libfairy.so.
#
# Usage (Git Bash):  ./fairy/build.sh [git ref]      (default: the pinned commit below)
#
# largeboards=yes is required for shogi (9x9 is beyond the default 8x8 bitboards).
# No network is embedded (nnue=no): the per-variant NNUE files are downloaded below into
# app/src/main/assets/nets and passed to the engine as EvalFile.
set -euo pipefail

REF="${1:-9f778da667f6e07dae1e85d3e2ea204fc6dee94d}"   # pinned commit; pass a ref to override
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/fairy/src"
OUT="$ROOT/app/src/main/jniLibs"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe ;;
  Darwin*)              HOST=darwin-x86_64;  EXT= ;;
  *)                    HOST=linux-x86_64;   EXT= ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"
MAKE="$NDK_DIR/prebuilt/$HOST/bin/make$EXT"
[ -x "$MAKE" ] || MAKE=make

if [ ! -d "$SRC/.git" ]; then
  # Exact commit (pinned below), so a moved branch or a compromised upstream cannot change the build.
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 https://github.com/fairy-stockfish/Fairy-Stockfish.git "$REF"
  git -C "$SRC" checkout -q FETCH_HEAD
fi
export PATH="$TC:$PATH"
cd "$SRC/src"

build() { # abi  arch  triple
  local abi=$1 arch=$2 triple=$3
  echo ">> Build fairy-stockfish $abi ($arch)"
  "$MAKE" clean >/dev/null 2>&1 || true
  "$MAKE" -j"$(nproc 2>/dev/null || echo 4)" build ARCH="$arch" COMP=ndk largeboards=yes nnue=no \
      COMPCXX="$TC/clang++$EXT --target=$triple" 2>&1 | grep -iE "error|warning: unused" | head -20 || true
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libfairy.so" stockfish
  ls -la "$OUT/$abi/libfairy.so"
}

# NNUE networks (passed as EvalFile by the app): chess (Chess960) and shogi, listed on
# https://fairy-stockfish.github.io/nnue/
NETS="$ROOT/app/src/main/assets/nets"
mkdir -p "$NETS"
fetch() { # file url sha256: downloaded once, then verified on every build
  [ -s "$NETS/$1" ] || { echo ">> Download $1"; curl -fsSL --retry 3 -o "$NETS/$1.part" "$2" && mv "$NETS/$1.part" "$NETS/$1"; }
  echo "$3  $NETS/$1" | sha256sum -c --quiet - || { echo "Checksum mismatch for $1: file removed" >&2; rm -f "$NETS/$1"; exit 1; }
}
fetch nn-46832cfbead3.nnue https://tests.stockfishchess.org/api/nn/nn-46832cfbead3.nnue \
  46832cfbead30ac4992945bf3d52ff202601126c5dbffce2e78e7ef860dacb89
fetch shogi-878ca61334a7.nnue "https://drive.usercontent.google.com/download?id=1RA0mstKWi_tH98DVdLGBamEdE8FXiSgB&export=download&confirm=t" \
  878ca61334a759af2b01111b3c0e187f4bae1d634315223330a2dadd36689f5a

build x86_64    x86-64-sse41-popcnt x86_64-linux-android29
build arm64-v8a armv8               aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true
echo ">> OK"
