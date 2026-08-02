#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
runner="$project_dir/scripts/desktop-large-extension-analysis.sh"
fixture=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-large-analysis-test.XXXXXX")
trap 'rm -rf "$fixture"' EXIT

mkdir -p "$fixture/bin" "$fixture/inputs"
profile="$fixture/profile.json"
printf '{}\n' >"$profile"
cli_log="$fixture/cli.log"

cat >"$fixture/bin/sixbridge" <<'EOF'
#!/usr/bin/env bash
set -u

printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_CLI_LOG"
[ "${1:-}" = "analyze" ] || exit 97
shift
input=
output=
profile=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --input) input=$2; shift 2 ;;
    --profile) profile=$2; shift 2 ;;
    --output) output=$2; shift 2 ;;
    *) exit 98 ;;
  esac
done
[ -f "$input" ] && [ -f "$profile" ] && [ -n "$output" ] || exit 99

case "$(basename -- "$input")" in
  compatible.crx)
    cat >"$output" <<'JSON'
{"status":"compatible","extensionName":"Compatible Fake","originalExtensionId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","usedApis":["runtime","storage"]}
JSON
    exit 0
    ;;
  partial.crx)
    cat >"$output" <<'JSON'
{"status":"partial","extensionName":"Partial Fake","originalExtensionId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","usedApis":["downloads"]}
JSON
    exit 0
    ;;
  unknown.crx)
    cat >"$output" <<'JSON'
{"status":"unknown","extensionName":"Unknown Fake","originalExtensionId":"cccccccccccccccccccccccccccccccc","usedApis":[]}
JSON
    exit 2
    ;;
  timeout.crx)
    trap '' TERM
    while :; do :; done
    ;;
  *) exit 96 ;;
esac
EOF
chmod +x "$fixture/bin/sixbridge"

for name in compatible partial unknown timeout; do
  printf 'fixture\n' >"$fixture/inputs/$name.crx"
done

run_runner() {
  SIXBRIDGE_CLI="$fixture/bin/sixbridge" \
    SIXBRIDGE_TEST_CLI_LOG="$cli_log" \
    SIXBRIDGE_ANALYSIS_TIMEOUT_SECONDS=1 \
    SIXBRIDGE_ANALYSIS_TERM_GRACE_SECONDS=1 \
    "$runner" "$@"
}

success_output=$(run_runner \
  --profile "$profile" \
  --input "$fixture/inputs/compatible.crx" \
  --input "$fixture/inputs/partial.crx")
[ "$(printf '%s\n' "$success_output" | wc -l | tr -d ' ')" -eq 2 ]
printf '%s\n' "$success_output" | jq -e -s '
  length == 2 and
  .[0].status == "COMPATIBLE" and
  .[0].extensionName == "Compatible Fake" and
  .[0].verifiedExtensionId == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" and
  .[0].usedApiCount == 2 and
  (.[0].elapsedMs | type) == "number" and
  .[1].status == "PARTIAL" and
  .[1].usedApiCount == 1
' >/dev/null

set +e
unknown_output=$(run_runner \
  --profile "$profile" \
  --input "$fixture/inputs/unknown.crx" 2>"$fixture/unknown.err")
unknown_exit=$?
set -e
[ "$unknown_exit" -eq 2 ]
printf '%s\n' "$unknown_output" | jq -e '
  .status == "UNKNOWN" and .usedApiCount == 0
' >/dev/null

set +e
timeout_output=$(run_runner \
  --profile "$profile" \
  --input "$fixture/inputs/timeout.crx" 2>"$fixture/timeout.err")
timeout_exit=$?
set -e
[ "$timeout_exit" -eq 3 ]
printf '%s\n' "$timeout_output" | jq -e '
  .result == "TIMEOUT" and (.elapsedMs | type) == "number"
' >/dev/null

before_rejection=$(wc -l <"$cli_log" | tr -d ' ')
set +e
run_runner --profile "$profile" --input "$fixture/inputs" \
  >"$fixture/path.out" 2>"$fixture/path.err"
path_exit=$?
set -e
[ "$path_exit" -eq 3 ]
grep -F 'canonical regular file' "$fixture/path.err" >/dev/null
[ "$(wc -l <"$cli_log" | tr -d ' ')" -eq "$before_rejection" ]

printf 'desktop-large-extension-analysis shell tests: PASS\n'
