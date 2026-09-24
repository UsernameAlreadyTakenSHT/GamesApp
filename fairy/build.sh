#!/usr/bin/env bash
# Builds Fairy-Stockfish (github.com/fairy-stockfish/Fairy-Stockfish, GPL-3) for Android
# (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libfairy.so.
#
# Usage (Git Bash):  ./fairy/build.sh [git ref]      (default: master)
#
# largeboards=yes is required for shogi (9x9 is beyond the default 8x8 bitboards).
# No network is embedded (nnue=no): without an EvalFile the engine plays with its classical
# evaluation (per-variant NNUE files could be shipped as assets and passed via EvalFile).
set -euo pipefail

REF="${1:-master}"
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
  git clone --depth 1 --branch "$REF" https://github.com/fairy-stockfish/Fairy-Stockfish.git "$SRC"
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

build x86_64    x86-64-sse41-popcnt x86_64-linux-android29
build arm64-v8a armv8               aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true
echo ">> OK"
