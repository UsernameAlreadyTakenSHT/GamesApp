#!/usr/bin/env bash
# Builds Berserk (C) for Android (x86_64 emulator + arm64-v8a phones) and drops the binaries
# into app/src/main/jniLibs/<abi>/libberserk.so. The NNUE network named in src/makefile is
# downloaded and embedded (incbin).
#
# Usage (Git Bash):  ./berserk/build.sh [git ref]      (default: the pinned commit below)
# The engine has NEON and AVX2 code paths, so a single clang invocation per ABI is enough.
# pthread_create is wrapped (bigstack.c) to enforce a 64 MB thread stack on Bionic.
set -euo pipefail

REF="${1:-8ae895a6151695be4a50d4fb65b0c131659c513a}"   # release 14; pass a ref to override
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
  # Exact commit (pinned below), so a moved branch or a compromised upstream cannot change the build.
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 https://github.com/jhonnold/berserk.git "$REF"
  git -C "$SRC" checkout -q FETCH_HEAD
fi
cd "$SRC/src"
cp "$WORK/bigstack.c" .

NET="$(grep '^MAIN_NETWORK' makefile | sed 's/.*= *//')"
VERSION="$(grep '^VERSION' makefile | sed 's/.*= *//')"
[ -f "$NET" ] || curl -fsSL -o "$NET" \
  "https://github.com/jhonnold/berserk-networks/releases/download/networks/$NET"

# The source list as the makefile has it (its SRC variable, continued over several lines):
# it changes between versions.
SRCS="$(awk '/^SRC[[:space:]]*=/ { on = 1; sub(/^SRC[[:space:]]*=/, "") }
  on { line = $0; more = sub(/\\[[:space:]]*$/, "", line); printf "%s ", line; if (!more) exit }' makefile)"

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
