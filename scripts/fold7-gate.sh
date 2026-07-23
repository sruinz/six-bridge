#!/bin/bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <adb-serial>" >&2
  echo "Find the serial with: adb devices -l" >&2
  exit 2
fi

serial=$1
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
extension_id=odgobepbaifhaibnnkpcclekeaogainb
package_name="dev.sixbridge.ext.$extension_id"
wrapper_apk="$project_dir/dist/$extension_id-wrapper.apk"
evidence_dir="$project_dir/.local/evidence"
evidence_log="$evidence_dir/$(date +%Y%m%d-%H%M%S)-minimal-gate.log"

if [ ! -f "$wrapper_apk" ]; then
  echo "Minimal gate wrapper not found: $wrapper_apk" >&2
  exit 2
fi

mkdir -p "$evidence_dir"

{
  echo "# SIX Bridge Fold7 minimal gate"
  echo "serial=$serial"
  echo "package=$package_name"
  echo

  adb -s "$serial" get-state
  adb -s "$serial" install -r "$wrapper_apk"

  package_path=$(adb -s "$serial" shell pm path "$package_name")
  echo "packagePath=$package_path"
  case "$package_path" in
    package:*) ;;
    *)
      echo "Wrapper package is not installed." >&2
      exit 3
      ;;
  esac

  discovery=$(adb -s "$serial" shell cmd package resolve-activity \
    --brief \
    -a android.intent.action.VIEW \
    -c android.intent.category.BROWSABLE \
    -d six://ex \
    -p "$package_name")
  echo "discovery=$discovery"
  case "$discovery" in
    *"$package_name"*) ;;
    *)
      echo "six://ex discovery activity did not resolve." >&2
      exit 3
      ;;
  esac

  provider_magic=$(adb -s "$serial" shell \
    "content read --uri 'content://$package_name.contentprovider' | head -c 4")
  echo "providerMagic=$provider_magic"
  if [ "$provider_magic" != "Cr24" ]; then
    echo "ContentProvider did not return a CRX3 stream." >&2
    exit 3
  fi

  adb -s "$serial" shell am start \
    -a android.intent.action.VIEW \
    -d internet://extension \
    -p com.sec.android.app.sbrowser

  echo
  echo "## Relevant logcat"
  adb -s "$serial" logcat -d -t 1000 |
    grep -E "$package_name|$extension_id|SixManager|SixPkg|TerraceExtensions|dev_ext" ||
    true
} 2>&1 | tee "$evidence_log"

echo
echo "Host-side checks passed."
echo "Evidence: $evidence_log"
echo "Now confirm: on a normal HTTPS page, SIX Bridge OK appears once, disappears after 8 seconds, reappears once after refresh, and appears without re-enabling after a browser restart."
