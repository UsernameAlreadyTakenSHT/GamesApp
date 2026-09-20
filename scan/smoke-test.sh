#!/usr/bin/env bash
# Pushes the x86_64 Scan build + data to a running emulator and runs one Hub search.
# Usage (Git Bash): ./scan/smoke-test.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"
DEV=/data/local/tmp/scan

"$ADB" shell "mkdir -p $DEV/data"
"$ADB" push "$ROOT/app/src/main/jniLibs/x86_64/libscan.so" "$DEV/scan" >/dev/null
"$ADB" push "$ROOT/app/src/main/assets/scan/scan.ini" "$DEV/" >/dev/null
"$ADB" push "$ROOT/app/src/main/assets/scan/data/book" "$ROOT/app/src/main/assets/scan/data/eval" "$DEV/data/" >/dev/null
"$ADB" shell "cd $DEV && chmod 755 scan && (printf 'hub\nset-param name=book value=false\ninit\npos pos=Wbbbbbbbbbbbbbbbbbbbbeeeeeeeeeewwwwwwwwwwwwwwwwwwww\nlevel move-time=1\ngo think\n'; sleep 3; printf 'quit\n') | ./scan hub 2>&1" \
  | grep -E 'id |wait|ready|done|error|param name=(variant|threads|bb-size|book)' | head -10
