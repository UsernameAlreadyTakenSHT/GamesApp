#!/usr/bin/env bash
# Builds ice4 (github.com/MinusKelvin/ice4, GPL-3.0), the chess engine that fits in 4096 bytes,
# for Android (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libice4.so.
#
# Usage (Git Bash):  ./ice4/build.sh [tag]      (default: v6.1)
#
# What ships is not the 4 KB artefact (a shell script that unpacks and compiles its own
# source with xz and the system compiler, neither of which a phone has) but the same
# source compiled directly, as its `OPENBENCH` build: same search and evaluation, plus the
# UCI commands the app needs (`position fen`, `setoption`, `go infinite` / `stop`).
# The source is untouched. It includes <bits/stdc++.h>, a GCC header the NDK's libc++ lacks:
# ../c4ke/shim/bits/stdc++.h stands in for it.
#
# The search threads keep their tables on the stack: pthread_create is wrapped
# (../berserk/bigstack.c) to give them 64 MB instead of Bionic's 1 MB.
set -euo pipefail

TAG="${1:-v6.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/ice4/src"
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
  git clone --depth 1 --branch "$TAG" https://github.com/MinusKelvin/ice4.git "$SRC"
fi
cd "$SRC"

build() { # abi  triple  extra flags
  local abi=$1 triple=$2; shift 2
  echo ">> Build ice4 $abi"
  mkdir -p "$OUT/$abi"
  "$TC/clang$EXT" --target="$triple" -O2 -c "$ROOT/berserk/bigstack.c" -o "bigstack-$abi.o"
  "$TC/clang++$EXT" --target="$triple" -std=c++20 -O3 -DOPENBENCH -DNDEBUG -pthread -static-libstdc++ \
      -w -I "$SHIM" "$@" -Wl,--wrap=pthread_create src/main.cpp "bigstack-$abi.o" -o "$OUT/$abi/libice4.so"
  rm -f "bigstack-$abi.o"
  "$TC/llvm-strip$EXT" "$OUT/$abi/libice4.so"
  ls -la "$OUT/$abi/libice4.so"
}

# x86_64 is only ever the emulator, whose host CPU has AVX2 in practice.
build x86_64    x86_64-linux-android29  -march=x86-64-v3
build arm64-v8a aarch64-linux-android29
echo ">> OK"
