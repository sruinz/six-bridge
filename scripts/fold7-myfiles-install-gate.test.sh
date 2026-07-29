#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly GATE_SCRIPT="${ROOT_DIR}/scripts/fold7-myfiles-install-gate.sh"
readonly EXTENSION_ID="abcdefghijklmnopabcdefghijklmnop"
readonly PACKAGE_NAME="dev.sixbridge.ext.${EXTENSION_ID}"
readonly SIGNER_SHA256="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
readonly OTHER_EXTENSION_ID="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
readonly OTHER_PACKAGE_NAME="dev.sixbridge.ext.${OTHER_EXTENSION_ID}"
readonly OTHER_SIGNER_SHA256="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local file=$1
    local expected=$2
    local message=$3
    grep -Fq -- "$expected" "$file" || {
        cat "$file" >&2
        fail "$message"
    }
}

expect_exit_3() {
    local expected=$1
    local label=$2
    shift 2
    local output="${FIXTURE_ROOT}/${label}.log"
    local status
    set +e
    "$@" >"$output" 2>&1
    status=$?
    set -e
    [ "$status" -eq 3 ] || {
        cat "$output" >&2
        fail "$label gate exit code가 3이 아닙니다: $status"
    }
    assert_contains "$output" "$expected" "$label 거부 이유가 없습니다."
}

sha256_file() {
    shasum -a 256 "$1" | awk '{print $1}'
}

make_bundle() {
    local bundle_work="${FIXTURE_ROOT}/bundle"
    local wrapper_sha
    mkdir -p "$bundle_work"
    printf 'fixture wrapper bytes\n' >"${bundle_work}/wrapper.apk"
    wrapper_sha=$(sha256_file "${bundle_work}/wrapper.apk")
    cat >"${bundle_work}/extension.json" <<EOF
{"schemaVersion":1,"extensionId":"${EXTENSION_ID}","wrapperSha256":"${wrapper_sha}","wrapperPackage":"${PACKAGE_NAME}","wrapperVersionCode":7,"signerSha256":"${SIGNER_SHA256}"}
EOF
    printf '{"schemaVersion":4,"status":"COMPATIBLE"}\n' \
        >"${bundle_work}/compatibility.json"
    (
        cd "$bundle_work"
        zip -q -0 "${FIXTURE_ROOT}/fixture.six" \
            wrapper.apk extension.json compatibility.json
    )
    cp "${bundle_work}/wrapper.apk" "${FIXTURE_ROOT}/device-installed.apk"
}

make_fixture_tools() {
    mkdir -p \
        "${FIXTURE_ROOT}/bin" \
        "${FIXTURE_ROOT}/sdk/build-tools/36.0.0" \
        "${FIXTURE_ROOT}/sdk/cmdline-tools/latest/bin" \
        "${FIXTURE_ROOT}/evidence" \
        "${FIXTURE_ROOT}/tmp"
    : >"${FIXTURE_ROOT}/manager.apk"

    cat >"${FIXTURE_ROOT}/bin/date" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  +%Y%m%d-%H%M%S) printf '20260729-120000\n';;
  *) /bin/date "$@";;
esac
EOF

    cat >"${FIXTURE_ROOT}/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$SIXBRIDGE_TEST_ADB_LOG"
[ "${1:-}" = -s ] || exit 20
shift 2
phase=${ADB_PHASE:-post}
generation=${ADB_TARGET_GENERATION:-}
if [ -z "$generation" ]; then
  if [ "$phase" = pre ]; then generation=old; else generation=new; fi
fi
if [ "$generation" = old ]; then
  last_update='2026-07-29 11:59:00'
  generation_number=100
else
  last_update='2026-07-29 12:01:00'
  generation_number=200
fi
case "${1:-}" in
  get-state)
    if [ "${ADB_REPLACE_LOCK_OWNER:-0}" = 1 ]; then
      cat >"$SIXBRIDGE_TEST_LOCK_OWNER_FILE" <<OWNER
hostId=$(hostname)
pid=$PPID
token=foreign-token
OWNER
    fi
    printf 'device\n'
    ;;
  shell)
    shift
    case "$*" in
      'getprop ro.product.model')
        printf 'SM-F966N\n'
        ;;
      'date +%s')
        printf '1722225600\n'
        ;;
      log\ -t\ SixBridgeMyFilesGate\ *)
        printf '%s\n' "${!#}" \
          >"$SIXBRIDGE_TEST_MARKER_FILE"
        ;;
      "dumpsys package ${SIXBRIDGE_TEST_PACKAGE}")
        if [ "${ADB_TARGET_PRESENT:-1}" = 0 ]; then
          printf 'Unable to find package: %s\n' "$SIXBRIDGE_TEST_PACKAGE"
          exit 0
        fi
        printf 'Packages:\n'
        printf '  Package [%s]\n' "$SIXBRIDGE_TEST_PACKAGE"
        printf '    codePath=/data/app/%s\n' "$generation"
        printf '    lastUpdateTime=%s\n' "$last_update"
        printf '    installerPackageName=%s\n' \
          "${ADB_INSTALLER_PACKAGE:-com.google.android.packageinstaller}"
        printf '    initiatingPackageName=com.google.android.packageinstaller\n'
        if [ -n "${ADB_INITIATING_PACKAGE+x}" ]; then
          printf '    initiatingPackageName=%s\n' "$ADB_INITIATING_PACKAGE"
        fi
        printf '    originatingPackageName=%s\n' \
          "${ADB_ORIGINATING_PACKAGE:-com.sec.android.app.myfiles}"
        printf '    packageSource=%s\n' "${ADB_PACKAGE_SOURCE:-3}"
        ;;
      "pm path ${SIXBRIDGE_TEST_PACKAGE}")
        if [ "${ADB_PM_PATH_MODE:-normal}" = transport-error ]; then
          printf 'error: device offline\n' >&2
          exit 1
        fi
        [ "${ADB_TARGET_PRESENT:-1}" != 0 ] || exit 1
        printf 'package:/data/app/%s/base.apk\n' "$generation"
        ;;
      stat\ -c\ %i:%s:%Y\ /data/app/*/base.apk)
        printf '%s:22:%s\n' "$generation_number" "$generation_number"
        ;;
      *'cmd package resolve-activity --brief --user 0'*'-d internet://extension'*)
        printf 'com.sec.android.app.sbrowser/com.sec.android.app.sbrowser.SBrowserMainActivity\n'
        ;;
      *'cmd package resolve-activity --brief --user 0'*'-d six://ex'*)
        printf '%s/dev.sixbridge.runtime.SixDiscoveryActivity\n' \
          "$SIXBRIDGE_TEST_PACKAGE"
        ;;
      *)
        printf 'unexpected adb shell: %s\n' "$*" >&2
        exit 21
        ;;
    esac
    ;;
  exec-out)
    shift
    [ "${1:-}" = cat ] || exit 22
    if [ "${ADB_INSTALLED_SHA_MISMATCH:-0}" = 1 ]; then
      printf 'different installed wrapper bytes\n'
    else
      /bin/cat "$SIXBRIDGE_TEST_INSTALLED_APK"
    fi
    ;;
  logcat)
    if [ "${ADB_LOG_MARKER:-present}" = present ] &&
        [ -f "$SIXBRIDGE_TEST_MARKER_FILE" ]; then
      if [ "${ADB_LOG_MARKER_FORMAT:-compact}" = padded ]; then
        printf '         1722225600.123 13001 13001 I SixBridgeMyFilesGate: %s\n' \
          "$(<"$SIXBRIDGE_TEST_MARKER_FILE")"
      else
        printf '1722225600.123 1 1 I SixBridgeMyFilesGate: %s\n' \
          "$(<"$SIXBRIDGE_TEST_MARKER_FILE")"
      fi
    fi
    if [ "${ADB_SAMSUNG_LOGS:-complete}" = complete ]; then
      if [ "${ADB_LOG_TARGET:-target}" = target ]; then
        log_package=$SIXBRIDGE_TEST_PACKAGE
        log_extension=$SIXBRIDGE_TEST_EXTENSION
      else
        log_package=$SIXBRIDGE_TEST_OTHER_PACKAGE
        log_extension=$SIXBRIDGE_TEST_OTHER_EXTENSION
      fi
      if [ "${ADB_LOG_TIMING:-after}" = before ]; then
        pkg_epoch=1722225600.100
        support_epoch=1722225600.110
      else
        pkg_epoch=1722225601.000
        support_epoch=1722225602.000
      fi
      printf '%s 1 1 I SixPkgUpdater: isDevSixPkg - MyFiles case: %s\n' \
        "$pkg_epoch" \
        "$log_package"
      printf '%s 1 1 I SixAppDataUpdater: updateSixAppData - Allowed to be supported: %s\n' \
        "$support_epoch" \
        "$log_extension"
    else
      printf '1722225601.000 1 1 I SixPkgUpdater: unrelated\n'
    fi
    ;;
  *)
    printf 'unexpected adb: %s\n' "$*" >&2
    exit 23
    ;;
esac
EOF

    cat >"${FIXTURE_ROOT}/sdk/cmdline-tools/latest/bin/apkanalyzer" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  "manifest print ${SIXBRIDGE_TEST_MANAGER_APK}")
    cat <<MANIFEST
<manifest package="dev.sixbridge.manager">
  <queries>
    <package android:name="com.sec.android.app.myfiles" />
  </queries>
</manifest>
MANIFEST
    ;;
  dex\ code\ --class\ dev.sixbridge.manager.install.SystemInstallCoordinator\ *)
    printf '.class SystemInstallCoordinator\n'
    ;;
  dex\ code\ --class\ dev.sixbridge.manager.app.DefaultManagerRepository\ *)
    printf '.class DefaultManagerRepository\n'
    ;;
  manifest\ print\ *)
    if [ "${APKANALYZER_EXTENSION_ASSOCIATION:-valid}" = split ]; then
      cat <<MANIFEST
<manifest android:versionCode="${APKANALYZER_VERSION:-7}" package="${APKANALYZER_PACKAGE:-$SIXBRIDGE_TEST_PACKAGE}">
  <application>
    <meta-data android:name="extension-id" android:value="${SIXBRIDGE_TEST_OTHER_EXTENSION}" />
    <meta-data android:name="other-id" android:value="${SIXBRIDGE_TEST_EXTENSION}" />
  </application>
</manifest>
MANIFEST
    else
      cat <<MANIFEST
<manifest android:versionCode="7" package="${SIXBRIDGE_TEST_PACKAGE}">
  <application>
    <meta-data android:name="extension-id" android:value="${SIXBRIDGE_TEST_EXTENSION}" />
  </application>
</manifest>
MANIFEST
    fi
    ;;
  manifest\ application-id\ *)
    printf '%s\n' "${APKANALYZER_PACKAGE:-$SIXBRIDGE_TEST_PACKAGE}"
    ;;
  manifest\ version-code\ *)
    printf '%s\n' "${APKANALYZER_VERSION:-7}"
    ;;
  *)
    printf 'unexpected apkanalyzer: %s\n' "$*" >&2
    exit 24
    ;;
esac
EOF

    cat >"${FIXTURE_ROOT}/sdk/build-tools/36.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'Verifies\n'
printf 'Signer #1 certificate SHA-256 digest: %s\n' \
  "${APKSIGNER_SIGNER:-$SIXBRIDGE_TEST_SIGNER}"
EOF
    chmod +x \
        "${FIXTURE_ROOT}/bin/date" \
        "${FIXTURE_ROOT}/bin/adb" \
        "${FIXTURE_ROOT}/sdk/cmdline-tools/latest/bin/apkanalyzer" \
        "${FIXTURE_ROOT}/sdk/build-tools/36.0.0/apksigner"
}

make_fixture() {
    FIXTURE_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-myfiles-gate-test.XXXXXX")
    FIXTURE_ROOT=$(CDPATH= cd -- "$FIXTURE_ROOT" && pwd -P)
    make_fixture_tools
    make_bundle
    : >"${FIXTURE_ROOT}/adb.log"
}

run_gate() {
    local mode=$1
    local serial=$2
    local input=$3
    local bash_trace=
    shift 3
    if [ "${SIXBRIDGE_TEST_XTRACE:-0}" = 1 ]; then
        bash_trace=-x
    fi
    env \
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        TMPDIR="${FIXTURE_ROOT}/tmp" \
        ANDROID_HOME="${FIXTURE_ROOT}/sdk" \
        MANAGER_APK="${FIXTURE_ROOT}/manager.apk" \
        SIXBRIDGE_EVIDENCE_ROOT="${FIXTURE_ROOT}/evidence" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" \
        SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" \
        SIXBRIDGE_TEST_OTHER_PACKAGE="$OTHER_PACKAGE_NAME" \
        SIXBRIDGE_TEST_OTHER_EXTENSION="$OTHER_EXTENSION_ID" \
        SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" \
        SIXBRIDGE_TEST_MANAGER_APK="${FIXTURE_ROOT}/manager.apk" \
        SIXBRIDGE_TEST_INSTALLED_APK="${FIXTURE_ROOT}/device-installed.apk" \
        SIXBRIDGE_TEST_MARKER_FILE="${FIXTURE_ROOT}/marker.log" \
        SIXBRIDGE_TEST_LOCK_FILE="${FIXTURE_ROOT}/evidence/.myfiles-install-gate-lock-serial-1" \
        SIXBRIDGE_TEST_LOCK_OWNER_FILE="${FIXTURE_ROOT}/evidence/.myfiles-install-gate-lock-serial-1.owner" \
        "$@" \
        bash ${bash_trace:+"$bash_trace"} "$GATE_SCRIPT" "$mode" "$serial" "$input"
}

run_pre() {
    run_gate pre serial-1 "${FIXTURE_ROOT}/fixture.six" ADB_PHASE=pre "$@"
}

run_post() {
    run_gate post serial-1 "$PACKAGE_NAME" ADB_PHASE=post "$@"
}

current_pointer() {
    printf '%s/evidence/.myfiles-install-gate-current-serial-1\n' "$FIXTURE_ROOT"
}

lock_file() {
    printf '%s/evidence/.myfiles-install-gate-lock-serial-1\n' "$FIXTURE_ROOT"
}

lock_owner_file() {
    printf '%s.owner\n' "$(lock_file)"
}

write_lock_owner() {
    local pid=$1
    local token=$2
    local host_id=${3:-$(hostname)}
    printf '%s\n' "$pid" >"$(lock_file)"
    cat >"$(lock_owner_file)" <<EOF
hostId=${host_id}
pid=${pid}
token=${token}
EOF
}

write_lock_claim() {
    local pid=$1
    local token=$2
    cat >"$(lock_file).claim.${pid}" <<EOF
hostId=$(hostname)
pid=${pid}
token=${token}
EOF
}

test_success_and_required_failures() {
    local pre_output="${FIXTURE_ROOT}/pre.log"
    local post_output="${FIXTURE_ROOT}/post.log"
    local evidence

    run_pre >"$pre_output"
    assert_contains "$pre_output" 'mode=pre' 'pre 요약이 없습니다.'
    assert_contains "$pre_output" "package=${PACKAGE_NAME}" 'pre package baseline이 없습니다.'
    assert_contains "$pre_output" 'versionCode=7' 'pre versionCode baseline이 없습니다.'
    assert_contains "$pre_output" "signerSha256=${SIGNER_SHA256}" 'pre signer baseline이 없습니다.'
    assert_contains "$pre_output" "extensionId=${EXTENSION_ID}" 'pre extension ID baseline이 없습니다.'
    evidence=$(sed -n 's/^evidence=//p' "$pre_output")
    [ -f "${evidence}/baseline.env" ] || fail 'pre baseline.env가 없습니다.'
    if find "${FIXTURE_ROOT}/tmp" -mindepth 1 -maxdepth 1 \
        -name 'sixbridge-myfiles-gate.*' | grep -q .; then
        fail 'pre 임시 추출 디렉터리가 정리되지 않았습니다.'
    fi

    run_post >"$post_output"
    for expected in \
        'installer=com.google.android.packageinstaller' \
        'originating=com.sec.android.app.myfiles' \
        'classification=dev_ext' \
        'support=Allowed to be supported' \
        'wrapperByteIdentity=matched'; do
        assert_contains "$post_output" "$expected" "post 합격 요약이 없습니다: $expected"
    done

    run_pre >/dev/null
    expect_exit_3 '삼성 내 파일 origin이 아닙니다.' manager-origin \
        run_post ADB_ORIGINATING_PACKAGE=dev.sixbridge.manager

    run_pre >/dev/null
    expect_exit_3 '설치 APK SHA-256이 pre baseline과 다릅니다.' sha-mismatch \
        run_post ADB_INSTALLED_SHA_MISMATCH=1

    run_pre >/dev/null
    expect_exit_3 '삼성 지원 로그를 찾지 못했습니다.' samsung-log-missing \
        run_post ADB_SAMSUNG_LOGS=missing
}

test_unsafe_bundles_are_rejected() {
    local malformed="${FIXTURE_ROOT}/malformed"
    local symlink_work="${FIXTURE_ROOT}/symlink-bundle"
    local traversal_work="${FIXTURE_ROOT}/traversal-bundle"
    mkdir -p "$malformed" "$symlink_work" "$traversal_work"

    cp "${FIXTURE_ROOT}/bundle/extension.json" \
        "${FIXTURE_ROOT}/bundle/compatibility.json" "$symlink_work"
    ln -s ../device-installed.apk "${symlink_work}/wrapper.apk"
    (
        cd "$symlink_work"
        zip -q -y "${malformed}/symlink.six" \
            wrapper.apk extension.json compatibility.json
    )
    expect_exit_3 '.six symlink entry를 허용하지 않습니다.' symlink \
        run_gate pre serial-1 "${malformed}/symlink.six"

    cp "${FIXTURE_ROOT}/bundle/"* "$traversal_work"
    mkdir -p "${traversal_work}/inside"
    (
        cd "${traversal_work}/inside"
        zip -q -0 "${malformed}/traversal.six" \
            ../wrapper.apk ../extension.json ../compatibility.json
    )
    expect_exit_3 '.six 경로 이탈 entry를 허용하지 않습니다.' traversal \
        run_gate pre serial-1 "${malformed}/traversal.six"

    (
        cd "${FIXTURE_ROOT}/bundle"
        bsdtar --format zip -cf "${malformed}/duplicate.six" \
            wrapper.apk wrapper.apk extension.json compatibility.json
    )
    expect_exit_3 '.six 중복 entry를 허용하지 않습니다.' duplicate \
        run_gate pre serial-1 "${malformed}/duplicate.six"
}

test_unchanged_target_with_unrelated_logs_is_rejected() {
    run_pre >/dev/null
    expect_exit_3 'target 설치 세대가 pre 이후 변경되지 않았습니다.' \
        unchanged-unrelated \
        run_post ADB_TARGET_GENERATION=old ADB_LOG_TARGET=other
}

test_fresh_target_with_unrelated_logs_is_rejected() {
    run_pre >/dev/null
    expect_exit_3 'target-bound 삼성 지원 로그를 찾지 못했습니다.' \
        fresh-unrelated run_post ADB_LOG_TARGET=other
}

test_target_logs_before_marker_are_rejected() {
    run_pre >/dev/null
    expect_exit_3 'target-bound 삼성 지원 로그를 찾지 못했습니다.' \
        target-before-marker run_post \
        ADB_LOG_TIMING=before ADB_LOG_MARKER=missing
}

test_saved_marker_boundary_survives_logcat_rollover() {
    local output="${FIXTURE_ROOT}/marker-rollover.log"
    run_pre >/dev/null
    if ! run_post ADB_LOG_MARKER=missing >"$output" 2>&1; then
        cat "$output" >&2
        fail 'current logcat에서 marker가 rollover된 정상 post가 거부됐습니다.'
    fi
    assert_contains "$output" 'wrapperByteIdentity=matched' \
        '저장된 marker 경계 post 성공 요약이 없습니다.'
}

test_saved_marker_boundary_accepts_logcat_padding() {
    local output="${FIXTURE_ROOT}/marker-padding.log"
    run_pre ADB_LOG_MARKER_FORMAT=padded >/dev/null
    if ! run_post ADB_LOG_MARKER=missing >"$output" 2>&1; then
        cat "$output" >&2
        fail '공백이 있는 저장 marker의 rollover post가 거부됐습니다.'
    fi
    assert_contains "$output" 'wrapperByteIdentity=matched' \
        '공백이 있는 저장 marker post 성공 요약이 없습니다.'
}

test_saved_marker_evidence_is_fail_closed() {
    local pointer run_dir marker original epoch nonce
    pointer=$(current_pointer)
    run_pre >/dev/null
    run_dir=$(<"$pointer")
    marker="${run_dir}/pre-log-marker.txt"
    original="${FIXTURE_ROOT}/pre-log-marker.original"
    cp "$marker" "$original"
    epoch=$(sed -n 's/^preLogEpoch=//p' "${run_dir}/baseline.env")
    nonce=$(sed -n 's/^preLogNonce=//p' "${run_dir}/baseline.env")

    rm "$marker"
    expect_exit_3 '저장된 pre log marker evidence를 찾지 못했습니다.' \
        marker-missing run_post ADB_LOG_MARKER=missing

    ln -s "$original" "$marker"
    expect_exit_3 '저장된 pre log marker evidence를 찾지 못했습니다.' \
        marker-symlink run_post ADB_LOG_MARKER=missing
    rm "$marker"

    printf 'malformed marker\n' >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-malformed run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: other-nonce\n' \
        "$epoch" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-nonce-mismatch run_post ADB_LOG_MARKER=missing

    printf '1722225599.999 1 1 I SixBridgeMyFilesGate: %s\n' \
        "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-epoch-mismatch run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: %s\ntrailing-record-without-newline' \
        "$epoch" "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-extra-record run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: %s unexpected-field\n' \
        "$epoch" "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-extra-field run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: %s \n' \
        "$epoch" "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-trailing-space run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: %s\r\n' \
        "$epoch" "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-crlf run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: %s\0\n' \
        "$epoch" "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-nul run_post ADB_LOG_MARKER=missing

    printf '%s 1 1 I SixBridgeMyFilesGate: %s' \
        "$epoch" "$nonce" >"$marker"
    expect_exit_3 \
        '저장된 pre log marker evidence가 baseline과 일치하지 않습니다.' \
        marker-missing-final-lf run_post ADB_LOG_MARKER=missing
}

test_fresh_target_with_target_bound_logs_succeeds() {
    local pre_output="${FIXTURE_ROOT}/fresh-pre.log"
    local output="${FIXTURE_ROOT}/fresh-target.log"
    local absent_output="${FIXTURE_ROOT}/absent-target.log"
    if ! run_pre >"$pre_output" 2>&1; then
        cat "$pre_output" >&2
        fail 'fresh target pre가 실패했습니다.'
    fi
    if ! run_post >"$output" 2>&1; then
        cat "$output" >&2
        fail 'fresh target과 target-bound 삼성 로그가 거부됐습니다.'
    fi
    assert_contains "$output" 'wrapperByteIdentity=matched' \
        'fresh target 성공 요약이 없습니다.'

    run_pre ADB_TARGET_PRESENT=0 >/dev/null
    if ! run_post >"$absent_output" 2>&1; then
        cat "$absent_output" >&2
        fail 'pre 때 없던 target의 신규 설치가 거부됐습니다.'
    fi
    assert_contains "$absent_output" 'wrapperByteIdentity=matched' \
        'pre absent 신규 설치 성공 요약이 없습니다.'
}

test_failed_pre_preserves_previous_pointer() {
    local pointer previous
    pointer=$(current_pointer)
    run_pre >/dev/null
    previous=$(<"$pointer")
    expect_exit_3 '.six bundle을 찾지 못했거나 symlink입니다:' failed-pre \
        run_gate pre serial-1 "${FIXTURE_ROOT}/missing.six" ADB_PHASE=pre
    [ -f "$pointer" ] || fail '실패한 pre가 기존 pointer를 삭제했습니다.'
    [ "$(<"$pointer")" = "$previous" ] ||
        fail '실패한 pre가 기존 pointer를 변경했습니다.'
}

test_successful_post_consumes_pointer() {
    local pointer
    pointer=$(current_pointer)
    run_pre >/dev/null
    run_post >/dev/null
    [ ! -e "$pointer" ] ||
        fail '성공한 post가 current pointer를 소비하지 않았습니다.'
    expect_exit_3 '성공한 My Files pre evidence를 찾지 못했습니다.' \
        repeated-post run_post
}

test_pm_transport_error_is_rejected() {
    expect_exit_3 'pre target 설치 상태를 확정하지 못했습니다.' \
        pm-transport run_pre ADB_PM_PATH_MODE=transport-error
}

test_known_absent_target_is_allowed() {
    run_pre ADB_TARGET_PRESENT=0 >/dev/null
    run_post >/dev/null
}

test_stale_dead_lock_is_recovered() {
    write_lock_owner 999999 stale-token
    sleep 1
    run_pre >/dev/null
    [ ! -e "$(lock_file)" ] ||
        fail 'dead local owner의 stale lock이 정리되지 않았습니다.'
    [ ! -e "$(lock_owner_file)" ] ||
        fail 'dead local owner의 stale owner가 정리되지 않았습니다.'

    write_lock_owner 999998 stale-claim
    write_lock_claim 999998 stale-claim
    cat >"$(lock_owner_file)" <<EOF
hostId=$(hostname)
pid=999997
token=previous-owner
EOF
    sleep 1
    run_pre >/dev/null
    [ ! -e "$(lock_file)" ] ||
        fail 'owner 게시 전 crash의 stale lock이 정리되지 않았습니다.'
    [ ! -e "$(lock_file).claim.999998" ] ||
        fail 'owner 게시 전 crash의 stale claim이 정리되지 않았습니다.'
}

test_live_serial_lock_is_rejected() {
    write_lock_owner "$$" live-token
    expect_exit_3 '같은 serial의 gate가 이미 실행 중입니다:' \
        held-lock run_pre
    rm -f -- "$(lock_file)" "$(lock_owner_file)"
}

test_lock_cleanup_is_token_safe() {
    run_pre ADB_REPLACE_LOCK_OWNER=1 >/dev/null
    [ -f "$(lock_file)" ] ||
        fail '소유권 token이 바뀐 lock을 cleanup이 삭제했습니다.'
    assert_contains "$(lock_owner_file)" 'token=foreign-token' \
        '교체된 lock owner token이 보존되지 않았습니다.'
    rm -f -- "$(lock_file)" "$(lock_owner_file)"
}

test_completion_failure_preserves_pointer_and_retries() {
    local pointer run_dir
    pointer=$(current_pointer)
    run_pre >/dev/null
    run_dir=$(<"$pointer")
    mkdir "${run_dir}/post-completed.txt"
    expect_exit_3 'post completion evidence를 기록하지 못했습니다.' \
        completion-write run_post
    [ -f "$pointer" ] ||
        fail 'completion 기록 실패가 current pointer를 삭제했습니다.'
    [ "$(<"$pointer")" = "$run_dir" ] ||
        fail 'completion 기록 실패가 current pointer를 변경했습니다.'
    rmdir "${run_dir}/post-completed.txt"
    run_post >/dev/null
    [ ! -e "$pointer" ] ||
        fail 'completion 재시도 성공 후 current pointer가 남았습니다.'
}

test_failed_post_can_retry_same_pointer() {
    local pointer run_dir
    pointer=$(current_pointer)
    run_pre >/dev/null
    run_dir=$(<"$pointer")
    expect_exit_3 '삼성 지원 로그를 찾지 못했습니다.' \
        retry-first run_post ADB_SAMSUNG_LOGS=missing
    [ "$(<"$pointer")" = "$run_dir" ] ||
        fail '실패한 post가 current pointer를 변경했습니다.'
    run_post >/dev/null
    [ ! -e "$pointer" ] ||
        fail '같은 pointer의 post 재시도 성공 후 pointer가 남았습니다.'
}

expect_post_contract_failure() {
    local expected=$1
    local label=$2
    shift 2
    run_pre >/dev/null
    expect_exit_3 "$expected" "$label" run_post "$@"
}

test_post_identity_and_source_contracts() {
    expect_post_contract_failure \
        '설치 APK package가 pre baseline과 다릅니다.' package-mismatch \
        APKANALYZER_PACKAGE="$OTHER_PACKAGE_NAME"
    expect_post_contract_failure \
        '설치 APK versionCode가 pre baseline과 다릅니다.' version-mismatch \
        APKANALYZER_VERSION=8
    expect_post_contract_failure \
        '설치 APK signer가 pre baseline과 다릅니다.' signer-mismatch \
        APKSIGNER_SIGNER="$OTHER_SIGNER_SHA256"
    expect_post_contract_failure \
        '설치 APK extension ID를 확인하지 못했습니다.' extension-association \
        APKANALYZER_EXTENSION_ASSOCIATION=split
    expect_post_contract_failure \
        'Google package installer 설치 기록이 아닙니다:' installer-mismatch \
        ADB_INSTALLER_PACKAGE=other.installer
    expect_post_contract_failure \
        'Google package installer initiating 기록이 아닙니다:' initiating-mismatch \
        ADB_INITIATING_PACKAGE=other.installer
    expect_post_contract_failure \
        'packageSource가 사용자 설치 값 3이 아닙니다.' package-source \
        ADB_PACKAGE_SOURCE=99
}

main() {
    [ -f "$GATE_SCRIPT" ] || fail "host gate script가 없습니다: $GATE_SCRIPT"
    make_fixture
    trap 'rm -rf -- "${FIXTURE_ROOT:-}"' EXIT
    case "${1:-all}" in
        unchanged)
            test_unchanged_target_with_unrelated_logs_is_rejected
            ;;
        fresh)
            test_fresh_target_with_target_bound_logs_succeeds
            ;;
        atomic-pre)
            test_failed_pre_preserves_previous_pointer
            ;;
        consume)
            test_successful_post_consumes_pointer
            ;;
        lock)
            test_stale_dead_lock_is_recovered
            test_live_serial_lock_is_rejected
            test_lock_cleanup_is_token_safe
            ;;
        pm-error)
            test_pm_transport_error_is_rejected
            ;;
        absent)
            test_known_absent_target_is_allowed
            ;;
        stale-lock)
            test_stale_dead_lock_is_recovered
            ;;
        live-lock)
            test_live_serial_lock_is_rejected
            ;;
        token-cleanup)
            test_lock_cleanup_is_token_safe
            ;;
        completion)
            test_completion_failure_preserves_pointer_and_retries
            ;;
        log-coverage)
            test_fresh_target_with_unrelated_logs_is_rejected
            test_target_logs_before_marker_are_rejected
            ;;
        marker-boundary)
            test_saved_marker_boundary_survives_logcat_rollover
            test_saved_marker_boundary_accepts_logcat_padding
            test_saved_marker_evidence_is_fail_closed
            test_target_logs_before_marker_are_rejected
            ;;
        marker-rollover)
            test_saved_marker_boundary_survives_logcat_rollover
            test_saved_marker_boundary_accepts_logcat_padding
            ;;
        marker-validation)
            test_saved_marker_evidence_is_fail_closed
            ;;
        marker-before)
            test_target_logs_before_marker_are_rejected
            ;;
        retry)
            test_failed_post_can_retry_same_pointer
            ;;
        all)
            test_success_and_required_failures
            test_unsafe_bundles_are_rejected
            test_unchanged_target_with_unrelated_logs_is_rejected
            test_fresh_target_with_unrelated_logs_is_rejected
            test_target_logs_before_marker_are_rejected
            test_saved_marker_boundary_survives_logcat_rollover
            test_saved_marker_boundary_accepts_logcat_padding
            test_saved_marker_evidence_is_fail_closed
            test_fresh_target_with_target_bound_logs_succeeds
            test_failed_pre_preserves_previous_pointer
            test_successful_post_consumes_pointer
            test_pm_transport_error_is_rejected
            test_known_absent_target_is_allowed
            test_stale_dead_lock_is_recovered
            test_live_serial_lock_is_rejected
            test_lock_cleanup_is_token_safe
            test_completion_failure_preserves_pointer_and_retries
            test_failed_post_can_retry_same_pointer
            test_post_identity_and_source_contracts
            ;;
        *)
            fail "알 수 없는 fixture case: $1"
            ;;
    esac
    printf 'PASS: Fold7 삼성 내 파일 설치 gate fixture\n'
}

main "$@"
