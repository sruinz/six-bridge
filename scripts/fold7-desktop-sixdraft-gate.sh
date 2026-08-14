#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly REQUIRED_SERIAL='192.0.2.10:44085'
readonly MAX_DRAFT_SIZE=268435456
readonly EXPECTED_MANAGER_VERSION_CODE=2
readonly CLI="${SIXBRIDGE_CLI:-${ROOT_DIR}/cli/build/install/sixbridge/bin/sixbridge}"
readonly EVIDENCE_ROOT="${SIXBRIDGE_EVIDENCE_ROOT:-${ROOT_DIR}/.local/evidence}"

umask 077

fail() { printf '오류: %s\n' "$1" >&2; exit "${2:-1}"; }
usage() {
    cat >&2 <<EOF
사용법:
  $0 --draft <path.sixdraft> --serial ${REQUIRED_SERIAL} \
    --expected-name <extension-name> --expected-package <wrapper-package>
  $0 --self-test
EOF
    exit 2
}
sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }

cleanup_verified_snapshot() {
    [ -z "${SNAPSHOT:-}" ] || rm -f -- "$SNAPSHOT"
}

wait_for_confirmation() {
    [ "${SIXBRIDGE_GATE_SKIP_CONFIRMATIONS:-}" = 1 ] && return
    printf '%s\n' "$1" >&2
    IFS= read -r _ || fail '사용자 확인을 읽지 못했습니다.'
}

validate_arguments() {
    [ -n "$DRAFT" ] || usage
    [ -n "$SERIAL" ] || usage
    [ -n "$EXPECTED_NAME" ] || fail 'expected-name은 비워 둘 수 없습니다.' 2
    [[ "$EXPECTED_PACKAGE" =~ ^dev\.sixbridge\.ext\.[a-p]{32}$ ]] ||
        fail 'expected-package은 dev.sixbridge.ext.<32자 extension ID>여야 합니다.' 2
    [[ "$EXPECTED_MANAGER_VERSION_CODE" =~ ^[1-9][0-9]*$ ]] ||
        fail '예상 manager versionCode가 올바르지 않습니다.' 2
    [ "$SERIAL" = "$REQUIRED_SERIAL" ] || fail "Fold7 serial만 허용합니다: $REQUIRED_SERIAL" 2
    [ -f "$DRAFT" ] && [ ! -L "$DRAFT" ] || fail "draft가 안전한 일반 파일이 아닙니다: $DRAFT" 2
    actual_extension=${DRAFT##*.}
    test "$actual_extension" = sixdraft || fail 'draft 확장자는 .sixdraft여야 합니다.' 2
    draft_size=$(stat -f '%z' "$DRAFT")
    test "$draft_size" -le "$MAX_DRAFT_SIZE" || fail "draft는 256 MiB 이하여야 합니다: $draft_size" 2
    [ -x "$CLI" ] || fail "SixBridge CLI를 찾을 수 없습니다: $CLI" 2
}

create_verified_snapshot() {
    SNAPSHOT="${RUN_DIR}/verified-input.sixdraft"
    trap cleanup_verified_snapshot EXIT
    cp -- "$DRAFT" "$SNAPSHOT" || fail 'draft의 검증용 private snapshot을 만들지 못했습니다.' 3
    chmod 600 "$SNAPSHOT" || fail 'draft snapshot 권한을 제한하지 못했습니다.' 3
    draft_size=$(stat -f '%z' "$SNAPSHOT")
    test "$draft_size" -le "$MAX_DRAFT_SIZE" || fail "snapshot은 256 MiB 이하여야 합니다: $draft_size" 2
    "$CLI" verify-draft --input "$SNAPSHOT" >/dev/null || fail 'SixDraft strict host 검증에 실패했습니다.' 3
    sha256=$(sha256_file "$SNAPSHOT")
    test -n "$sha256" || fail 'draft SHA-256을 계산하지 못했습니다.' 3
}

collect_device_sha256() {
    local device_path=$1 device_sha_file
    [[ "$device_path" =~ ^/[A-Za-z0-9._/-]+\.sixdraft$ ]] || fail '기기 draft 경로는 공백 없는 절대 .sixdraft 경로여야 합니다.' 2
    device_sha_file="${RUN_DIR}/device-draft.sha256"
    adb -s "$SERIAL" shell toybox sha256sum "$device_path" >"$device_sha_file" || fail '기기 draft SHA-256을 읽지 못했습니다.' 3
    device_sha=$(awk 'NR == 1 {print $1}' "$device_sha_file")
    [[ "$device_sha" =~ ^[0-9a-fA-F]{64}$ ]] || fail '기기 draft SHA-256 형식이 올바르지 않습니다.' 3
    device_sha=$(printf '%s' "$device_sha" | tr '[:upper:]' '[:lower:]')
    [ "$device_sha" = "$sha256" ] || fail 'host와 기기 draft SHA-256이 다릅니다.' 3
}

collect_post_install_evidence() {
    local manager_pid manager_uid
    adb -s "$SERIAL" shell pm list packages --user 0 "$EXPECTED_PACKAGE" >"${RUN_DIR}/wrapper-package-user-0.txt" ||
        fail 'user 0 wrapper package 설치 상태를 읽지 못했습니다.' 3
    grep -Fxq "package:${EXPECTED_PACKAGE}" "${RUN_DIR}/wrapper-package-user-0.txt" ||
        fail "예상 wrapper package가 user 0에 설치되지 않았습니다: $EXPECTED_PACKAGE" 3
    adb -s "$SERIAL" shell pm list packages -U --user 0 dev.sixbridge.manager >"${RUN_DIR}/manager-user-0-uid.txt" ||
        fail 'user 0 manager UID를 읽지 못했습니다.' 3
    manager_uid=$(sed -n 's/^package:dev\.sixbridge\.manager uid:\([0-9][0-9]*\)$/\1/p' "${RUN_DIR}/manager-user-0-uid.txt")
    [[ "$manager_uid" =~ ^[1-9][0-9]*$ ]] || fail 'user 0 manager UID 형식이 올바르지 않습니다.' 3
    adb -s "$SERIAL" shell ps -n -u "$manager_uid" -o UID,PID,NAME |
        awk -v uid="$manager_uid" '$1 == uid && $3 == "dev.sixbridge.manager" { print $1, $2, $3 }' \
            >"${RUN_DIR}/manager-user-0-process.txt" || fail 'user 0 manager process를 읽지 못했습니다.' 3
    manager_pid=$(awk 'NR == 1 {print $2}' "${RUN_DIR}/manager-user-0-process.txt")
    if [[ "$manager_pid" =~ ^[1-9][0-9]*$ ]]; then
        adb -s "$SERIAL" shell logcat -d --pid "$manager_pid" -t 1000 >"${RUN_DIR}/manager-logcat.txt" ||
            fail 'manager PID로 제한한 logcat 증거를 읽지 못했습니다.' 3
    else
        printf 'manager process not running; scoped logcat unavailable\n' >"${RUN_DIR}/manager-logcat.txt"
    fi
}

main() {
    DRAFT= SERIAL= EXPECTED_NAME= EXPECTED_PACKAGE=
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --draft|--serial|--expected-name|--expected-package)
                [ "$#" -ge 2 ] || usage
                case "$1" in
                    --draft) DRAFT=$2;;
                    --serial) SERIAL=$2;;
                    --expected-name) EXPECTED_NAME=$2;;
                    *) EXPECTED_PACKAGE=$2;;
                esac
                shift 2;;
            --self-test) [ "$#" -eq 1 ] || usage; self_test; return;;
            *) usage;;
        esac
    done
    validate_arguments
    [ ! -L "$EVIDENCE_ROOT" ] || fail "evidence root가 symlink입니다: $EVIDENCE_ROOT" 3
    mkdir -p "$EVIDENCE_ROOT"
    timestamp=$(date +%Y%m%d-%H%M%S)
    RUN_DIR="${EVIDENCE_ROOT}/${timestamp}-desktop-sixdraft-gate"
    EVIDENCE_LOG="${EVIDENCE_ROOT}/${timestamp}-desktop-sixdraft-gate.log"
    mkdir "$RUN_DIR" || fail "evidence 디렉터리를 만들지 못했습니다: $RUN_DIR" 3
    create_verified_snapshot
    command -v adb >/dev/null 2>&1 || fail '필수 도구를 찾을 수 없습니다: adb' 2
    adb -s "$SERIAL" get-state | grep -Fxq device || fail 'Fold7이 device 상태가 아닙니다.' 3
    adb -s "$SERIAL" shell pm path --user 0 dev.sixbridge.manager >"${RUN_DIR}/manager-user-0-path.txt" || fail 'user 0 Android manager 설치 상태를 읽지 못했습니다.' 3
    grep -Fq 'package:' "${RUN_DIR}/manager-user-0-path.txt" || fail 'user 0에 최신 Android manager APK가 설치되어 있지 않습니다.' 3
    adb -s "$SERIAL" shell pm list packages --user 0 --show-versioncode dev.sixbridge.manager >"${RUN_DIR}/manager-user-0-version.txt" ||
        fail 'user 0 Android manager versionCode를 읽지 못했습니다.' 3
    grep -Fxq "package:dev.sixbridge.manager versionCode:${EXPECTED_MANAGER_VERSION_CODE}" "${RUN_DIR}/manager-user-0-version.txt" ||
        fail "user 0 Android manager versionCode가 게이트 기준과 다릅니다: ${EXPECTED_MANAGER_VERSION_CODE}" 3
    {
        printf 'serial=%s\nsource_draft=%s\nverified_snapshot=%s\ndraft_size=%s\nhost_draft_sha256=%s\nexpected_addon_name=%s\nexpected_wrapper_package=%s\nmanager_version_code=%s\nhost_strict_verify=passed\n' \
            "$SERIAL" "$DRAFT" "$SNAPSHOT" "$draft_size" "$sha256" "$EXPECTED_NAME" "$EXPECTED_PACKAGE" "$EXPECTED_MANAGER_VERSION_CODE"
    } >"$EVIDENCE_LOG"
    printf '아래 private snapshot을 Quick Share·USB·클라우드로 Fold7에 전송하세요:\n%s\n' "$SNAPSHOT" >&2
    printf '%s' '전송한 Fold7 .sixdraft 절대 경로를 입력하세요: ' >&2
    if [ -n "${SIXBRIDGE_DEVICE_DRAFT_PATH:-}" ]; then device_draft_path=$SIXBRIDGE_DEVICE_DRAFT_PATH; else IFS= read -r device_draft_path || fail '사용자 입력을 읽지 못했습니다.'; fi
    [ -n "$device_draft_path" ] || fail '기기 draft 경로가 필요합니다.' 2
    collect_device_sha256 "$device_draft_path"
    cleanup_verified_snapshot
    SNAPSHOT=
    trap - EXIT
    printf 'device_draft_sha256=%s\n' "$device_sha" >>"$EVIDENCE_LOG"
    wait_for_confirmation '사용자가 `데스크탑 변환 파일 가져오기`, 최종 .six/APK 생성, 파일 앱 설치와 Add-ons 확인을 마친 뒤 Enter를 누르세요.'
    collect_post_install_evidence
    printf 'manual_addon_screenshot_required=true\nmanual_function_and_restart_required=true\nevidence=%s\n' "$EVIDENCE_LOG" >>"$EVIDENCE_LOG"
    printf '자동 증거 수집을 마쳤습니다: %s\n' "$EVIDENCE_LOG"
    printf '%s\n' 'Add-ons 이름·아이콘·지원 상태, 텍스트 선택 동작, 설정 저장, 브라우저 재시작 뒤 유지와 래퍼 제거는 사용자가 화면으로 확인해야 합니다.'
}

self_test() {
    local fixture cleanup_root valid_draft corrupt_draft wrong_extension adb_install_invocations expected_package
    fixture=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-desktop-sixdraft-gate.XXXXXX")
    cleanup_self_test() {
        [ -z "${fixture:-}" ] || rm -rf -- "$fixture"
        [ -z "${cleanup_root:-}" ] || rm -rf -- "$cleanup_root"
    }
    trap cleanup_self_test EXIT
    mkdir -p "$fixture/bin" "$fixture/evidence"
    [ "${SIXBRIDGE_SELF_TEST_FORCE_FAILURE:-}" != 1 ] || fail '강제 self-test 실패'
    valid_draft="$fixture/valid.sixdraft"; corrupt_draft="$fixture/corrupt.sixdraft"; wrong_extension="$fixture/wrong.zip"
    expected_package='dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    printf 'valid fixture\n' >"$valid_draft"; printf 'corrupt fixture\n' >"$corrupt_draft"; cp "$valid_draft" "$wrong_extension"
    cat >"$fixture/bin/sixbridge" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$1" = verify-draft ] && [ "$2" = --input ] || exit 2
printf '%s\n' "$3" >>"$SIXBRIDGE_TEST_CLI_LOG"
grep -Fq 'corrupt fixture' "$3" && exit 1
printf 'SixDraft verified\n'
EOF
    cat >"$fixture/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$1" = -s ] && shift 2
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_ADB_LOG"
case "$1" in
  get-state) printf 'device\n';;
  shell) shift; case "$1" in
    toybox) printf '%s  %s\n' "$SIXBRIDGE_TEST_DRAFT_SHA256" "$3";;
    pm)
      if [ "$2" = path ] && [ "$3" = --user ] && [ "$4" = 0 ] && [ "$5" = dev.sixbridge.manager ]; then
        printf 'package:/data/user/0/dev.sixbridge.manager\n'
      elif [ "$2" = list ] && [ "$3" = packages ] && [ "$4" = --user ] && [ "$5" = 0 ] && [ "$6" = --show-versioncode ] && [ "$7" = dev.sixbridge.manager ]; then
        printf 'package:dev.sixbridge.manager versionCode:%s\n' "${SIXBRIDGE_TEST_MANAGER_VERSION:-2}"
      elif [ "$2" = list ] && [ "$3" = packages ] && [ "$4" = --user ] && [ "$5" = 0 ] && [ "$6" = "$SIXBRIDGE_TEST_EXPECTED_PACKAGE" ]; then
        [ "${SIXBRIDGE_TEST_MISSING_WRAPPER:-0}" = 1 ] || printf 'package:%s\n' "$SIXBRIDGE_TEST_EXPECTED_PACKAGE"
      elif [ "$2" = list ] && [ "$3" = packages ] && [ "$4" = -U ] && [ "$5" = --user ] && [ "$6" = 0 ] && [ "$7" = dev.sixbridge.manager ]; then
        printf 'package:dev.sixbridge.manager uid:11240\n'
      else
        exit 2
      fi;;
    ps) [ "$2" = -n ] && [ "$3" = -u ] && [ "$4" = 11240 ] && [ "$5" = -o ] && [ "$6" = UID,PID,NAME ] && printf '  UID   PID NAME\n15011240 999 dev.sixbridge.manager\n11240 1234 dev.sixbridge.manager\n11240 777 dev.sixbridge.manager:worker\n';;
    logcat) [ "$2" = -d ] && [ "$3" = --pid ] && [ "$4" = 1234 ] && [ "$5" = -t ] && [ "$6" = 1000 ] && printf '스마트 컨텍스트 메뉴\n';;
    *) exit 2;;
  esac;;
  install) exit 9;; *) exit 2;;
esac
EOF
    chmod +x "$fixture/bin/sixbridge" "$fixture/bin/adb"; : >"$fixture/adb.log"; : >"$fixture/cli.log"
    SIXBRIDGE_CLI="$fixture/bin/sixbridge" SIXBRIDGE_EVIDENCE_ROOT="$fixture/evidence" SIXBRIDGE_GATE_SKIP_CONFIRMATIONS=1 SIXBRIDGE_DEVICE_DRAFT_PATH='/sdcard/Download/valid.sixdraft' SIXBRIDGE_TEST_ADB_LOG="$fixture/adb.log" SIXBRIDGE_TEST_CLI_LOG="$fixture/cli.log" SIXBRIDGE_TEST_EXPECTED_PACKAGE="$expected_package" SIXBRIDGE_TEST_DRAFT_SHA256="$(sha256_file "$valid_draft")" PATH="$fixture/bin:$PATH" "$0" --draft "$valid_draft" --serial "$REQUIRED_SERIAL" --expected-name '스마트 컨텍스트 메뉴' --expected-package "$expected_package" >/dev/null
    actual_extension=${valid_draft##*.}; draft_size=$(stat -f '%z' "$valid_draft"); sha256=$(sha256_file "$valid_draft"); adb_install_invocations=$(grep -Ec '^install([[:space:]]|$)' "$fixture/adb.log" || true)
    test "$actual_extension" = sixdraft; test "$draft_size" -le 268435456; test -n "$sha256"; test "$adb_install_invocations" -eq 0
    grep -Fx 'shell pm path --user 0 dev.sixbridge.manager' "$fixture/adb.log" >/dev/null
    grep -Fx 'shell pm list packages --user 0 --show-versioncode dev.sixbridge.manager' "$fixture/adb.log" >/dev/null
    grep -Fx "shell pm list packages --user 0 $expected_package" "$fixture/adb.log" >/dev/null
    grep -Fx 'shell pm list packages -U --user 0 dev.sixbridge.manager' "$fixture/adb.log" >/dev/null
    grep -Fx 'shell ps -n -u 11240 -o UID,PID,NAME' "$fixture/adb.log" >/dev/null
    grep -Fx 'shell logcat -d --pid 1234 -t 1000' "$fixture/adb.log" >/dev/null
    ! grep -Fq 'shell logcat -d --pid 999 ' "$fixture/adb.log" || fail 'Secure Folder PID가 logcat 대상으로 선택됐습니다.'
    grep -F '/evidence/' "$fixture/cli.log" | grep -F '/verified-input.sixdraft' >/dev/null
    [ ! -e "$(tail -n 1 "$fixture/cli.log")" ] || fail '성공 후 verified snapshot이 남았습니다.'
    ! grep -Fq 'shell dumpsys ' "$fixture/adb.log" || fail 'self-test가 user 비범위 dumpsys를 호출했습니다.'
    [ "$(grep -c '^shell pm list packages' "$fixture/adb.log")" -eq 3 ] ||
        fail 'self-test가 허용된 user 0 package 조회 외의 명령을 호출했습니다.'
    test -f "$(find "$fixture/evidence" -maxdepth 1 -type f -name '*-desktop-sixdraft-gate.log' -print -quit)"
    expect_self_test_failure() {
        local label=$1 adb_log
        shift
        mkdir -p "$fixture/evidence/pre-$label"
        adb_log="$fixture/${label}-adb.log"
        : >"$adb_log"
        if SIXBRIDGE_CLI="$fixture/bin/sixbridge" SIXBRIDGE_EVIDENCE_ROOT="$fixture/evidence/pre-$label" SIXBRIDGE_TEST_ADB_LOG="$adb_log" SIXBRIDGE_TEST_CLI_LOG="$fixture/cli.log" SIXBRIDGE_TEST_EXPECTED_PACKAGE="$expected_package" PATH="$fixture/bin:$PATH" "$0" "$@" >"$fixture/${label}.log" 2>&1; then
            fail "self-test에서 실패해야 하는 입력이 성공했습니다: $label"
        fi
        if [ -s "$adb_log" ]; then
            printf '예상하지 않은 adb 호출: %s\n' "$(tr '\n' ';' <"$adb_log")" >&2
            fail "Android 상태 변경 전 거부해야 하는 입력이 adb를 호출했습니다: $label"
        fi
        [ -z "$(find "$fixture/evidence/pre-$label" -type f -name verified-input.sixdraft -print -quit)" ] ||
            fail "실패 후 verified snapshot이 남았습니다: $label"
    }
    expect_self_test_failure wrong-extension --draft "$wrong_extension" --serial "$REQUIRED_SERIAL" --expected-name '스마트 컨텍스트 메뉴' --expected-package "$expected_package"
    expect_self_test_failure corrupt-draft --draft "$corrupt_draft" --serial "$REQUIRED_SERIAL" --expected-name '스마트 컨텍스트 메뉴' --expected-package "$expected_package"
    expect_self_test_failure missing-serial --draft "$valid_draft" --expected-name '스마트 컨텍스트 메뉴' --expected-package "$expected_package"
    expect_self_test_failure empty-expected-name --draft "$valid_draft" --serial "$REQUIRED_SERIAL" --expected-name '' --expected-package "$expected_package"
    expect_self_test_failure empty-expected-package --draft "$valid_draft" --serial "$REQUIRED_SERIAL" --expected-name '스마트 컨텍스트 메뉴' --expected-package ''
    mkdir -p "$fixture/evidence/version-mismatch" "$fixture/evidence/missing-wrapper"
    if SIXBRIDGE_CLI="$fixture/bin/sixbridge" SIXBRIDGE_EVIDENCE_ROOT="$fixture/evidence/version-mismatch" SIXBRIDGE_GATE_SKIP_CONFIRMATIONS=1 SIXBRIDGE_DEVICE_DRAFT_PATH='/sdcard/Download/valid.sixdraft' SIXBRIDGE_TEST_ADB_LOG="$fixture/adb.log" SIXBRIDGE_TEST_CLI_LOG="$fixture/cli.log" SIXBRIDGE_TEST_EXPECTED_PACKAGE="$expected_package" SIXBRIDGE_TEST_MANAGER_VERSION=1 SIXBRIDGE_TEST_DRAFT_SHA256="$(sha256_file "$valid_draft")" PATH="$fixture/bin:$PATH" "$0" --draft "$valid_draft" --serial "$REQUIRED_SERIAL" --expected-name '스마트 컨텍스트 메뉴' --expected-package "$expected_package" >/dev/null 2>&1; then
        fail 'manager versionCode 불일치가 성공했습니다.'
    fi
    if SIXBRIDGE_CLI="$fixture/bin/sixbridge" SIXBRIDGE_EVIDENCE_ROOT="$fixture/evidence/missing-wrapper" SIXBRIDGE_GATE_SKIP_CONFIRMATIONS=1 SIXBRIDGE_DEVICE_DRAFT_PATH='/sdcard/Download/valid.sixdraft' SIXBRIDGE_TEST_ADB_LOG="$fixture/adb.log" SIXBRIDGE_TEST_CLI_LOG="$fixture/cli.log" SIXBRIDGE_TEST_EXPECTED_PACKAGE="$expected_package" SIXBRIDGE_TEST_MISSING_WRAPPER=1 SIXBRIDGE_TEST_DRAFT_SHA256="$(sha256_file "$valid_draft")" PATH="$fixture/bin:$PATH" "$0" --draft "$valid_draft" --serial "$REQUIRED_SERIAL" --expected-name '스마트 컨텍스트 메뉴' --expected-package "$expected_package" >/dev/null 2>&1; then
        fail '예상 wrapper package 부재가 성공했습니다.'
    fi
    [ -z "$(find "$fixture/evidence/version-mismatch" "$fixture/evidence/missing-wrapper" -type f -name verified-input.sixdraft -print -quit)" ] ||
        fail '실패 후 verified snapshot이 남았습니다.'
    cleanup_root=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-desktop-sixdraft-gate-cleanup.XXXXXX")
    if TMPDIR="$cleanup_root" SIXBRIDGE_SELF_TEST_FORCE_FAILURE=1 "$0" --self-test >/dev/null 2>&1; then
        fail '강제 self-test 실패가 성공했습니다.'
    fi
    [ -z "$(find "$cleanup_root" -type d -name 'sixbridge-desktop-sixdraft-gate.*' -print -quit)" ] ||
        fail '실패한 self-test fixture가 정리되지 않았습니다.'
    rm -rf -- "$cleanup_root"
    trap - EXIT
    cleanup_self_test
    printf 'fold7 desktop SixDraft gate self-test 통과\n'
}

main "$@"
