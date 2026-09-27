#!/usr/bin/env bash
# Fetches OpenTafl (Java) and copies its engine core into opentafl/java/, which the app
# compiles as an extra source set (see app/build.gradle.kts). OpenTafl runs in-process on
# Android: its rules classes referee the game and AiWorkspace is the opponent.
#
# Usage (Git Bash):  ./opentafl/build.sh [git ref]      (default: the pinned commit below)
#
# Only the engine, rules and notation packages (plus Log) are used; the handful of terminal
# UI classes they reference are replaced by the stubs in opentafl/stubs/.
set -euo pipefail

REF="${1:-6895051d5a3b32612d12e93e2cd4584d23f94bb3}"   # pinned commit; pass a ref to override
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/opentafl/src"
OUT="$ROOT/opentafl/java"
PKG="com/manywords/softworks/tafl"

if [ ! -d "$SRC/.git" ]; then
  # Exact commit (pinned below), so a moved branch or a compromised upstream cannot change the build.
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 https://github.com/jslater89/OpenTafl.git "$REF"
  git -C "$SRC" checkout -q FETCH_HEAD
fi

rm -rf "$OUT"
mkdir -p "$OUT/$PKG"
cp -r "$SRC/src/$PKG/engine" "$SRC/src/$PKG/rules" "$SRC/src/$PKG/notation" "$OUT/$PKG/"
cp "$SRC/src/$PKG/Log.java" "$OUT/$PKG/"
# The PlayTaflOnline importer needs javax.json and is not used by the app.
rm -rf "$OUT/$PKG/notation/playtaflonline"
# Utilities has two desktop-clipboard helpers on java.awt (absent on Android); drop them.
# (The sources have CRLF line endings, hence the optional \r before the closing brace.)
sed -i -e '/^import java\.awt/d' \
       -e '/public static void pushToClipboard/,/^    }\r\{0,1\}$/d' \
       -e '/public static String getFromClipboard/,/^    }\r\{0,1\}$/d' \
       "$OUT/$PKG/engine/Utilities.java"
cp "$SRC/LICENSE.txt" "$OUT/LICENSE-OpenTafl.txt"
(cd "$SRC" && git rev-parse --short HEAD) > "$OUT/VERSION"

echo ">> OpenTafl core copied to opentafl/java ($(find "$OUT" -name '*.java' | wc -l) files, $(cat "$OUT/VERSION"))"
