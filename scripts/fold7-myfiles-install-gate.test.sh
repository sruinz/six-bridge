#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly GATE_SCRIPT="${ROOT_DIR}/scripts/fold7-myfiles-install-gate.sh"
readonly EXTENSION_ID="abcdefghijklmnopabcdefghijklmnop"
readonly PACKAGE_NAME="dev.sixbridge.ext.${EXTENSION_ID}"
readonly SIGNER_SHA256="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

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
case "${1:-}" in
  get-state)
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
      "dumpsys package ${SIXBRIDGE_TEST_PACKAGE}")
        printf 'Packages:\n'
        printf '  Package [%s]\n' "$SIXBRIDGE_TEST_PACKAGE"
        printf '    installerPackageName=com.google.android.packageinstaller\n'
        printf '    initiatingPackageName=com.google.android.packageinstaller\n'
        printf '    originatingPackageName=%s\n' \
          "${ADB_ORIGINATING_PACKAGE:-com.sec.android.app.myfiles}"
        printf '    packageSource=1\n'
        ;;
      "pm path ${SIXBRIDGE_TEST_PACKAGE}")
        printf 'package:/data/app/fixture/base.apk\n'
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
    if [ "${ADB_SAMSUNG_LOGS:-complete}" = complete ]; then
      printf '1722225601.000 1 1 I SixPkgUpdater: isDevSixPkg - MyFiles case\n'
      printf '1722225602.000 1 1 I SixAppDataUpdater: updateSixAppData - Allowed to be supported\n'
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
    cat <<MANIFEST
<manifest android:versionCode="7" package="${SIXBRIDGE_TEST_PACKAGE}">
  <application>
    <meta-data android:name="extension-id" android:value="${SIXBRIDGE_TEST_EXTENSION}" />
  </application>
</manifest>
MANIFEST
    ;;
  manifest\ application-id\ *)
    printf '%s\n' "$SIXBRIDGE_TEST_PACKAGE"
    ;;
  manifest\ version-code\ *)
    printf '7\n'
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
printf 'Signer #1 certificate SHA-256 digest: %s\n' "$SIXBRIDGE_TEST_SIGNER"
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
    shift 3
    env \
        PATH="${FIXTURE_ROOT}/bin:${PATH}" \
        TMPDIR="${FIXTURE_ROOT}/tmp" \
        ANDROID_HOME="${FIXTURE_ROOT}/sdk" \
        MANAGER_APK="${FIXTURE_ROOT}/manager.apk" \
        SIXBRIDGE_EVIDENCE_ROOT="${FIXTURE_ROOT}/evidence" \
        SIXBRIDGE_TEST_ADB_LOG="${FIXTURE_ROOT}/adb.log" \
        SIXBRIDGE_TEST_PACKAGE="$PACKAGE_NAME" \
        SIXBRIDGE_TEST_EXTENSION="$EXTENSION_ID" \
        SIXBRIDGE_TEST_SIGNER="$SIGNER_SHA256" \
        SIXBRIDGE_TEST_MANAGER_APK="${FIXTURE_ROOT}/manager.apk" \
        SIXBRIDGE_TEST_INSTALLED_APK="${FIXTURE_ROOT}/device-installed.apk" \
        "$@" \
        bash "$GATE_SCRIPT" "$mode" "$serial" "$input"
}

run_pre() {
    run_gate pre serial-1 "${FIXTURE_ROOT}/fixture.six" "$@"
}

run_post() {
    run_gate post serial-1 "$PACKAGE_NAME" "$@"
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

main() {
    [ -f "$GATE_SCRIPT" ] || fail "host gate script가 없습니다: $GATE_SCRIPT"
    make_fixture
    trap 'rm -rf -- "${FIXTURE_ROOT:-}"' EXIT
    test_success_and_required_failures
    test_unsafe_bundles_are_rejected
    printf 'PASS: Fold7 삼성 내 파일 설치 gate fixture\n'
}

main
