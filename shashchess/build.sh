#!/usr/bin/env bash
# Builds ShashChess (a Stockfish derivative built on Shashin's theory) for Android
# (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libshashchess.so.
#
# Usage (Git Bash):  ./shashchess/build.sh [git ref]      (default: the pinned commit below)
# The NNUE network is downloaded by the ShashChess Makefile and embedded (~100 MB per ABI).
# With COMP=ndk the Makefile sets OS=Android, which leaves the LiveBook code (libcurl, online
# opening books and tablebases) out of the binary: the engine has no network code at all.
set -euo pipefail

REF="${1:-076bcfc1ffc6267601fdf933e78e34782a29eefb}"   # release 42.3; pass a ref to override
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/shashchess/src"
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
  # Exact commit (pinned above), so a moved tag or a compromised upstream cannot change the build.
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 https://github.com/amchess/ShashChess.git "$REF"
  git -C "$SRC" checkout -q FETCH_HEAD
fi

export PATH="$TC:$PATH"
cd "$SRC/src"

# memory.cpp (inherited from Stockfish 19) declares its large-page table for
# "linux && !android" but uses it under "linux && MAP_HUGE_SHIFT && x86_64", which recent NDK
# headers also satisfy on Android x86_64: keep the 1 GB huge-page path off Android.
sed -i 's/^\( *#if defined(__linux__) && defined(MAP_HUGE_SHIFT) && defined(__x86_64__)\)\r\{0,1\}$/\1 \&\& !defined(__ANDROID__)/' memory.cpp
grep -q 'defined(MAP_HUGE_SHIFT) && defined(__x86_64__) && !defined(__ANDROID__)' memory.cpp

build() { # abi  arch  triple
  local abi=$1 arch=$2 triple=$3
  echo ">> Build $abi ($arch)"
  "$MAKE" clean >/dev/null 2>&1 || true
  "$MAKE" -j"$(nproc 2>/dev/null || echo 4)" build ARCH="$arch" COMP=ndk \
      COMPCXX="$TC/clang++$EXT --target=$triple" 2>&1 | grep -v "^$TC" | tail -3
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libshashchess.so" shashchess
  ls -la "$OUT/$abi/libshashchess.so"
}

build x86_64    x86-64-sse41-popcnt x86_64-linux-android29
build arm64-v8a armv8               aarch64-linux-android29
"$MAKE" clean >/dev/null 2>&1 || true

echo ">> OK"
