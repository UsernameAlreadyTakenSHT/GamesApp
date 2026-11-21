#!/usr/bin/env bash
# Builds c4ke (github.com/citrus610/c4ke, MIT), a chess engine that fits in 4 KiB, for
# Android (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libc4ke.so.
#
# Usage (Git Bash):  ./c4ke/build.sh [tag]      (default: v3.0)
#
# What ships is not the 4 KiB artefact (a script that unpacks and compiles its own minified
# source on the spot) but the same source compiled directly, which is upstream's `make c4ke`:
# same search and evaluation, plus the UCI commands the app needs (`position fen`,
# `setoption`, `go infinite` / `stop`). The source is untouched. It includes
# <bits/stdc++.h>, a GCC header the NDK's libc++ lacks: shim/bits/stdc++.h stands in for it
# (ice4/build.sh uses it too). -fexperimental-library turns on libc++'s std::jthread.
#
# The search threads keep their tables on the stack: pthread_create is wrapped
# (../berserk/bigstack.c) to give them 64 MB instead of Bionic's 1 MB.
set -euo pipefail

TAG="${1:-v3.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/c4ke/src"
SHIM="$ROOT/c4ke/shim"
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
  git clone --depth 1 --branch "$TAG" https://github.com/citrus610/c4ke.git "$SRC"
fi
cd "$SRC"

build() { # abi  triple  extra flags
  local abi=$1 triple=$2; shift 2
  echo ">> Build c4ke $abi"
  mkdir -p "$OUT/$abi"
  "$TC/clang$EXT" --target="$triple" -O2 -c "$ROOT/berserk/bigstack.c" -o "bigstack-$abi.o"
  "$TC/clang++$EXT" --target="$triple" -std=c++23 -O3 -DNDEBUG -pthread -static-libstdc++ \
      -fexperimental-library -w -I "$SHIM" "$@" -Wl,--wrap=pthread_create src/main.cpp "bigstack-$abi.o" -o "$OUT/$abi/libc4ke.so"
  rm -f "bigstack-$abi.o"
  "$TC/llvm-strip$EXT" "$OUT/$abi/libc4ke.so"
  ls -la "$OUT/$abi/libc4ke.so"
}

# x86_64 is only ever the emulator, whose host CPU has AVX2 in practice.
build x86_64    x86_64-linux-android29  -march=x86-64-v3
build arm64-v8a aarch64-linux-android29
echo ">> OK"
