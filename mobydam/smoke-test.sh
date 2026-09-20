#!/usr/bin/env bash
# Pushes the x86_64 Moby Dam build + data to a running emulator and runs one Hub search.
# Usage (Git Bash): ./mobydam/smoke-test.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"
DEV=/data/local/tmp/mobydam

"$ADB" shell "mkdir -p $DEV"
"$ADB" push "$ROOT/app/src/main/jniLibs/x86_64/libmobydam.so" "$DEV/mobydam" >/dev/null
"$ADB" push "$ROOT"/app/src/main/assets/mobydam/eval-ph0 "$ROOT"/app/src/main/assets/mobydam/eval-ph1 \
  "$ROOT"/app/src/main/assets/mobydam/eval-ph2 "$ROOT"/app/src/main/assets/mobydam/eval-ph3 \
  "$ROOT"/app/src/main/assets/mobydam/book.opn "$DEV/" >/dev/null
"$ADB" shell "cd $DEV && chmod 755 mobydam && (printf 'hub\ninit\npos pos=Wbbbbbbbbbbbbbbbbbbbbeeeeeeeeeewwwwwwwwwwwwwwwwwwww\nlevel move-time=1\ngo think\n'; sleep 3; printf 'quit\n') | ./mobydam -t 20 hub 2>&1" \
  | grep -E 'id |wait|ready|done|error|param|pong' | head -12
