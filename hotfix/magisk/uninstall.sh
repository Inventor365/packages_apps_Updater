#!/system/bin/sh
# Android cached its parse of this module's Updater; make it parse the original again
rm -rf "${LUNARIS_HOTFIX_TEST_ROOT:-}/data/system/package_cache/"*
