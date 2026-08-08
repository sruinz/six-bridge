#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"

default_secure_java_home="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home"
secure_java_home="${SIXBRIDGE_SECURE_ZIP_JAVA_HOME:-$default_secure_java_home}"
if [ -x "$secure_java_home/bin/java" ]; then
  JAVA_HOME="$secure_java_home"
  export JAVA_HOME
fi

cli="$project_dir/cli/build/install/sixbridge/bin/sixbridge"
fixture="$project_dir/fixtures/desktop-mobile-golden"
profile="$project_dir/profiles/sbrowser-30.0.2.61.json"
output_dir="$project_dir/.local/test-output"
report="$output_dir/desktop-mobile-before.json"
first="$output_dir/desktop-mobile.sixdraft"
second="$output_dir/desktop-mobile-repeat.sixdraft"
project_identity="00112233445566778899aabbccddeeff"

if [ ! -x "$cli" ]; then
  echo "sixbridge installDist 결과를 찾을 수 없습니다: $cli" >&2
  exit 1
fi

mkdir -p "$output_dir"
state_dir=$(mktemp -d "$project_dir/.local/verify-mobile-state.XXXXXX")
trap 'rm -rf "$state_dir"' EXIT

tree_hash() {
  (
    cd "$fixture"
    find . -type f -print0 |
      LC_ALL=C sort -z |
      xargs -0 shasum -a 256 |
      shasum -a 256 |
      awk '{print $1}'
  )
}

before_hash=$(tree_hash)

"$cli" --workspace "$state_dir" analyze \
  --input "$fixture" \
  --profile "$profile" \
  --output "$report"

"$cli" --workspace "$state_dir" draft \
  --input "$fixture" \
  --profile "$profile" \
  --identity "$project_identity" \
  --approve-adapter action-popup-entry \
  --output "$first"

"$cli" --workspace "$state_dir" draft \
  --input "$fixture" \
  --profile "$profile" \
  --identity "$project_identity" \
  --approve-adapter action-popup-entry \
  --output "$second"

after_hash=$(tree_hash)
if [ "$before_hash" != "$after_hash" ]; then
  echo "원본 fixture가 변환 중 변경되었습니다." >&2
  exit 1
fi
echo "source unchanged"

first_hash=$(shasum -a 256 "$first" | awk '{print $1}')
second_hash=$(shasum -a 256 "$second" | awk '{print $1}')
if [ "$first_hash" != "$second_hash" ]; then
  echo "동일 입력의 SixDraft hash가 다릅니다." >&2
  exit 1
fi
echo "deterministic hash matched"
"$cli" verify-draft --input "$first"
