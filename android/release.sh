#!/usr/bin/env bash
# Builds a signed, non-debuggable release APK: dist/eyespy-release.apk
#
# First run creates your signing key in keystore/ (random password in keystore/pass.txt).
# BACK UP keystore/: app updates must be signed with the same key, or you have to uninstall
# and set the phones up again.
#
# If keystore/lineage.bin exists, the APK also carries a key rotation from the Android debug
# key, so it installs over earlier debug builds without wiping their data.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)"
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -z "$SDK" && -d "$HOME/Library/Android/sdk" ]] && SDK="$HOME/Library/Android/sdk"
[[ -z "$SDK" && -d "$HOME/Android/Sdk" ]] && SDK="$HOME/Android/Sdk"
[[ -d "$SDK" ]] || { echo "Android SDK not found: set ANDROID_HOME" >&2; exit 1; }
APKSIGNER="$(ls -d "$SDK"/build-tools/*/ | grep -v rc | sort -V | tail -1)apksigner"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

KS=keystore/eyespy-release.jks
PASS=keystore/pass.txt
if [[ ! -f "$KS" ]]; then
  mkdir -p keystore && chmod 700 keystore
  (umask 077; openssl rand -hex 24 > "$PASS")
  "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -storepass "$(cat "$PASS")" \
    -alias eyespy -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=eyeSpy" >/dev/null
  chmod 600 "$KS"
  echo "Created signing key in android/keystore/. Back it up."
fi
NEW=(--ks "$KS" --ks-pass "file:$PASS" --ks-key-alias eyespy)  # PKCS12: key password = store password

./gradlew -q assembleRelease
mkdir -p dist
UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk
if [[ -f keystore/lineage.bin ]]; then
  OLD=(--ks "$HOME/.android/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android)
  "$APKSIGNER" sign "${OLD[@]}" --next-signer "${NEW[@]}" --lineage keystore/lineage.bin \
    --rotation-min-sdk-version 30 --out dist/eyespy-release.apk "$UNSIGNED"
else
  "$APKSIGNER" sign "${NEW[@]}" --out dist/eyespy-release.apk "$UNSIGNED"
fi
"$APKSIGNER" verify dist/eyespy-release.apk
echo "built android/dist/eyespy-release.apk"
