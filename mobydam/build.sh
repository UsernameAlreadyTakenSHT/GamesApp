#!/usr/bin/env bash
# Builds Moby Dam (Harm Jetten's international draughts engine, GPL-3, C) for Android
# (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libmobydam.so. Its evaluation tables (eval-ph0..3) and opening
# book (book.opn) go to app/src/main/assets/mobydam/ and are extracted at runtime.
#
# Usage (Git Bash):  ./mobydam/build.sh
# Source: mirror at https://github.com/rhalbersma/mobydam (master).
# Quirks: Bionic has no pthread_cancel (only used at shutdown) so it is defined away; the
# engine is launched with "-t 20" because its default transposition table is 512 MiB.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/mobydam"
SRC="$WORK/src"
OUT="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets/mobydam"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe ;;
  Darwin*)              HOST=darwin-x86_64;  EXT= ;;
  *)                    HOST=linux-x86_64;   EXT= ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 https://github.com/rhalbersma/mobydam.git "$SRC"
fi
cd "$SRC"
cp "$ROOT/plentychess/bigstack.c" .

SRCS="core/book.c core/end.c core/eval.c core/move.c core/tt.c core/util.c main/main.c main/pdn.c main/search.c"
DEFS="-DLOGF -DETC -DLMR -DKIL -DCUT -DCFLAGS=\"android\" -Dpthread_cancel(t)=0"
# Bionic's netdb.h does not pull in the IPv6 socket types that glibc provides transitively.
INCLUDES="-include netinet/in.h -include arpa/inet.h"
# core.h includes <x86intrin.h> unconditionally (only used by debug code); give arm64 an empty one.
mkdir -p stub-include && : > stub-include/x86intrin.h

build() { # abi  triple  extra cflags...
  local abi=$1 triple=$2; shift 2
  echo ">> Build mobydam $abi"
  "$TC/clang$EXT" --target="$triple" -O2 -c bigstack.c -o "bigstack-$abi.o"
  "$TC/clang$EXT" --target="$triple" -O3 -w -pthread $DEFS $INCLUDES -Icore -Imain "$@" \
    -Wl,--wrap=pthread_create $SRCS "bigstack-$abi.o" -lm -o "mobydam-$abi"
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libmobydam.so" "mobydam-$abi"
  ls -la "$OUT/$abi/libmobydam.so"
}

build x86_64    x86_64-linux-android29  -mpopcnt -march=haswell
build arm64-v8a aarch64-linux-android29 -Istub-include

mkdir -p "$ASSETS"
cp eval-ph0 eval-ph1 eval-ph2 eval-ph3 book/book.opn "$ASSETS/"
ls -la "$ASSETS"
echo ">> OK"
