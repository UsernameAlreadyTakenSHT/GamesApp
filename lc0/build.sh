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
  curl -fsSL -o "$WORK/meson.tar.gz" \
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
      curl -fsSL -o "subprojects/packagecache/$fn" "$url"
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
fetch() { # file url sha256: downloaded once, then verified on every build
  [ -s "$NETS/$1" ] || { echo ">> net $1"; curl -fsSL --retry 3 -o "$NETS/$1.part" "$2" && mv "$NETS/$1.part" "$NETS/$1"; }
  echo "$3  $NETS/$1" | sha256sum -c --quiet - || { echo "Checksum mismatch for $1: file removed" >&2; rm -f "$NETS/$1"; exit 1; }
}
# Bad Gyal 8 (dkappe): small 128x10 net, good CPU strength.
fetch badgyal-8.lc0 "https://github.com/dkappe/leela-chess-weights/files/3799966/badgyal-8.pb.gz" \
  ef0a7e4c977fce5123e3e1bbee422bfbab031a7aaad6e02d6e46aeb8d06f2295
# T1 256x10 distilled (official lczero.org contrib net): stronger, slower on CPU.
fetch t1-256x10.lc0 "https://storage.lczero.org/files/networks-contrib/t1-256x10-distilled-swa-2432500.pb.gz" \
  bc27a6cae8ad36f2b9a80a6ad9dabb0d6fda25b1e7f481a79bc359e14f563406
# Maia (CSSLab): human-like play, one net per rating band.
fetch maia-1100.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1100.pb.gz" \
  e1cf1cd0c96b8a4fa6a275f4b9fd54ed1ffebf9fe44641b9fceded310e9619c4
fetch maia-1200.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1200.pb.gz" \
  ead4ba953f233ae732999ebc1e2b675378148527ebcfad2f0acbc5e4c224d98e
fetch maia-1300.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1300.pb.gz" \
  36195f87bf4761834baa0bf87472b18509a7261a9d7d6f1a8443261369a733f2
fetch maia-1400.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1400.pb.gz" \
  d5353ea6766356dad2d28920c6692f37a5f30963767f1a3105d33b4d0af011e8
fetch maia-1500.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1500.pb.gz" \
  35ab6f20421d59e1df3b17c5a5016947af4c6761368ef84044a9a9c7619a9a00
fetch maia-1600.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1600.pb.gz" \
  d2c9e5948581acf4b9fc0b1e720c5dc0fe64ce80cfc4a239d3f8a42e1176c876
fetch maia-1700.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1700.pb.gz" \
  d277eacd792d340a30abb464dc65127254e65cac57abca17facc469889b96478
fetch maia-1800.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1800.pb.gz" \
  0031ad7c4256b1fd09fbebd28418d644d68b26cd2a45df4967ccf5c7ec9c4965
fetch maia-1900.lc0 "https://github.com/CSSLab/maia-chess/raw/master/maia_weights/maia-1900.pb.gz" \
  e2f565f42d7cd9f122557e6dc4eb84e5bbaedceda1d404dc485d3611c7c97a12
ls -la "$NETS"
echo ">> OK"
