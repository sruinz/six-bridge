#!/bin/bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
default_source='/workspace/T5다운로드/작업중/미니메뉴 확장프로그램/smart-context-menu'

has_source=false
for argument in "$@"; do
  [ "$argument" = "--source" ] && has_source=true
done
if [ "$has_source" = true ]; then
  exec "$project_dir/scripts/fold7-extension-validation.sh" \
    --case-id lneaoklobohnoncgckmbbjfiajgpbdde "$@"
else
  exec "$project_dir/scripts/fold7-extension-validation.sh" \
    --case-id lneaoklobohnoncgckmbbjfiajgpbdde \
    --source "$default_source" "$@"
fi
