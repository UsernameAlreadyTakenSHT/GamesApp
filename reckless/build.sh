#!/usr/bin/env bash
# Builds Reckless (Rust) for Android (x86_64 emulator + arm64-v8a phones) and drops the
# binaries into app/src/main/jniLibs/<abi>/libreckless.so. The NNUE network is downloaded
# by Reckless' own build script and embedded in the binary.
#
# Usage (Git Bash):  ./reckless/build.sh [tag]      (default: v0.9.0)
#
# Requirements: rustup with the `stable-x86_64-pc-windows-gnu` toolchain on Windows (its
# self-contained MinGW linker builds Cargo build scripts without Visual Studio), plus the
# `x86_64-linux-android` and `aarch64-linux-android` targets. Syzygy support is disabled
# because it needs bindgen/libclang.
set -euo pipefail

TAG="${1:-v0.9.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/reckless/src"
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
  git clone --depth 1 --branch "$TAG" https://github.com/codedeliveryservice/Reckless.git "$SRC"
fi
cd "$SRC"

export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TC/x86_64-linux-android29-clang$CLANG_EXT"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/aarch64-linux-android29-clang$CLANG_EXT"

build() { # abi  target  rustflags
  local abi=$1 target=$2 flags=$3
  echo ">> Build reckless $abi"
  RUSTFLAGS="$flags" cargo "+$TOOLCHAIN" build --release --no-default-features --target "$target" 2>&1 \
    | grep -E "error|warning: unused" || true
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libreckless.so" "target/$target/release/reckless"
  ls -la "$OUT/$abi/libreckless.so"
}

# x86_64 is only ever the emulator, whose host CPU has AVX2 in practice.
build x86_64    x86_64-linux-android  "-C target-cpu=x86-64-v3"
build arm64-v8a aarch64-linux-android ""
echo ">> OK"
