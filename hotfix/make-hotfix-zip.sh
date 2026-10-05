#!/bin/bash
#
# Builds the recovery-flashable Updater hotfix for Lunaris peridot builds signed with the AOSP
# test keys (up to 2026-09-05). Run after `m Updater` in a tree that builds with the current
# release keys:
#
#   packages/apps/Updater/hotfix/make-hotfix-zip.sh [out.zip]
#
# The zip carries the Updater re-signed with the AOSP platform test key (the platform key of
# those builds) and an otacerts.zip trusting both the AOSP test key and the current release
# key. It is signed with the AOSP test key, so the old builds' recovery accepts it.

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
TOP=$(cd "$HERE/../../../.." && pwd)
PRODUCT_OUT=${ANDROID_PRODUCT_OUT:-$TOP/out/target/product/peridot}
HOST_OUT=$TOP/out/host/linux-x86
JAVA=$TOP/prebuilts/jdk/jdk21/linux-x86/bin/java
SECURITY=$TOP/build/make/target/product/security

SRC_APK=$PRODUCT_OUT/system_ext/priv-app/Updater/Updater.apk
RELEASE_OTACERTS=$PRODUCT_OUT/system/etc/security/otacerts.zip

for f in "$SRC_APK" "$RELEASE_OTACERTS" "$HOST_OUT/bin/apksigner" "$HOST_OUT/framework/signapk.jar"; do
  [ -e "$f" ] || { echo "Missing $f; build Updater (and signapk) first." >&2; exit 1; }
done

VERSION_NAME=$("$HOST_OUT/bin/aapt2" dump badging "$SRC_APK" \
  | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p")
OUT_ZIP=${1:-$TOP/packages/apps/Updater/Lunaris-Updater-Hotfix-v$VERSION_NAME.zip}

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/META-INF/com/google/android" "$STAGE/payload"

cert_sha256() {
  openssl x509 -in "$1" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr A-F a-f
}

# Old builds only give the Updater its platform SELinux domain when it is signed with their
# platform key, the AOSP test key. Soong signs it with v3 only; keep that.
"$HOST_OUT/bin/apksigner" sign \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --v4-signing-enabled false \
  --key "$SECURITY/platform.pk8" --cert "$SECURITY/platform.x509.pem" \
  --out "$STAGE/payload/Updater.apk" "$SRC_APK"
SIGNER=$("$HOST_OUT/bin/apksigner" verify --print-certs "$STAGE/payload/Updater.apk" \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$SIGNER" = "$(cert_sha256 "$SECURITY/platform.x509.pem")" ] || {
  echo "Re-signed Updater.apk is not signed with the AOSP platform test key" >&2; exit 1; }

# Trust the AOSP test key (what the old builds trust) plus whatever the current builds trust
python3 - "$SECURITY/testkey.x509.pem" "$RELEASE_OTACERTS" "$STAGE/payload/otacerts.zip" <<'EOF'
import sys, zipfile

testkey, release, out = sys.argv[1:]
entries = {"testkey.x509.pem": open(testkey, "rb").read()}
with zipfile.ZipFile(release) as z:
    for name in z.namelist():
        data = z.read(name)
        if data in entries.values():
            continue
        entries["release-" + name if name in entries else name] = data
if len(entries) < 2:
    sys.exit("%s only trusts the AOSP test key; build with the release keys" % release)
with zipfile.ZipFile(out, "w") as z:
    for name in sorted(entries):
        info = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        z.writestr(info, entries[name])
EOF

PLATFORM_CERT_HEX=$(openssl x509 -in "$SECURITY/platform.x509.pem" -outform DER | xxd -p | tr -d '\n')
sed -e "s|@VERSION_NAME@|$VERSION_NAME|g" \
    -e "s|@APK_SHA256@|$(sha256sum "$STAGE/payload/Updater.apk" | cut -d' ' -f1)|" \
    -e "s|@OTACERTS_SHA256@|$(sha256sum "$STAGE/payload/otacerts.zip" | cut -d' ' -f1)|" \
    -e "s|@TESTKEY_PEM_SHA256@|$(sha256sum "$SECURITY/testkey.x509.pem" | cut -d' ' -f1)|" \
    -e "s|@PLATFORM_CERT_HEX@|$PLATFORM_CERT_HEX|" \
    "$HERE/update-binary.in" > "$STAGE/META-INF/com/google/android/update-binary"
if grep -q '@[A-Z_0-9]*@' "$STAGE/META-INF/com/google/android/update-binary"; then
  echo "update-binary still has placeholders" >&2; exit 1
fi
echo "# Installed by update-binary" > "$STAGE/META-INF/com/google/android/updater-script"

python3 - "$STAGE" "$STAGE/unsigned.zip" <<'EOF'
import os, sys, zipfile

stage, out = sys.argv[1:]
files = ["META-INF/com/google/android/update-binary",
         "META-INF/com/google/android/updater-script",
         "payload/Updater.apk",
         "payload/otacerts.zip"]
with zipfile.ZipFile(out, "w") as z:
    for name in files:
        info = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        mode = 0o755 if name.endswith("update-binary") else 0o644
        info.external_attr = mode << 16
        with open(os.path.join(stage, name), "rb") as f:
            z.writestr(info, f.read())
EOF

"$JAVA" -Djava.library.path="$HOST_OUT/lib64" -jar "$HOST_OUT/framework/signapk.jar" -w \
  "$SECURITY/testkey.x509.pem" "$SECURITY/testkey.pk8" "$STAGE/unsigned.zip" "$OUT_ZIP"

echo "Built $OUT_ZIP"
echo "  Updater $VERSION_NAME, signed with AOSP platform test key $SIGNER"
for name in $(unzip -Z1 "$STAGE/payload/otacerts.zip"); do
  echo "  otacerts: $name $(unzip -p "$STAGE/payload/otacerts.zip" "$name" \
    | openssl x509 -noout -fingerprint -sha256 | cut -d= -f2)"
done
echo "  sha256 $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
