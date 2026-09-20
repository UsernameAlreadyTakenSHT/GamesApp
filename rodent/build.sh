#!/usr/bin/env bash
# Builds Rodent V (Go) for Android (x86_64 emulator + arm64-v8a phones), drops the binaries
# into app/src/main/jniLibs/<abi>/librodent.so and copies its personalities, networks and
# opening books into app/src/main/assets/rodent/ (extracted at runtime as the engine's
# working directory).
#
# Usage (Git Bash):  ./rodent/build.sh [git ref]      (default: main)
#
# Notes:
#  - Go binaries are built for GOOS=linux with CGO disabled: fully static, they run on
#    Android without touching bionic.
#  - `-R 0x4000` aligns ELF segments to 16 KB: with Go's default 4 KB alignment the tail of
#    the text segment shares a page with read-only data on 16 KB-page kernels (the x86_64
#    emulator, recent arm64 phones) and faults with SIGSEGV at pc.
#  - nnue_avx2_stub.go adds the missing non-amd64 stubs for the AVX2 kernels (upstream only
#    builds on amd64); zz_noavx2_emulator.go forces the scalar path on x86_64 because the
#    hand-written AVX2 kernels fault inside the emulator.
#  - The default network is embedded in the binary; the two extra networks referenced by the
#    personalities (v2 and Tal) and the opening books are shipped as assets.
set -euo pipefail

REF="${1:-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/rodent"
SRC="$WORK/src"
OUT="$ROOT/app/src/main/jniLibs"
ASSETS="$ROOT/app/src/main/assets/rodent"

GO="${GO:-$(command -v go || echo "$HOME/go-sdk/go/bin/go")}"
"$GO" version

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$REF" https://github.com/nescitus/Rodent-V.git "$SRC"
fi
cd "$SRC"
cp "$WORK/nnue_avx2_stub.go" .

build() { # abi  goarch  [extra env]
  local abi=$1 goarch=$2; shift 2
  echo ">> Build rodent $abi"
  env CGO_ENABLED=0 GOOS=linux GOARCH="$goarch" "$@" "$GO" build -ldflags="-s -w -R 0x4000" -o "rodent-$abi" .
  mkdir -p "$OUT/$abi"
  cp "rodent-$abi" "$OUT/$abi/librodent.so"
  ls -la "$OUT/$abi/librodent.so"
}

cp "$WORK/zz_noavx2_emulator.go" .
build x86_64 amd64 GOAMD64=v2
rm -f zz_noavx2_emulator.go
build arm64-v8a arm64

# --- Data files -----------------------------------------------------------------------
rm -rf "$ASSETS"
mkdir -p "$ASSETS/nets" "$ASSETS/books"
mkdir -p "$ASSETS/personalities" && cp personalities/*.txt "$ASSETS/personalities/"
cp nets/rodent_4kb_768hl_8ob_v2.bin nets/rodent_4kb_768hl_tal_aggressive.bin "$ASSETS/nets/"
cp books/*.bin "$ASSETS/books/"
du -sh "$ASSETS"
echo ">> OK"
