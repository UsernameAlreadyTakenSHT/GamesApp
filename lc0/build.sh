#!/usr/bin/env bash
# Builds Leela Chess Zero (lc0) for Android (x86_64 emulator + arm64-v8a phones) with the
# NDK, drops the binaries into app/src/main/jniLibs/<abi>/liblc0.so and downloads the
# networks into app/src/main/assets/nets/ (as .lc0: gzipped protobuf; not named .gz because
# AGP would silently un-gzip .gz assets).
#
# Usage (Git Bash):  ./lc0/build.sh [lc0_tag]      (default: v0.32.1)
#
# Toolchain notes:
#  - Meson is fetched as a tarball and run with the NDK's bundled Python (no system install).
#  - That Python has no SSL, so Meson wrap sources are pre-downloaded with curl into
#    subprojects/packagecache.
#  - Ninja comes from the SDK's CMake package.
#  - CPU backend = BLAS via the Eigen fallback (no OpenBLAS, no ISPC): ~200 nps on the
#    emulator with a 128x10 net, which is plenty for a phone opponent.
set -euo pipefail

TAG="${1:-v0.32.1}"
MESON_VER=1.6.1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/lc0"
SRC="$WORK/src"
OUT="$ROOT/app/src/main/jniLibs"
NETS="$ROOT/app/src/main/assets/nets"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK_DIR="$(ls -d "$SDK"/ndk/* | sort -V | tail -1)"
CMAKE_DIR="$(ls -d "$SDK"/cmake/* | sort -V | tail -1)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXT=.exe; CLANG_EXT=.cmd ;;
  Darwin*)              HOST=darwin-x86_64;  EXT=;     CLANG_EXT= ;;
  *)                    HOST=linux-x86_64;   EXT=;     CLANG_EXT= ;;
esac
TC="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/bin"
PY="$NDK_DIR/toolchains/llvm/prebuilt/$HOST/python3/python$EXT"
[ -x "$PY" ] || PY=python3
export NINJA="$CMAKE_DIR/bin/ninja$EXT"

echo ">> NDK: $NDK_DIR"

# --- Meson (tarball, no install) ------------------------------------------------------
if [ ! -f "$WORK/meson-$MESON_VER/meson.py" ]; then
  curl -sL -o "$WORK/meson.tar.gz" \
    "https://github.com/mesonbuild/meson/releases/download/$MESON_VER/meson-$MESON_VER.tar.gz"
  tar xzf "$WORK/meson.tar.gz" -C "$WORK" && rm "$WORK/meson.tar.gz"
fi
MESON="$PY $WORK/meson-$MESON_VER/meson.py"

# --- Sources --------------------------------------------------------------------------
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch "$TAG" --recurse-submodules https://github.com/LeelaChessZero/lc0.git "$SRC"
fi
cd "$SRC"

# Pre-fetch wrap archives (the NDK Python cannot download over https).
mkdir -p subprojects/packagecache
for w in abseil-cpp eigen zlib; do
  f="subprojects/$w.wrap"
  for key in source patch; do
    url=$(grep "^${key}_url" "$f" | sed 's/^[^=]*= *//' || true)
    fn=$(grep "^${key}_filename" "$f" | sed 's/^[^=]*= *//' || true)
    if [ -n "$url" ] && [ -n "$fn" ] && [ ! -f "subprojects/packagecache/$fn" ]; then
      curl -sL -o "subprojects/packagecache/$fn" "$url"
    fi
  done
done

# Android-friendly defaults used by lc0's own Android CI.
printf '#define DEFAULT_MAX_PREFETCH 0\n#define DEFAULT_TASK_WORKERS 0\n' > params_override.h

build() { # abi  cpu_family  triple  [extra meson options]
  local abi=$1 fam=$2 triple=$3; shift 3
  echo ">> Build lc0 $abi ($fam)"
  cat > "cross-$fam.txt" <<EOF
[host_machine]
system = 'android'
cpu_family = '$fam'
cpu = '$fam'
endian = 'little'

[properties]
cpp_link_args = ['-llog', '-static-libstdc++']

[binaries]
c = '$TC/$triple-clang$CLANG_EXT'
cpp = '$TC/$triple-clang++$CLANG_EXT'
ar = '$TC/llvm-ar$EXT'
strip = '$TC/llvm-strip$EXT'
ranlib = '$TC/llvm-ranlib$EXT'
EOF
  rm -rf "build-$fam"
  $MESON setup "build-$fam" --cross-file "cross-$fam.txt" --buildtype release \
    -Dgtest=false -Ddefault_library=static -Dblas=true -Dopenblas=false -Dmkl=false \
    -Ddnnl=false -Daccelerate=false -Dispc=false -Dembed=false -Dpext=false "$@" >/dev/null
  "$NINJA" -C "build-$fam" | grep -iE "error|FAILED" || true
  mkdir -p "$OUT/$abi"
  "$TC/llvm-strip$EXT" -o "$OUT/$abi/liblc0.so" "build-$fam/lc0"
  ls -la "$OUT/$abi/liblc0.so"
}

build x86_64    x86_64  x86_64-linux-android29  -Df16c=false
build arm64-v8a aarch64 aarch64-linux-android29

# --- Networks -------------------------------------------------------------------------
mkdir -p "$NETS"
fetch() { [ -f "$NETS/$1" ] || { echo ">> net $1"; curl -sL -o "$NETS/$1" "$2"; }; }
# Bad Gyal 8 (dkappe): small 128x10 net, good CPU strength.
fetch badgyal-8.lc0 "https://github.com/dkappe/leela-chess-weights/files/3799966/badgyal-8.pb.gz"
# T1 256x10 distilled (official lczero.org contrib net): stronger, slower on CPU.
fetch t1-256x10.lc0 "https://storage.lczero.org/files/networks-contrib/t1-256x10-distilled-swa-2432500.pb.gz"
# Maia (CSSLab): human-like play, one net per rating band.
for r in 1100 1200 1300 1400 1500 1600 1700 1800 1900; do
  fetch "maia-$r.lc0" "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-$r.pb.gz"
done
ls -la "$NETS"
echo ">> OK"
