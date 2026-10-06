#!/bin/bash
#
# Builds the Updater hotfix for Lunaris peridot builds that predate it, as a recovery zip and
# as a Magisk / KernelSU / APatch module. Run after `m Updater` in a tree that builds with the
# Lunaris release keys:
#
#   packages/apps/Updater/hotfix/make-hotfix-zip.sh [output dir]
#
# Both carry the Updater signed with each platform key in use (the AOSP test key for builds up
# to 2026-09-05, the release key after) and an otacerts.zip trusting both OTA keys. The
# installers pick the APK matching the installed build. The recovery zip is signed with the
# release key, which recovery of current builds trusts.

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
TOP=$(cd "$HERE/../../../.." && pwd)
PRODUCT_OUT=${ANDROID_PRODUCT_OUT:-$TOP/out/target/product/peridot}
HOST_OUT=$TOP/out/host/linux-x86
JAVA=$TOP/prebuilts/jdk/jdk21/linux-x86/bin/java
TEST_KEYS=$TOP/build/make/target/product/security
RELEASE_KEYS=${RELEASE_KEYS:-$TOP/vendor/lineage-priv/keys}

SRC_APK=$PRODUCT_OUT/system_ext/priv-app/Updater/Updater.apk
RELEASE_OTACERTS=$PRODUCT_OUT/system/etc/security/otacerts.zip

for f in "$SRC_APK" "$RELEASE_OTACERTS" "$HOST_OUT/bin/apksigner" "$HOST_OUT/framework/signapk.jar" \
    "$RELEASE_KEYS/platform.x509.pem" "$RELEASE_KEYS/releasekey.x509.pem" \
    "$RELEASE_KEYS/releasekey.pk8"; do
  [ -e "$f" ] || { echo "Missing $f; build Updater (and signapk) first." >&2; exit 1; }
done

BADGING=$("$HOST_OUT/bin/aapt2" dump badging "$SRC_APK")
VERSION_NAME=$(sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" <<< "$BADGING")
VERSION_CODE=$(sed -n "s/^package: .*versionCode='\([^']*\)'.*/\1/p" <<< "$BADGING")
OUT_DIR=${1:-$TOP/packages/apps/Updater}
RECOVERY_ZIP=$OUT_DIR/Lunaris-Updater-Hotfix-v$VERSION_NAME.zip
MAGISK_ZIP=$OUT_DIR/Lunaris-Updater-v$VERSION_NAME-Magisk.zip
# Builds made from now on include this Updater
FIX_BUILD_UTC=$(date +%s)

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/payload"

cert_sha256() {
  openssl x509 -in "$1" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr A-F a-f
}

cert_hex() {
  openssl x509 -in "$1" -outform DER | xxd -p | tr -d '\n'
}

apk_signer() {
  "$HOST_OUT/bin/apksigner" verify --print-certs "$1" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
}

# The ROM build signs the Updater with the release platform key
[ "$(apk_signer "$SRC_APK")" = "$(cert_sha256 "$RELEASE_KEYS/platform.x509.pem")" ] || {
  echo "$SRC_APK is not signed with $RELEASE_KEYS/platform" >&2; exit 1; }
cp "$SRC_APK" "$STAGE/payload/Updater-release.apk"

# For builds up to 2026-09-05, whose platform key is the AOSP test key. Soong signs with v3
# only; keep that.
"$HOST_OUT/bin/apksigner" sign \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --v4-signing-enabled false \
  --key "$TEST_KEYS/platform.pk8" --cert "$TEST_KEYS/platform.x509.pem" \
  --out "$STAGE/payload/Updater-testkey.apk" "$SRC_APK"
[ "$(apk_signer "$STAGE/payload/Updater-testkey.apk")" = "$(cert_sha256 "$TEST_KEYS/platform.x509.pem")" ] || {
  echo "Re-signed Updater.apk is not signed with the AOSP platform test key" >&2; exit 1; }

# Trust the AOSP test key (what the old builds trust) plus whatever the current builds trust
python3 - "$TEST_KEYS/testkey.x509.pem" "$RELEASE_OTACERTS" "$STAGE/payload/otacerts.zip" <<'EOF'
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

# Fills in the @...@ values of a template
fill() {
  sed -e "s|@VERSION_NAME@|$VERSION_NAME|g" \
      -e "s|@VERSION_CODE@|$VERSION_CODE|g" \
      -e "s|@APK_TESTKEY_SHA256@|$(sha256sum "$STAGE/payload/Updater-testkey.apk" | cut -d' ' -f1)|" \
      -e "s|@APK_RELEASE_SHA256@|$(sha256sum "$STAGE/payload/Updater-release.apk" | cut -d' ' -f1)|" \
      -e "s|@OTACERTS_SHA256@|$(sha256sum "$STAGE/payload/otacerts.zip" | cut -d' ' -f1)|" \
      -e "s|@TESTKEY_PEM_SHA256@|$(sha256sum "$TEST_KEYS/testkey.x509.pem" | cut -d' ' -f1)|" \
      -e "s|@TESTKEY_PLATFORM_HEX@|$(cert_hex "$TEST_KEYS/platform.x509.pem")|" \
      -e "s|@RELEASE_PLATFORM_HEX@|$(cert_hex "$RELEASE_KEYS/platform.x509.pem")|" \
      -e "s|@FIX_BUILD_UTC@|$FIX_BUILD_UTC|" \
      "$1" > "$2"
  if grep -q '@[A-Z_0-9]*@' "$2"; then
    echo "$2 still has placeholders" >&2; exit 1
  fi
}

# Writes a zip with fixed timestamps from name=path arguments
make_zip() {
  python3 - "$@" <<'EOF'
import sys, zipfile

out, *files = sys.argv[1:]
with zipfile.ZipFile(out, "w") as z:
    for spec in files:
        name, path = spec.split("=", 1)
        info = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        executable = name.endswith(("update-binary", ".sh"))
        info.external_attr = (0o755 if executable else 0o644) << 16
        with open(path, "rb") as f:
            z.writestr(info, f.read())
EOF
}

PAYLOAD=(
  "payload/Updater-testkey.apk=$STAGE/payload/Updater-testkey.apk"
  "payload/Updater-release.apk=$STAGE/payload/Updater-release.apk"
  "payload/otacerts.zip=$STAGE/payload/otacerts.zip"
)

# Recovery zip
fill "$HERE/update-binary.in" "$STAGE/update-binary"
echo "# Installed by update-binary" > "$STAGE/updater-script"
make_zip "$STAGE/recovery-unsigned.zip" \
  "META-INF/com/google/android/update-binary=$STAGE/update-binary" \
  "META-INF/com/google/android/updater-script=$STAGE/updater-script" \
  "${PAYLOAD[@]}"
"$JAVA" -Djava.library.path="$HOST_OUT/lib64" -jar "$HOST_OUT/framework/signapk.jar" -w \
  "$RELEASE_KEYS/releasekey.x509.pem" "$RELEASE_KEYS/releasekey.pk8" \
  "$STAGE/recovery-unsigned.zip" "$RECOVERY_ZIP"

# Magisk / KernelSU / APatch module
fill "$HERE/magisk/module.prop.in" "$STAGE/module.prop"
fill "$HERE/magisk/select.sh.in" "$STAGE/select.sh"
make_zip "$MAGISK_ZIP" \
  "META-INF/com/google/android/update-binary=$HERE/magisk/update-binary" \
  "META-INF/com/google/android/updater-script=$HERE/magisk/updater-script" \
  "module.prop=$STAGE/module.prop" \
  "customize.sh=$HERE/magisk/customize.sh" \
  "select.sh=$STAGE/select.sh" \
  "post-fs-data.sh=$HERE/magisk/post-fs-data.sh" \
  "uninstall.sh=$HERE/magisk/uninstall.sh" \
  "${PAYLOAD[@]}"

echo "Updater $VERSION_NAME ($VERSION_CODE), for builds before $(date -u -d "@$FIX_BUILD_UTC")"
echo "  test-key APK signer:    $(apk_signer "$STAGE/payload/Updater-testkey.apk")"
echo "  release-key APK signer: $(apk_signer "$STAGE/payload/Updater-release.apk")"
for name in $(unzip -Z1 "$STAGE/payload/otacerts.zip"); do
  echo "  otacerts: $name $(unzip -p "$STAGE/payload/otacerts.zip" "$name" |
    openssl x509 -noout -fingerprint -sha256 | cut -d= -f2)"
done
for zip in "$RECOVERY_ZIP" "$MAGISK_ZIP"; do
  echo "Built $zip"
  echo "  sha256 $(sha256sum "$zip" | cut -d' ' -f1)"
done
