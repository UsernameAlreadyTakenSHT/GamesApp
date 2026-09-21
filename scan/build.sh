#!/usr/bin/env bash
# Builds Scan 3.1 (Fabien Letouzey's international draughts engine, GPL-3) for Android
# (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libscan.so. Also copies the data files the "normal" variant
# needs (data/book, data/eval — no bitbases) and scan.ini into app/src/main/assets/scan/,
# from where the app extracts them next to the engine's working directory at runtime.
#
# Usage (Git Bash):  ./scan/build.sh
# Source: mirror of Scan at https://github.com/rhalbersma/scan (master).
# Scan's Makefile is a plain g++ one-liner, so clang is driven directly; the pthread_create
# wrapper (bigstack.c) enforces a 64 MB stack for the engine's input/search threads.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/scan"
SRC="$WORK/src"
OUT="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets/scan"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe ;;
  Darwin*)              HOST=darwin-x86_64;  EXT= ;;
  *)                    HOST=linux-x86_64;   EXT= ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 https://github.com/rhalbersma/scan.git "$SRC"
fi
cd "$SRC/src"
cp "$ROOT/plentychess/bigstack.c" .

SRCS="bb_base.cpp bb_comp.cpp bb_index.cpp bit.cpp book.cpp common.cpp dxp.cpp eval.cpp fen.cpp \
game.cpp gen.cpp hash.cpp hub.cpp libmy.cpp list.cpp main.cpp move.cpp pos.cpp score.cpp \
search.cpp socket.cpp sort.cpp thread.cpp tt.cpp util.cpp var.cpp"

build() { # abi  triple  extra cxxflags...
  local abi=$1 triple=$2; shift 2
  echo ">> Build scan $abi"
  "$TC/clang$EXT" --target="$triple" -O2 -c bigstack.c -o "bigstack-$abi.o"
  "$TC/clang++$EXT" --target="$triple" -std=c++14 -fno-rtti -O2 -DNDEBUG -w -pthread "$@" \
    -static-libstdc++ -Wl,--wrap=pthread_create $SRCS "bigstack-$abi.o" -o "scan-$abi"
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libscan.so" "scan-$abi"
  ls -la "$OUT/$abi/libscan.so"
}

build x86_64    x86_64-linux-android29  -mpopcnt -march=haswell
build arm64-v8a aarch64-linux-android29

# Data files for the "normal" variant + config. Bitbases are not shipped (bb-size = 0).
mkdir -p "$ASSETS/data"
cp "$SRC/data/book" "$SRC/data/eval" "$ASSETS/data/"
cp "$SRC/scan.ini" "$ASSETS/scan.ini"
ls -la "$ASSETS" "$ASSETS/data"
echo ">> OK"
