#!/usr/bin/env bash
# Signs ChargeWatch with the private release key and a proof of key rotation.
#
# Usage: sign.sh <unsigned.apk> <out.apk> <oldkey-test.apk>
#
# Releases up to 1.0.25 were signed with a debug key whose file and password were public in this
# repo. Anyone could sign a modified copy with it, and Android would accept that copy as an update.
# Since then, releases carry two signatures:
#   - APK Signature Scheme v3 with the private release key, plus a lineage (proof of rotation)
#     signed by the retired key. Android 9 and newer accepts this as an update over the old app,
#     moves the app to the new key, and from then on refuses anything signed only with the old key
#     (the retired key keeps no "rollback" right in the lineage).
#   - v1 and v2 with the retired key, for Android 8.0 and 8.1, which do not read v3.
#
# The keystore (one PKCS12 file holding "release" and "retired") and its password come from the
# repository secrets CHARGEWATCH_KEYSTORE (base64) and CHARGEWATCH_KEYSTORE_PASSWORD. Without them
# (forks, pull requests, or before the secrets are set) the same steps run with throwaway keys, so
# the signing and the rotation test still run, and the build is marked as not releasable.
#
# The third file is the same app signed only with the retired key: what installed copies were
# signed with, and what a modified copy would be signed with. The emulator test installs it, updates
# it with the release, then checks that it can no longer replace the release.
set -euo pipefail

in=$1
out=$2
oldkey=$3

# Public fingerprints of the two certificates (SHA-256 of each certificate). A release must be
# signed with exactly these. They are also listed in the README, for anyone checking a download.
RELEASE_CERT_SHA256=55ae2f76712ae8fb9525bf09ef77acff3ab7f14051e42638b60005ea51cbf5b5
RETIRED_CERT_SHA256=8131bb479c1b816cc8a5adb70b6908b837ef0e33955fef7ecb1fad3a4e27f44b

tools="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
apksigner="$tools/apksigner"
work="${RUNNER_TEMP:-$(mktemp -d)}/signing"
rm -rf "$work"
mkdir -p "$work"
chmod 700 "$work"
ks="$work/keys.p12"
lineage="$work/rotation.lineage"

if [ -n "${CHARGEWATCH_KEYSTORE:-}" ] && [ -n "${CHARGEWATCH_KEYSTORE_PASSWORD:-}" ]; then
  mode=release
  printf '%s' "$CHARGEWATCH_KEYSTORE" | tr -d ' \r\n' | base64 -d > "$ks"
  export KS_PASS="$CHARGEWATCH_KEYSTORE_PASSWORD"
else
  mode=test
  echo "::warning::The signing secrets are not set. This build is signed with throwaway keys and will not be released."
  KS_PASS="$(openssl rand -hex 24)"
  export KS_PASS
  for alias in retired release; do
    keytool -genkeypair -keystore "$ks" -storetype PKCS12 -storepass:env KS_PASS -keypass:env KS_PASS \
      -alias "$alias" -keyalg RSA -keysize 2048 -validity 365 -dname "CN=ChargeWatch test $alias" 2>&1 | grep -v JAVA_TOOL || true
  done
fi

signer() { echo --ks "$ks" --ks-type PKCS12 --ks-key-alias "$1" --ks-pass env:KS_PASS; }
digest() {
  keytool -list -v -keystore "$ks" -storetype PKCS12 -storepass:env KS_PASS -alias "$1" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f'
}
release_sha=$(digest release)
retired_sha=$(digest retired)
[ -n "$release_sha" ] && [ -n "$retired_sha" ] || { echo "::error::The keystore must hold the keys \"release\" and \"retired\""; exit 1; }
if [ "$mode" = release ] && { [ "$release_sha" != "$RELEASE_CERT_SHA256" ] || [ "$retired_sha" != "$RETIRED_CERT_SHA256" ]; }; then
  echo "::error::CHARGEWATCH_KEYSTORE is not the ChargeWatch release keystore (fingerprints do not match the pins)"
  exit 1
fi

# The proof of rotation: the retired key vouches for the release key. It keeps the right to the
# app's installed data (so the update keeps everything) and nothing else, in particular no rollback.
"$apksigner" rotate --out "$lineage" \
  --old-signer $(signer retired) \
    --set-installed-data true --set-shared-uid false --set-permission false --set-rollback false --set-auth false \
  --new-signer $(signer release)

"$apksigner" sign --out "$out" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --lineage "$lineage" --rotation-min-sdk-version 28 \
  $(signer retired) --next-signer $(signer release) \
  "$in"

"$apksigner" sign --out "$oldkey" --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  $(signer retired) "$in"

fail() { echo "::error::$1"; exit 1; }
echo "--- schemes (Android 8.0 and up)"
"$apksigner" verify --verbose --min-sdk-version 26 "$out" > "$work/v26.txt" || { cat "$work/v26.txt"; fail "The APK does not verify"; }
grep -E "^Verified|^Number of signers|WARNING|ERROR" "$work/v26.txt" || true
grep -q "Verified using v3 scheme (APK Signature Scheme v3): true" "$work/v26.txt" || fail "The APK has no v3 signature"

echo "--- signer on Android 9 and up"
"$apksigner" verify --print-certs --min-sdk-version 28 "$out" > "$work/v28.txt"
grep "certificate" "$work/v28.txt" || true
grep -q "$release_sha" "$work/v28.txt" || fail "Android 9 and up would not see the release key"

echo "--- proof of rotation"
"$apksigner" lineage --in "$out" --print-certs > "$work/lineage.txt"
cat "$work/lineage.txt"
grep -q "$retired_sha" "$work/lineage.txt" && grep -q "$release_sha" "$work/lineage.txt" \
  || fail "The APK's lineage does not lead from the retired key to the release key"

echo "--- old-key copy (rotation test only)"
"$apksigner" verify --print-certs --min-sdk-version 26 "$oldkey" > "$work/old.txt"
grep -q "$retired_sha" "$work/old.txt" && ! grep -q "$release_sha" "$work/old.txt" || fail "The old-key test copy is signed wrongly"

rm -rf "$work"
echo "Signed with the $mode keys. Release certificate SHA-256: $release_sha"
echo "mode=$mode" >> "${GITHUB_OUTPUT:-/dev/null}"
echo "release_sha=$release_sha" >> "${GITHUB_OUTPUT:-/dev/null}"
