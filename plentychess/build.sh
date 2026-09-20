#!/usr/bin/env bash
# Builds PlentyChess (C++) for Android (x86_64 emulator + arm64-v8a phones) and drops the
# binaries into app/src/main/jniLibs/<abi>/libplenty.so.
#
# Usage (Git Bash):  ./plentychess/build.sh [tag]      (default: b-v8.0.0)
#
# Two Android-specific twists:
#  - PlentyChess pre-processes its network with a host tool (tools/process_net) whose output
#    layout depends on the SIMD flags it was compiled with. There is no host C++ compiler
#    here, so the tool is compiled for Android x86_64 and RUN ON THE EMULATOR (adb), once
#    with AVX2 flags (x86_64 engine) and once with SSSE3 flags (same layout as NEON/arm64).
#    An x86_64 emulator with AVX2 must therefore be running (`adb devices`).
#  - Bionic gives threads a 1 MB stack, far too small for the engine's recursion, so
#    pthread_create is wrapped (bigstack.c, -Wl,--wrap) to enforce 64 MB.
set -euo pipefail

TAG="${1:-b-v8.0.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/plentychess"
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
ADB="$SDK/platform-tools/adb$EXT"
export MSYS_NO_PATHCONV=1

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/Yoshie2000/PlentyChess.git "$SRC"
fi
cd "$SRC"
cp "$WORK/bigstack.c" .

NET="$(cat network.txt)"
[ -f "$NET.bin" ] || curl -sL -o "$NET.bin" \
  "https://github.com/Yoshie2000/PlentyNetworks/releases/download/$NET/$NET.bin"

# --- Network pre-processing on the emulator ------------------------------------------
process_net() { # name  x86 flags for the tool
  local name=$1; shift
  [ -f "$name" ] && return
  echo ">> process_net -> $name"
  "$TC/clang++$EXT" --target=x86_64-linux-android29 -std=c++17 -O2 -w -DARCH_X86 "$@" \
    -static-libstdc++ tools/process_net.cpp -o process_net_tool
  "$ADB" push process_net_tool /data/local/tmp/process_net >/dev/null
  "$ADB" push "$NET.bin" /data/local/tmp/ >/dev/null
  "$ADB" shell "cd /data/local/tmp && chmod 755 process_net && ./process_net false $NET.bin out.bin"
  "$ADB" pull /data/local/tmp/out.bin "$name" >/dev/null
}
process_net processed-avx2.bin  -march=haswell
process_net processed-neon.bin  -mssse3

# --- Engine ---------------------------------------------------------------------------
CPP_SRCS="$(grep '^SOURCES' Makefile | sed 's/SOURCES = //' | tr ' ' '\n' | grep -v '\.c$' | tr '\n' ' ')"

build() { # abi  triple  evalfile  cxxflags...
  local abi=$1 triple=$2 evalfile=$3; shift 3
  echo ">> Build plentychess $abi"
  "$TC/clang$EXT" --target="$triple" -w -O3 -c src/fathom/src/tbprobe.c -o tbprobe.o
  "$TC/clang$EXT" --target="$triple" -O2 -c bigstack.c -o bigstack.o
  "$TC/clang++$EXT" --target="$triple" -std=c++17 -w -fcommon -pthread -O3 -DNDEBUG "$@" \
    "-DEVALFILE=\"$evalfile\"" -static-libstdc++ -Wl,--wrap=pthread_create \
    $CPP_SRCS tbprobe.o bigstack.o -o engine-$abi
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libplenty.so" "engine-$abi"
  ls -la "$OUT/$abi/libplenty.so"
}

build x86_64    x86_64-linux-android29  processed-avx2.bin -DARCH_X86 -march=haswell
build arm64-v8a aarch64-linux-android29 processed-neon.bin -DARCH_ARM -march=armv8-a+simd
echo ">> OK"
