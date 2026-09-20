#!/usr/bin/env bash
# Builds Berserk (C) for Android (x86_64 emulator + arm64-v8a phones) and drops the binaries
# into app/src/main/jniLibs/<abi>/libberserk.so. The NNUE network named in src/makefile is
# downloaded and embedded (incbin).
#
# Usage (Git Bash):  ./berserk/build.sh [git ref]      (default: main)
# The engine has NEON and AVX2 code paths, so a single clang invocation per ABI is enough.
# pthread_create is wrapped (bigstack.c) to enforce a 64 MB thread stack on Bionic.
set -euo pipefail

REF="${1:-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/berserk"
SRC="$WORK/src"
OUT="$ROOT/app/src/main/jniLibs"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe ;;
  Darwin*)              HOST=darwin-x86_64;  EXT= ;;
  *)                    HOST=linux-x86_64;   EXT= ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$REF" https://github.com/jhonnold/berserk.git "$SRC"
fi
cd "$SRC/src"
cp "$WORK/bigstack.c" .

NET="$(grep '^MAIN_NETWORK' makefile | sed 's/.*= *//')"
VERSION="$(grep '^VERSION' makefile | sed 's/.*= *//')"
[ -f "$NET" ] || curl -fskL -o "$NET" \
  "https://github.com/jhonnold/berserk-networks/releases/download/networks/$NET"

SRCS="attacks.c bench.c berserk.c bits.c board.c datagen.c eval.c history.c move.c movegen.c \
movepick.c perft.c random.c search.c see.c tb.c thread.c transposition.c uci.c util.c zobrist.c \
nn/accumulator.c nn/evaluate.c pyrrhic/tbprobe.c"

build() { # abi  triple  cflags...
  local abi=$1 triple=$2; shift 2
  echo ">> Build berserk $abi"
  "$TC/clang$EXT" --target="$triple" -std=gnu11 -O3 -w -DNDEBUG "-DVERSION=\"$VERSION\"" \
    "-DEVALFILE=\"$NET\"" "$@" -pthread -Wl,--wrap=pthread_create $SRCS bigstack.c -lm -o "berserk-$abi"
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libberserk.so" "berserk-$abi"
  ls -la "$OUT/$abi/libberserk.so"
}

build x86_64    x86_64-linux-android29  -msse -msse2 -mssse3 -msse4.1 -mbmi -mfma -mavx2
build arm64-v8a aarch64-linux-android29
echo ">> OK"
