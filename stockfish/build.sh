build x86_64    x86-64-sse41-popcnt x86_64-linux-android29
build arm64-v8a armv8               aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Stockfish 11: last "classical" (pre-NNUE) release, kept as an alternative engine.
# Its Makefile predates NDK support, so we drive clang directly and force OS=Android
# for the PIE / no -lpthread flags. ~1 MB per ABI.
# ---------------------------------------------------------------------------
SRC11="$ROOT/stockfish/src11"
if [ ! -d "$SRC11/.git" ]; then
  git clone --depth 1 --branch sf_11 https://github.com/official-stockfish/Stockfish.git "$SRC11"
fi
cd "$SRC11/src"

build11() { # abi  arch  triple
  local abi=$1 arch=$2 triple=$3
  echo ">> Build SF11 $abi ($arch)"
  "$MAKE" clean >/dev/null 2>&1 || true
  "$MAKE" -j"$(nproc 2>/dev/null || echo 4)" build ARCH="$arch" COMP=clang OS=Android \n      COMPCXX="$TC/clang++$EXT --target=$triple -static-libstdc++ -Qunused-arguments" 2>&1 | grep -iE "error" || true
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libstockfish11.so" stockfish
  ls -la "$OUT/$abi/libstockfish11.so"
}

build11 x86_64    x86-64-modern x86_64-linux-android29
build11 arm64-v8a general-64    aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true

echo ">> OK"#!/usr/bin/env bash
# Builds Stockfish for Android (x86_64 emulator + arm64-v8a phones) with the NDK
# and drops the binaries into app/src/main/jniLibs/<abi>/libstockfish.so.
#
# Usage (Git Bash):  ./stockfish/build.sh [sf_tag]      (default: sf_19)
# NNUE networks are downloaded automatically by the Stockfish Makefile.
set -euo pipefail

TAG="${1:-sf_19}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/stockfish/src"
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

echo ">> NDK : $NDK_DIR"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/official-stockfish/Stockfish.git "$SRC"
fi

export PATH="$TC:$PATH"
cd "$SRC/src"

build() { # abi  arch  triple
  local abi=$1 arch=$2 triple=$3
  echo ">> Build $abi ($arch)"
  "$MAKE" clean >/dev/null 2>&1 || true
  "$MAKE" -j"$(nproc 2>/dev/null || echo 4)" build ARCH="$arch" COMP=ndk \
      COMPCXX="$TC/clang++$EXT --target=$triple" 2>&1 | grep -v "^$TC" | tail -3
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libstockfish.so" stockfish
  ls -la "$OUT/$abi/libstockfish.so"
}

build x86_64    x86-64-sse41-popcnt x86_64-linux-android29
build arm64-v8a armv8               aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# Stockfish 11: last "classical" (pre-NNUE) release, kept as an alternative engine.
# Its Makefile predates NDK support, so we drive clang directly and force OS=Android
# for the PIE / no -lpthread flags. ~1 MB per ABI.
# ---------------------------------------------------------------------------
SRC11="$ROOT/stockfish/src11"
if [ ! -d "$SRC11/.git" ]; then
  git clone --depth 1 --branch sf_11 https://github.com/official-stockfish/Stockfish.git "$SRC11"
fi
cd "$SRC11/src"

build11() { # abi  arch  triple
  local abi=$1 arch=$2 triple=$3
  echo ">> Build SF11 $abi ($arch)"
  "$MAKE" clean >/dev/null 2>&1 || true
  "$MAKE" -j"$(nproc 2>/dev/null || echo 4)" build ARCH="$arch" COMP=clang OS=Android \
      COMPCXX="$TC/clang++$EXT --target=$triple -static-libstdc++ -Qunused-arguments" 2>&1 \
      | grep -iE "error" || true
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libstockfish11.so" stockfish
  ls -la "$OUT/$abi/libstockfish11.so"
}

build11 x86_64    x86-64-modern x86_64-linux-android29
build11 arm64-v8a general-64    aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true

echo ">> OK"
