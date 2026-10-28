#!/usr/bin/env bash
# Fetches the Amap Android Location SDK AAR into app/libs/ (git-ignored).
#
# The Amap SDK is an OPTIONAL runtime component for pilot-bot-v2: the app
# compiles and runs without it (SystemGpsFallbackProvider takes over), and
# AmapNavigationProvider loads it reflectively. This script only stages the
# binary; it never edits build files or commits anything.
#
# Usage:
#   1. Preferred: download the Location SDK from the official page
#      https://lbs.amap.com/api/android-location-sdk/download/
#      unzip it, and copy the .aar into app/libs/ by hand.
#   2. Or, if you have a direct download URL for the AAR:
#      AMAP_AAR_URL="https://.../amap-location.aar" scripts/fetch-amap-sdk.sh
#
# After staging the AAR, add ONE line to app/build.gradle dependencies
# (replace the file name with the real one):
#   implementation(files("libs/amap-location.aar"))
# and set AMAP_KEY in local.properties (never commit it), then rebuild.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT_DIR/app/libs"
mkdir -p "$LIBS_DIR"

if ls "$LIBS_DIR"/*.aar >/dev/null 2>&1; then
  echo "Amap AAR already present in $LIBS_DIR — nothing to do."
  exit 0
fi

if [ -n "${AMAP_AAR_URL:-}" ]; then
  echo "Downloading Amap AAR from \$AMAP_AAR_URL ..."
  curl -L --fail -o "$LIBS_DIR/amap-location.aar" "$AMAP_AAR_URL"
  if [ -s "$LIBS_DIR/amap-location.aar" ]; then
    echo "Saved to $LIBS_DIR/amap-location.aar"
    echo "Next: add implementation(files(\"libs/amap-location.aar\")) to app/build.gradle"
    echo "and set AMAP_KEY in local.properties, then rebuild."
    exit 0
  fi
  echo "Download produced an empty file; removing it." >&2
  rm -f "$LIBS_DIR/amap-location.aar"
  exit 1
fi

cat <<'EOF'
Amap Location SDK AAR is not in app/libs/ and no AMAP_AAR_URL was given.

The app still works without it (system GPS fallback), but for Amap定位:

  1. Open https://lbs.amap.com/api/android-location-sdk/download/
  2. Download the Android Location SDK and unzip it.
  3. Copy the .aar file into app/libs/ (this directory is git-ignored).
  4. Add to app/build.gradle dependencies (use the real file name):
       implementation(files("libs/amap-location.aar"))
  5. Write AMAP_KEY=你的高德Key into local.properties (never commit it).
  6. Rebuild in Android Studio.

Or re-run with a direct URL:
  AMAP_AAR_URL="https://.../amap-location.aar" scripts/fetch-amap-sdk.sh
EOF
# Exit 2 = setup incomplete but NOT fatal: the app runs on system GPS.
exit 2
