#!/usr/bin/env bash
#
# Fails if the streaming audio callback is missing from a built APK.
#
# sherpa-onnx resolves that callback from native code by name and exact
# signature - GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;") - so
# nothing in Kotlin references the descriptor and nothing in Kotlin breaks
# when it disappears. It has disappeared twice, by two different mechanisms:
# D8 desugaring a lambda into a class carrying only the erased bridge, and R8
# inlining the specialised method into that bridge. Both compiled cleanly and
# passed every test.
#
# The only place the answer exists is the built artefact, so that is what this
# reads. Usage:
#
#   tools/check-jni-callback.sh app/build/outputs/apk/release/app-release.apk
#
set -euo pipefail

apk="${1:?usage: check-jni-callback.sh <apk>}"
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 2; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
dexdump="$(ls -1 "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)"
[ -x "${dexdump:-}" ] || { echo "dexdump not found under $sdk/build-tools" >&2; exit 2; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$work"

# The declaration line, not a call site: a call site can survive in one dex
# while the method it names is gone from all of them.
declaration="type[[:space:]]*:[[:space:]]*'\(\[F\)Ljava/lang/Integer;'"

found=0
for dex in "$work"/classes*.dex; do
    n="$("$dexdump" -d "$dex" 2>/dev/null | grep -cE "$declaration" || true)"
    found=$((found + n))
done

if [ "$found" -eq 0 ]; then
    echo "FAIL: $(basename "$apk") declares no invoke([F)Ljava/lang/Integer;" >&2
    echo "      sherpa-onnx cannot resolve its audio callback, so this build" >&2
    echo "      synthesises silently and then fails. Check proguard-rules.pro." >&2
    exit 1
fi

echo "OK: $(basename "$apk") declares invoke([F)Ljava/lang/Integer; ($found)"
