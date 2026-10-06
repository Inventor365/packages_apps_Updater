#!/system/bin/sh
# Runs before the module is mounted. After a system update, keep applying only if the new
# build still needs this Updater (choosing its key again), and remove the module otherwise.

MODDIR=${0%/*}
. "$MODDIR/select.sh"

BUILD=$(getprop ro.build.version.incremental)
if [ "$BUILD" != "$(cat "$MODDIR/build_incremental" 2>/dev/null)" ]; then
  if select_files "$MODDIR"; then
    echo "$BUILD" > "$MODDIR/build_incremental"
  else
    touch "$MODDIR/disable" "$MODDIR/remove"
  fi
  touch "$MODDIR/.clear_package_cache"
fi

if [ -f "$MODDIR/.clear_package_cache" ]; then
  # Otherwise Android keeps using its parsed copy of the Updater it saw last
  rm -rf "$R/data/system/package_cache/"*
  rm -f "$MODDIR/.clear_package_cache"
fi
