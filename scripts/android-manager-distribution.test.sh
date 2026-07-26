#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly VERIFY_SCRIPT="${ROOT_DIR}/scripts/verify.sh"
readonly PACKAGE_SCRIPT="${ROOT_DIR}/scripts/package-manager-debug.sh"
readonly EXPECTED_APK_SHA256="cbd455d57e668f7cb6b74d1e9a49341a83eeca65787a8006d13c291f9a3161a9"

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    exit 1
}

assert_contains() {
    local file="$1"
    local expected="$2"
    local message="$3"

    grep -Fq -- "${expected}" "${file}" || fail "${message}"
}

new_fixture() {
    local fixture_dir

    fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-manager-distribution.XXXXXX")"
    mkdir -p \
        "${fixture_dir}/scripts" \
        "${fixture_dir}/fake-bin" \
        "${fixture_dir}/jdk/bin" \
        "${fixture_dir}/sdk/platforms/android-36" \
        "${fixture_dir}/sdk/build-tools/36.0.0" \
        "${fixture_dir}/sdk/cmdline-tools/latest/bin"
    cp "${VERIFY_SCRIPT}" "${fixture_dir}/scripts/verify.sh"
    chmod +x "${fixture_dir}/scripts/verify.sh"

    for script_name in \
        fold7-gate.sh \
        fold7-extension-validation.sh \
        fold7-ai-recipe.sh \
        fold7-video-nas.sh \
        fold7-converted-extension.sh \
        fold7-validation-lib.sh \
        fold7-extension-validation.test.sh \
        fold7-converted-extension.test.sh \
        verify-mobile-conversion.sh \
        android-manager-manifest.test.sh \
        android-manager-distribution.test.sh; do
        : >"${fixture_dir}/scripts/${script_name}"
    done

    apply_fixture_tools "${fixture_dir}"
    printf '%s\n' "${fixture_dir}"
}

apply_fixture_tools() {
    local fixture_dir="$1"

    printf '%s\n' \
        '#!/bin/sh' \
        'printf "openjdk version \"17.0.1\" 2026-01-01\n" >&2' \
        >"${fixture_dir}/jdk/bin/java"
    printf '%s\n' \
        '#!/bin/sh' \
        'printf "gradle %s\n" "$*" >>"$COMMAND_LOG"' \
        'case " $* " in' \
        '  *" :android-manager:assembleDebug "*)' \
        '    mkdir -p "$FIXTURE_ROOT/android-manager/build/outputs/apk/debug"' \
        '    printf "verified-apk\n" >"$FIXTURE_ROOT/android-manager/build/outputs/apk/debug/android-manager-debug.apk"' \
        '    ;;' \
        'esac' \
        >"${fixture_dir}/fake-bin/gradle"
    printf '%s\n' \
        '#!/bin/sh' \
        'printf "node %s\n" "$*" >>"$COMMAND_LOG"' \
        >"${fixture_dir}/fake-bin/node"
    printf '%s\n' \
        '#!/bin/sh' \
        'printf "bash %s\n" "$*" >>"$COMMAND_LOG"' \
        >"${fixture_dir}/fake-bin/bash"
    printf '%s\n' \
        '#!/bin/sh' \
        'exit 0' \
        >"${fixture_dir}/sdk/build-tools/36.0.0/aapt2"
    printf '%s\n' \
        '#!/bin/sh' \
        'exit 0' \
        >"${fixture_dir}/sdk/cmdline-tools/latest/bin/apkanalyzer"
    chmod +x \
        "${fixture_dir}/jdk/bin/java" \
        "${fixture_dir}/fake-bin/gradle" \
        "${fixture_dir}/fake-bin/node" \
        "${fixture_dir}/fake-bin/bash" \
        "${fixture_dir}/sdk/build-tools/36.0.0/aapt2" \
        "${fixture_dir}/sdk/cmdline-tools/latest/bin/apkanalyzer"
}

run_fixture_verify() {
    local fixture_dir="$1"
    local output_file="$2"

    COMMAND_LOG="${fixture_dir}/commands.log" \
        FIXTURE_ROOT="${fixture_dir}" \
        JAVA_HOME="${fixture_dir}/jdk" \
        ANDROID_HOME="${fixture_dir}/sdk" \
        PATH="${fixture_dir}/fake-bin:/usr/bin:/bin" \
        /bin/sh "${fixture_dir}/scripts/verify.sh" >"${output_file}" 2>&1
}

test_verify_runs_android_contract() {
    local fixture_dir
    local output_file
    local command_log

    fixture_dir="$(new_fixture)"
    output_file="${fixture_dir}/verify-output.txt"
    command_log="${fixture_dir}/commands.log"
    run_fixture_verify "${fixture_dir}" "${output_file}" ||
        fail "유효한 SDK/JDK fixture에서 verify.sh가 실패했습니다: $(cat "${output_file}")"

    assert_contains "${command_log}" ":bundle-contract:test" \
        "verify.sh가 bundle-contract test를 실행하지 않았습니다."
    assert_contains "${command_log}" ":android-manager:testDebugUnitTest" \
        "verify.sh가 Android unit test를 실행하지 않았습니다."
    assert_contains "${command_log}" ":android-manager:lintDebug" \
        "verify.sh가 Android lint를 실행하지 않았습니다."
    assert_contains "${command_log}" ":android-manager:assembleDebug" \
        "verify.sh가 Android debug APK를 조립하지 않았습니다."
    assert_contains "${command_log}" "bash scripts/android-manager-manifest.test.sh" \
        "verify.sh가 Android manifest 계약 검사를 실행하지 않았습니다."
    rm -rf -- "${fixture_dir}"
}

test_verify_rejects_missing_sdk() {
    local fixture_dir
    local output_file

    fixture_dir="$(new_fixture)"
    output_file="${fixture_dir}/missing-sdk.txt"
    if COMMAND_LOG="${fixture_dir}/commands.log" \
        FIXTURE_ROOT="${fixture_dir}" \
        JAVA_HOME="${fixture_dir}/jdk" \
        ANDROID_HOME="${fixture_dir}/missing-sdk" \
        PATH="${fixture_dir}/fake-bin:/usr/bin:/bin" \
        /bin/sh "${fixture_dir}/scripts/verify.sh" >"${output_file}" 2>&1; then
        fail "verify.sh가 누락된 Android SDK를 허용했습니다."
    fi
    assert_contains "${output_file}" "Android SDK" \
        "Android SDK 누락 안내가 한글 오류에 없습니다."
    assert_contains "${output_file}" "ANDROID_HOME" \
        "Android SDK 누락 안내에 설정할 환경 변수가 없습니다."
    rm -rf -- "${fixture_dir}"
}

test_verify_rejects_missing_jdk() {
    local fixture_dir
    local output_file

    fixture_dir="$(new_fixture)"
    output_file="${fixture_dir}/missing-jdk.txt"
    if COMMAND_LOG="${fixture_dir}/commands.log" \
        FIXTURE_ROOT="${fixture_dir}" \
        JAVA_HOME="${fixture_dir}/missing-jdk" \
        SIXBRIDGE_DEFAULT_JAVA_HOME="${fixture_dir}/missing-default-jdk" \
        ANDROID_HOME="${fixture_dir}/sdk" \
        PATH="${fixture_dir}/fake-bin:/usr/bin:/bin" \
        /bin/sh "${fixture_dir}/scripts/verify.sh" >"${output_file}" 2>&1; then
        fail "verify.sh가 누락된 JDK를 허용했습니다."
    fi
    assert_contains "${output_file}" "JDK" \
        "JDK 누락 안내가 한글 오류에 없습니다."
    assert_contains "${output_file}" "JAVA_HOME" \
        "JDK 누락 안내에 설정할 환경 변수가 없습니다."
    rm -rf -- "${fixture_dir}"
}

test_verify_uses_installed_fallback_for_stale_java_home() {
    local fixture_dir
    local output_file

    fixture_dir="$(new_fixture)"
    output_file="${fixture_dir}/fallback-jdk.txt"
    if ! COMMAND_LOG="${fixture_dir}/commands.log" \
        FIXTURE_ROOT="${fixture_dir}" \
        JAVA_HOME="${fixture_dir}/missing-jdk" \
        SIXBRIDGE_DEFAULT_JAVA_HOME="${fixture_dir}/jdk" \
        ANDROID_HOME="${fixture_dir}/sdk" \
        PATH="${fixture_dir}/fake-bin:/usr/bin:/bin" \
        /bin/sh "${fixture_dir}/scripts/verify.sh" >"${output_file}" 2>&1; then
        fail "verify.sh가 설치된 fallback JDK를 사용하지 못했습니다: $(cat "${output_file}")"
    fi
    rm -rf -- "${fixture_dir}"
}

test_verify_rejects_jdk_older_than_17() {
    local fixture_dir
    local output_file

    fixture_dir="$(new_fixture)"
    output_file="${fixture_dir}/old-jdk.txt"
    printf '%s\n' \
        '#!/bin/sh' \
        'printf "openjdk version \"11.0.29\" 2025-10-21\n" >&2' \
        >"${fixture_dir}/jdk/bin/java"
    chmod +x "${fixture_dir}/jdk/bin/java"

    if COMMAND_LOG="${fixture_dir}/commands.log" \
        FIXTURE_ROOT="${fixture_dir}" \
        JAVA_HOME="${fixture_dir}/jdk" \
        ANDROID_HOME="${fixture_dir}/sdk" \
        PATH="${fixture_dir}/fake-bin:/usr/bin:/bin" \
        /bin/sh "${fixture_dir}/scripts/verify.sh" >"${output_file}" 2>&1; then
        fail "verify.sh가 JDK 11을 허용했습니다."
    fi
    assert_contains "${output_file}" "JDK 17 이상" \
        "낮은 JDK 버전 안내에 최소 버전이 없습니다."
    rm -rf -- "${fixture_dir}"
}

test_package_requires_fresh_verification() {
    local fixture_dir
    local output_file
    local packaged_apk

    [[ -f "${PACKAGE_SCRIPT}" ]] ||
        fail "package-manager-debug.sh가 없습니다."
    fixture_dir="$(new_fixture)"
    cp "${PACKAGE_SCRIPT}" "${fixture_dir}/scripts/package-manager-debug.sh"
    chmod +x "${fixture_dir}/scripts/package-manager-debug.sh"
    output_file="${fixture_dir}/package-output.txt"
    packaged_apk="${fixture_dir}/dist/sixbridge-manager-debug.apk"

    COMMAND_LOG="${fixture_dir}/commands.log" \
        FIXTURE_ROOT="${fixture_dir}" \
        JAVA_HOME="${fixture_dir}/jdk" \
        ANDROID_HOME="${fixture_dir}/sdk" \
        PATH="${fixture_dir}/fake-bin:/usr/bin:/bin" \
        /bin/bash "${fixture_dir}/scripts/package-manager-debug.sh" >"${output_file}" 2>&1 ||
        fail "검증 성공 뒤 package-manager-debug.sh가 실패했습니다: $(cat "${output_file}")"

    [[ "$(cat "${packaged_apk}")" == "verified-apk" ]] ||
        fail "검증에서 새로 조립한 APK가 dist에 복사되지 않았습니다."
    assert_contains "${output_file}" "${EXPECTED_APK_SHA256}" \
        "패키징 결과에 새 APK의 SHA-256이 없습니다."

    printf 'stale-source\n' \
        >"${fixture_dir}/android-manager/build/outputs/apk/debug/android-manager-debug.apk"
    printf 'stale-dist\n' >"${packaged_apk}"
    printf '%s\n' \
        '#!/bin/sh' \
        'printf "의도한 검증 실패\n" >&2' \
        'exit 42' \
        >"${fixture_dir}/scripts/verify.sh"
    chmod +x "${fixture_dir}/scripts/verify.sh"

    if /bin/bash "${fixture_dir}/scripts/package-manager-debug.sh" \
        >"${fixture_dir}/failed-package-output.txt" 2>&1; then
        fail "검증 실패 뒤 stale APK가 패키징됐습니다."
    fi
    [[ ! -e "${packaged_apk}" ]] ||
        fail "검증 실패 뒤 이전 dist APK가 남았습니다."
    rm -rf -- "${fixture_dir}"
}

test_gitignore_excludes_local_release_outputs() {
    local ignored_path

    for ignored_path in \
        "dist/sixbridge-manager-debug.apk" \
        "android-manager/build/outputs/apk/debug/android-manager-debug.apk" \
        "android-manager/schemas/.tmp/generated.json" \
        "android-manager/schemas/dev.sixbridge.manager.catalog.ManagerDatabase/2.json.tmp" \
        "local.properties" \
        "android-manager/local.properties" \
        ".local/evidence/manager-validation.log" \
        ".local/keys/wrapper.p12"; do
        git -C "${ROOT_DIR}" check-ignore -q "${ignored_path}" ||
            fail "Git 제외 규칙이 로컬 산출물을 포함하지 않습니다: ${ignored_path}"
    done
}

test_verify_runs_android_contract
test_verify_rejects_missing_sdk
test_verify_rejects_missing_jdk
test_verify_uses_installed_fallback_for_stale_java_home
test_verify_rejects_jdk_older_than_17
test_package_requires_fresh_verification
test_gitignore_excludes_local_release_outputs

printf 'PASS: Android manager verification and packaging contracts\n'
