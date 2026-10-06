# Lunaris Updater module installer, run by Magisk / KernelSU / APatch.

SKIPUNZIP=1

# Part of the OTA feed URL in the Lunaris peridot Updater's resources
OTA_FEED="Inventor365/evolution-peridot"
R=${LUNARIS_HOTFIX_TEST_ROOT:-}

ui_print "*********************************************"
ui_print "  Lunaris Updater module for peridot"
ui_print "*********************************************"

DEVICE=$(getprop ro.lunaris.device)
[ -n "$DEVICE" ] || DEVICE=$(getprop ro.lineage.device)
[ -n "$DEVICE" ] || DEVICE=$(getprop ro.product.device)
[ "$DEVICE" = "peridot" ] || abort "! This module is only for the POCO F6 (peridot), not '$DEVICE'"

unzip -p "$R/system_ext/priv-app/Updater/Updater.apk" resources.arsc 2>/dev/null |
  grep -q "$OTA_FEED" || abort "! The installed system is not Lunaris for peridot"

ui_print "- Extracting files"
unzip -o -q "$ZIPFILE" module.prop select.sh post-fs-data.sh uninstall.sh 'payload/*' \
  -d "$MODPATH" >&2 || abort "! Could not extract the module"
. "$MODPATH/select.sh"
payload_intact "$MODPATH" || abort "! The zip is damaged. Download it again."

select_files "$MODPATH" || abort "! $SELECT_ERROR"
ui_print "- Build signed with: $SELECTED_KEYS"
getprop ro.build.version.incremental > "$MODPATH/build_incremental"
# Make Android parse the Updater again on the next boot instead of using its cached copy
touch "$MODPATH/.clear_package_cache"

set_perm_recursive "$MODPATH" 0 0 0755 0644

ui_print "- Updater $VERSION_NAME will be used after a reboot"
if [ "$ADDED_RELEASE_KEY" = 1 ]; then
  ui_print "- OTAs signed with the Lunaris release key will be accepted"
fi
ui_print "- The module removes itself once a system update"
ui_print "  brings this Updater"
