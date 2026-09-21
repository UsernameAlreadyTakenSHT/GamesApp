#!/usr/bin/env bash
# Builds the Sanmill engine (the Rust "tgf" console engine of the Sanmill Nine Men's Morris
# app) for Android (x86_64 emulator + arm64-v8a phones) and drops the binaries into
# app/src/main/jniLibs/<abi>/libsanmill.so.
#
# Usage (Git Bash):  ./sanmill/build.sh [git ref]      (default: master)
#
# Requirements: rustup, the NDK, and on Windows the `*-x86_64-pc-windows-gnu` toolchain
# (its bundled MinGW linker builds Cargo build scripts without Visual Studio). The engine's
# C dependencies (bundled SQLite, zstd) are compiled with the NDK clang via the `cc` crate.
# The perfect-play database support is left out (default features off in tgf-cli).
set -euo pipefail

REF="${1:-master}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/sanmill/src"
OUT="$ROOT/app/src/main/jniLibs"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe; CLANG_EXT=.cmd; HOST_TRIPLE=x86_64-pc-windows-gnu ;;
  Darwin*)              HOST=darwin-x86_64;  EXT=;     CLANG_EXT=;     HOST_TRIPLE= ;;
  *)                    HOST=linux-x86_64;   EXT=;     CLANG_EXT=;     HOST_TRIPLE= ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"
export PATH="$HOME/.cargo/bin:$PATH"

if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$REF" https://github.com/calcitem/Sanmill.git "$SRC"
fi
cd "$SRC"

# The repository pins its compiler in rust-toolchain.toml; use that version with the GNU
# host on Windows.
CHANNEL="$(sed -n 's/^channel *= *"\([^"]*\)".*/\1/p' rust-toolchain.toml)"
TOOLCHAIN="$CHANNEL${HOST_TRIPLE:+-$HOST_TRIPLE}"
rustup toolchain install "$TOOLCHAIN" --profile minimal >/dev/null
[ -n "$HOST_TRIPLE" ] && rustup component add --toolchain "$TOOLCHAIN" rust-mingw >/dev/null
rustup target add --toolchain "$TOOLCHAIN" x86_64-linux-android aarch64-linux-android >/dev/null
# Host build scripts (cc -> jobserver -> getrandom) use raw-dylib imports, which the GNU
# toolchain's dlltool cannot handle without a GNU assembler; route rustc to LLVM's dlltool
# from the NDK instead (see rustc-dlltool.cmd).
if [ -n "$HOST_TRIPLE" ]; then
  export SANMILL_DLLTOOL="$(cygpath -w "$TC/llvm-dlltool.exe")"
  export RUSTC_WRAPPER="$(cygpath -w "$ROOT/sanmill/rustc-dlltool.cmd")"
fi

export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TC/x86_64-linux-android29-clang$CLANG_EXT"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TC/aarch64-linux-android29-clang$CLANG_EXT"
# C code in dependencies (SQLite, zstd) must be compiled by the NDK too.
export CC_x86_64_linux_android="$TC/x86_64-linux-android29-clang$CLANG_EXT"
export CC_aarch64_linux_android="$TC/aarch64-linux-android29-clang$CLANG_EXT"
export AR_x86_64_linux_android="$TC/llvm-ar$EXT"
export AR_aarch64_linux_android="$TC/llvm-ar$EXT"

build() { # abi  target  rustflags
  local abi=$1 target=$2 flags=$3
  echo ">> Build sanmill $abi"
  RUSTFLAGS="$flags" cargo "+$TOOLCHAIN" build --release -p tgf-cli --target "$target" 2>&1 \
    | grep -E "^error|error\[" || true
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/libsanmill.so" "target/$target/release/tgf"
  ls -la "$OUT/$abi/libsanmill.so"
}

build x86_64    x86_64-linux-android  "-C target-cpu=x86-64-v2"
build arm64-v8a aarch64-linux-android ""
echo ">> OK"
