#!/system/bin/sh
# Read the brief file and set it to clipboard using Android's content provider
CONTENT=$(cat /sdcard/Download/clipboard_brief.txt)
am broadcast -a clipper.set -e text "$CONTENT"
