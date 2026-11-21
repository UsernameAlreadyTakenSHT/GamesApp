#!/usr/bin/env bash
# Builds Gaia (github.com/jromang/gaiachess, Rust, GPL-3.0-or-later) for Android (x86_64
# emulator + arm64-v8a phones) and drops the binaries into
# app/src/main/jniLibs/<abi>/libgaia.so.
#
# Usage (Git Bash):  ./gaia/build.sh [tag]      (default: v4.4.4)
#
# Requirements: rustup as for Reckless (see reckless/build.sh), with Rust 1.98 or later, plus
# a host C compiler (gcc) for the zstd crate that Gaia's build script uses.
#
# The network (GaiaNet-T7, 113 MB) is downloaded below, checked against its SHA-256 and
# embedded in the binary by Gaia's build script, zstd-compressed: the engine unpacks it in
# memory when it starts. Only the `nnue` feature is built:
#   - no `gui`: Gaia's own board (macroquad, audio) has no use inside the app;
#   - no `online-tb`: the app is offline;
#   - no `syzygy` / `nalimov`: no tablebase files are shipped (and Syzygy needs a host clang);
#   - no `gaiatb`: the embedded 3-4 piece tablebases would add ~30 MB per ABI.
set -euo pipefail

TAG="${1:-v4.4.4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/gaia/src"
NETS="$ROOT/gaia/nets"
OUT="$ROOT/app/src/main/jniLibs"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe; CLANG_EXT=.cmd; TOOLCHAIN=stable-x86_64-pc-windows-gnu ;;
  Darwin*)              HOST=darwin-x86_64;  EXT=;     CLANG_EXT=;     TOOLCHAIN=stable ;;
  *)                    HOST=linux-x86_64;   EXT=;     CLANG_EXT=;     TOOLCHAIN=stable ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"
export PATH="$HOME/.cargo/bin:$PATH"

rustup toolchain install "$TOOLCHAIN" --profile minimal >/dev/null
rustup target add --toolchain "$TOOLCHAIN" x86_64-linux-android aarch64-linux-android >/dev/null

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$TAG" https://github.com/jromang/gaiachess.git "$SRC"
fi

# The network named by DEFAULT_NET in Gaia's defaults.conf, from the authors' Hugging Face
# repository: downloaded once, then verified on every build.
NET="$NETS/gaianet-t7.bin"
NET_SHA=935faf29e28de460c69f85faf37afa7294ec12e98c417342b018454cf09de85d
mkdir -p "$NETS"
[ -s "$NET" ] || {
  echo ">> Download gaianet-t7.bin"
  curl -fsSL --retry 3 -o "$NET.part" \
    "https://huggingface.co/jromanghf/gaiachess-networks/resolve/main/gaianet-t7.bin" && mv "$NET.part" "$NET"
}
echo "$NET_SHA  $NET" | sha256sum -c --quiet - || { echo "Checksum mismatch for gaianet-t7.bin: file removed" >&2; rm -f "$NET"; exit 1; }

cd "$SRC"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TC/x86_64-linux-android29-clang$CLANG_EXT"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/aarch64-linux-android29-clang$CLANG_EXT"
# Cargo's build script reads the network from this path (a Windows path under Git Bash).
MODEL="$(cygpath -m "$NET" 2>/dev/null || echo "$NET")"
export MODEL

build() { # abi  target  rustflags
  local abi=$1 target=$2 flags=$3
  echo ">> Build gaia $abi"
  RUSTFLAGS="$flags" cargo "+$TOOLCHAIN" build --release --no-default-features --features nnue --target "$target" 2>&1 \
    | grep -E "^error|^NNUE|warning: unused" || true
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libgaia.so" "target/$target/release/gaiachess"
  ls -la "$OUT/$abi/libgaia.so"
}

# x86_64 is only ever the emulator, whose host CPU has AVX2 in practice; gaia_dist lets the
# engine pick its SIMD paths at startup, as in Gaia's own release binaries.
build x86_64    x86_64-linux-android  "-C target-cpu=x86-64-v3 --cfg gaia_dist"
build arm64-v8a aarch64-linux-android ""
echo ">> OK"
