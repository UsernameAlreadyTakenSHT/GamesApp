#!/usr/bin/env bash
# Builds the Marcher checkers engine (Collin Kees, github.com/Stermere/Checkers-Engine, MIT)
# for Android (x86_64 emulator + arm64-v8a phones) with the NDK and drops the binaries into
# app/src/main/jniLibs/<abi>/libmarcher.so.
#
# Usage (Git Bash):  ./marcher/build.sh [git ref]      (default: main)
#
# The engine has no command-line host of its own (it is driven from Python or wasm), so
# marcher/cli.c adds a small line protocol on stdin/stdout. The engine is one translation
# unit (board_search.c #includes the rest). PRINT_OUTPUT=0 keeps its search statistics off
# stdout, which carries the protocol.
set -euo pipefail

REF="${1:-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/marcher"
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
  git clone --depth 1 --branch "$REF" https://github.com/Stermere/Checkers-Engine.git "$SRC"
fi
cd "$WORK"

build() { # abi  triple  extra cflags...
  local abi=$1 triple=$2; shift 2
  echo ">> Build marcher $abi"
  "$TC/clang$EXT" --target="$triple" -O3 -DNDEBUG -DPRINT_OUTPUT=0 -w "$@" cli.c -o "marcher-$abi" -lm
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libmarcher.so" "marcher-$abi"
  ls -la "$OUT/$abi/libmarcher.so"
}

# SSE2 is part of x86_64, so the emulator build gets the engine's SIMD NNUE path;
# arm64 uses its scalar path.
build x86_64    x86_64-linux-android29  -mpopcnt
build arm64-v8a aarch64-linux-android29

# --- Endgame database (4 pieces, ~18 MB, ~4 MB compressed in the APK) --------------------
# db_gen.c is the engine's own generator (it reuses the search's move generation). It is
# built for x86_64 and run on a connected emulator, since the host may have no C compiler;
# shim/direct.h stands in for Windows' <direct.h>. The engine reads db/ from its working
# directory, which the app extracts from assets/marcher/.
ASSETS="$ROOT/app/src/main/assets/marcher"
if [ -z "${SKIP_DB:-}" ]; then
  ADB="${ADB:-$SDK/platform-tools/adb$EXT}"
  echo ">> Build and run the endgame database generator on the emulator"
  "$TC/clang$EXT" --target=x86_64-linux-android29 -O3 -DNDEBUG -DPRINT_OUTPUT=0 -w -mpopcnt -Ishim \
    src/src/engine/db_gen.c -o dbgen-x86_64 -lm
  MSYS_NO_PATHCONV=1 "$ADB" push dbgen-x86_64 /data/local/tmp/marcher-dbgen >/dev/null
  MSYS_NO_PATHCONV=1 "$ADB" shell "cd /data/local/tmp && chmod 755 marcher-dbgen && rm -rf db && ./marcher-dbgen 4 | tail -1"
  rm -rf "$ASSETS" && mkdir -p "$ASSETS"
  MSYS_NO_PATHCONV=1 "$ADB" pull /data/local/tmp/db "$ASSETS/" >/dev/null
  du -sh "$ASSETS/db"
fi
echo ">> OK"
