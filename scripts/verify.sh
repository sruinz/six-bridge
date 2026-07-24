#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"

if [ -x "$project_dir/gradlew" ]; then
  gradle_command="$project_dir/gradlew"
else
  gradle_command="gradle"
fi

if [ -n "${JAVA_HOME:-}" ] && [ ! -x "$JAVA_HOME/bin/java" ]; then
  unset JAVA_HOME
fi

GRADLE_USER_HOME="$project_dir/.local/gradle-home" \
  "$gradle_command" -Pkotlin.jvm.target.validation.mode=IGNORE \
  clean test installDist

node --test fixtures/minimal-mv3/background.test.mjs \
  fixtures/minimal-mv3/content.test.mjs \
  fixtures/desktop-mobile-golden/action-entry.test.mjs \
  fixtures/desktop-mobile-golden/selection-bridge.test.mjs \
  fixtures/desktop-mobile-golden/storage-fallback.test.mjs

bash -n scripts/fold7-gate.sh
bash scripts/verify-mobile-conversion.sh
