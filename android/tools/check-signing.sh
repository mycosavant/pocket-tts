#!/usr/bin/env bash
#
# Fails if a built APK is not signed by this repository's committed debug key.
#
# Android identifies an app by its signing certificate, so a build signed by a
# different key cannot be installed over the previous one and cannot open data
# the previous one kept. Before app/debug.keystore was committed, the Android
# plugin generated a fresh key on whatever machine happened to be building -
# and a CI runner is a fresh machine every time. Every published APK therefore
# had a different identity, every sideload was an uninstall, and every
# uninstall threw away a 98 MB model.
#
# That is invisible in source: the build file said "sign with the debug config"
# both before and after, and nothing about the failure appears until a phone
# refuses the install. So this reads the artefact, the way the JNI check does.
#
# If this fails after a deliberate key change, update EXPECTED below - and know
# that everyone with the old build installed has to uninstall to take the new
# one.
#
#   tools/check-signing.sh app/build/outputs/apk/release/app-release.apk
#
set -euo pipefail

apk="${1:?usage: check-signing.sh <apk>}"
[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 2; }

# SHA-256 of the certificate in app/debug.keystore, lower case and unseparated,
# which is how apksigner prints it. keytool -list prints the same bytes as
# colon-separated upper case.
EXPECTED="f78633b83722e0e89104fcdb745413d1442a7b65a313cd5f6afacaab6204fc9c"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
apksigner="$(ls -1 "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
[ -x "${apksigner:-}" ] || { echo "apksigner not found under $sdk/build-tools" >&2; exit 2; }

# --print-certs verifies as well as prints, so a corrupt or unsigned APK fails
# here rather than reaching the comparison with an empty digest.
if ! certs="$("$apksigner" verify --print-certs "$apk" 2>&1)"; then
    echo "FAIL: $(basename "$apk") did not verify" >&2
    printf '%s\n' "$certs" >&2
    exit 1
fi

# Matched on the part of the line that every apksigner agrees on. How it
# labels a signer is not stable across build-tools releases - 35 writes
# "Signer #1 certificate SHA-256 digest:", 36 writes "V2 Signer: certificate
# SHA-256 digest:" and names each signature scheme separately - and pinning one
# of those spellings made this fail on a runner against a correctly signed APK,
# which is the worst way for a check to be wrong.
#
# Every scheme signs with the same certificate here, so they collapse to one
# digest; if a future key rotation ever made them differ, each is compared and
# the odd one out is what gets reported.
digests="$(
    printf '%s\n' "$certs" \
        | sed -n 's/.*certificate SHA-256 digest: *\([0-9a-fA-F]\{64\}\).*/\1/p' \
        | tr 'A-Z' 'a-z' \
        | sort -u
)"

if [ -z "$digests" ]; then
    echo "FAIL: $(basename "$apk") reports no signer certificate" >&2
    printf '%s\n' "$certs" >&2
    exit 1
fi

unexpected="$(printf '%s\n' "$digests" | grep -v -x "$EXPECTED" || true)"
if [ -n "$unexpected" ]; then
    echo "FAIL: $(basename "$apk") is signed by an unexpected key" >&2
    echo "      expected $EXPECTED" >&2
    printf '      actual   %s\n' $unexpected >&2
    echo "      This build cannot be installed over any other, and a phone that" >&2
    echo "      kept its app data will refuse it outright. Check that" >&2
    echo "      app/debug.keystore is present and that signingConfigs points at it." >&2
    exit 1
fi

echo "OK: $(basename "$apk") is signed by the committed debug key ($EXPECTED)"
