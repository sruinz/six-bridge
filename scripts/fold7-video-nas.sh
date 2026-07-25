#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
if [ "$#" -eq 0 ]; then
  echo "--source 경로가 필요합니다." >&2
  exit 2
fi
for argument in "$@"; do
  if [ "$argument" = "--case-id" ]; then
    echo "--case-id는 wrapper에서 변경할 수 없습니다." >&2
    exit 2
  fi
done
exec "$project_dir/scripts/fold7-extension-validation.sh" \
  --case-id fchehlladkkanoekpjffcfffpfbdaalj "$@"
