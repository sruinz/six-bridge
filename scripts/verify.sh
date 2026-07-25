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
  fixtures/desktop-mobile-golden/selection-bridge.test.mjs \
  fixtures/desktop-mobile-golden/storage-fallback.test.mjs \
  scripts/lan-receiver-evidence.test.mjs \
  scripts/ai-recipe-lan-receiver.test.mjs

bash -n scripts/fold7-gate.sh
for shell_file in \
  scripts/fold7-extension-validation.sh \
  scripts/fold7-ai-recipe.sh \
  scripts/fold7-video-nas.sh \
  scripts/fold7-converted-extension.sh \
  scripts/fold7-validation-lib.sh \
  scripts/fold7-extension-validation.test.sh \
  scripts/fold7-converted-extension.test.sh; do
  bash -n "$shell_file"
done
bash scripts/fold7-extension-validation.test.sh
bash scripts/fold7-converted-extension.test.sh
bash scripts/verify-mobile-conversion.sh
