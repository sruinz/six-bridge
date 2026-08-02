#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
runner="$project_dir/scripts/desktop-large-extension-analysis.sh"
fixture=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-large-analysis-test.XXXXXX")

cleanup_fixture() {
  for pid_file in "$fixture"/*.pid; do
    [ -f "$pid_file" ] || continue
    pid=$(cat "$pid_file" 2>/dev/null || true)
    case "$pid" in
      ''|*[!0-9]*) continue ;;
    esac
    kill -KILL "$pid" 2>/dev/null || true
  done
  rm -rf "$fixture"
}

trap cleanup_fixture EXIT

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

write_report() {
  status=$1
  name=$2
  extension_id=$3
  used_apis=$4
  cat >"$output" <<JSON
{
  "schemaVersion": 3,
  "status": "$status",
  "manifestVersion": 3,
  "extensionName": "$name",
  "extensionVersion": "1",
  "manifestKeyPresent": false,
  "declaredPermissions": [],
  "usedApis": $used_apis,
  "unsupportedApis": [],
  "issues": [],
  "conversionState": "passthrough",
  "adapterDecisions": [],
  "originalExtensionId": "$extension_id",
  "convertedExtensionId": "$extension_id",
  "sourceSha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "browserProfileSha256": "1111111111111111111111111111111111111111111111111111111111111111"
}
JSON
}

run_with_child() {
  trap '' HUP INT TERM
  printf '%s\n' "$$" >"$SIXBRIDGE_TEST_LEADER_PID_FILE"
  /bin/sh -c 'trap "" HUP INT TERM; while :; do sleep 1; done' &
  child=$!
  printf '%s\n' "$child" >"$SIXBRIDGE_TEST_CHILD_PID_FILE"
  while :; do sleep 1; done
}

case "$(basename -- "$input")" in
  compatible.crx)
    write_report compatible 'Compatible Fake' \
      aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '["runtime","storage"]'
    exit 0
    ;;
  partial.crx)
    write_report partial 'Partial Fake' \
      bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb '["downloads"]'
    exit 0
    ;;
  unknown.crx)
    write_report unknown 'Unknown Fake' \
      cccccccccccccccccccccccccccccccc '[]'
    exit 2
    ;;
  timeout.crx|signal.crx)
    run_with_child
    ;;
  missing-id.crx)
    write_report compatible Invalid aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '[]'
    jq 'del(.originalExtensionId)' "$output" >"$output.tmp" && mv "$output.tmp" "$output"
    exit 0
    ;;
  invalid-api.crx)
    write_report compatible Invalid aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '["runtime",1]'
    exit 0
    ;;
  multiple-json.crx)
    write_report compatible Invalid aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '[]'
    cp "$output" "$output.one"
    cat "$output.one" "$output.one" >"$output"
    exit 0
    ;;
  malformed.crx)
    printf '{\n' >"$output"
    exit 0
    ;;
  wrong-schema.crx)
    write_report compatible Invalid aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '[]'
    jq '.schemaVersion = 2' "$output" >"$output.tmp" && mv "$output.tmp" "$output"
    exit 0
    ;;
  invalid-status.crx)
    write_report invalid Invalid aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '[]'
    exit 0
    ;;
  invalid-name.crx)
    write_report compatible Invalid aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa '[]'
    jq '.extensionName = 1' "$output" >"$output.tmp" && mv "$output.tmp" "$output"
    exit 0
    ;;
  invalid-id.crx)
    write_report compatible Invalid zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz '[]'
    exit 0
    ;;
  *) exit 96 ;;
esac
EOF
chmod +x "$fixture/bin/sixbridge"

for name in compatible partial unknown timeout signal missing-id invalid-api \
  multiple-json malformed wrong-schema invalid-status invalid-name invalid-id; do
  printf 'fixture\n' >"$fixture/inputs/$name.crx"
done

run_runner() {
  SIXBRIDGE_CLI="$fixture/bin/sixbridge" \
    SIXBRIDGE_TEST_CLI_LOG="$cli_log" \
    SIXBRIDGE_TEST_CHILD_PID_FILE="${SIXBRIDGE_TEST_CHILD_PID_FILE:-$fixture/default-child.pid}" \
    SIXBRIDGE_TEST_LEADER_PID_FILE="${SIXBRIDGE_TEST_LEADER_PID_FILE:-$fixture/default-leader.pid}" \
    SIXBRIDGE_ANALYSIS_TIMEOUT_SECONDS=1 \
    SIXBRIDGE_ANALYSIS_TERM_GRACE_SECONDS=1 \
    "$runner" "$@"
}

wait_for_file() {
  path=$1
  attempts=0
  while [ ! -s "$path" ] && [ "$attempts" -lt 100 ]; do
    sleep 0.05
    attempts=$((attempts + 1))
  done
  [ -s "$path" ]
}

assert_process_dead() {
  pid=$1
  attempts=0
  while kill -0 "$pid" 2>/dev/null && [ "$attempts" -lt 50 ]; do
    sleep 0.1
    attempts=$((attempts + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    printf 'analysis descendant remained alive: %s\n' "$pid" >&2
    return 1
  fi
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

if [ "${SIXBRIDGE_TEST_CASE:-all}" = all ] ||
  [ "${SIXBRIDGE_TEST_CASE:-all}" = timeout_cleanup ]; then
  timeout_child="$fixture/timeout-child.pid"
  timeout_leader="$fixture/timeout-leader.pid"
  set +e
  timeout_output=$(SIXBRIDGE_TEST_CHILD_PID_FILE="$timeout_child" \
    SIXBRIDGE_TEST_LEADER_PID_FILE="$timeout_leader" run_runner \
    --profile "$profile" \
    --input "$fixture/inputs/timeout.crx" 2>"$fixture/timeout.err")
  timeout_exit=$?
  set -e
  [ "$timeout_exit" -eq 3 ]
  printf '%s\n' "$timeout_output" | jq -e '
    .result == "TIMEOUT" and (.elapsedMs | type) == "number"
  ' >/dev/null
  wait_for_file "$timeout_child"
  assert_process_dead "$(cat "$timeout_child")"
fi

if [ "${SIXBRIDGE_TEST_CASE:-all}" = all ] ||
  [ "${SIXBRIDGE_TEST_CASE:-all}" = signal_cleanup ]; then
  signal_child="$fixture/signal-child.pid"
  signal_leader="$fixture/signal-leader.pid"
  SIXBRIDGE_CLI="$fixture/bin/sixbridge" \
    SIXBRIDGE_TEST_CLI_LOG="$cli_log" \
    SIXBRIDGE_TEST_CHILD_PID_FILE="$signal_child" \
    SIXBRIDGE_TEST_LEADER_PID_FILE="$signal_leader" \
    SIXBRIDGE_ANALYSIS_TIMEOUT_SECONDS=30 \
    SIXBRIDGE_ANALYSIS_TERM_GRACE_SECONDS=1 \
    "$runner" --profile "$profile" --input "$fixture/inputs/signal.crx" \
    >"$fixture/signal.out" 2>"$fixture/signal.err" &
  signal_runner=$!
  printf '%s\n' "$signal_runner" >"$fixture/signal-runner.pid"
  wait_for_file "$signal_child"
  kill -TERM "$signal_runner"
  set +e
  wait "$signal_runner"
  signal_exit=$?
  set -e
  rm -f "$fixture/signal-runner.pid"
  [ "$signal_exit" -eq 143 ]
  assert_process_dead "$(cat "$signal_child")"
fi

if [ "${SIXBRIDGE_TEST_CASE:-all}" = all ] ||
  [ "${SIXBRIDGE_TEST_CASE:-all}" = strict_json ]; then
  strict_failures=0
  for name in missing-id invalid-api multiple-json malformed wrong-schema \
    invalid-status invalid-name invalid-id; do
    set +e
    run_runner --profile "$profile" --input "$fixture/inputs/$name.crx" \
      >"$fixture/$name.out" 2>"$fixture/$name.err"
    invalid_exit=$?
    set -e
    if [ "$invalid_exit" -ne 3 ]; then
      printf 'strict JSON fixture unexpectedly exited %s: %s\n' \
        "$invalid_exit" "$name" >&2
      strict_failures=$((strict_failures + 1))
    fi
  done
  [ "$strict_failures" -eq 0 ]
fi

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
