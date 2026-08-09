#!/usr/bin/env bash

# Test-only source hook. Production callers source release-lib.sh only; this
# hook deliberately replaces the resolver after the production library loads.
release_tool() {
    local name=${1:-} path=''
    case "${name}" in
        apksigner) path=${SIXBRIDGE_APKSIGNER:-} ;;
        apkanalyzer) path=${SIXBRIDGE_APKANALYZER:-} ;;
        hdiutil) path=${SIXBRIDGE_HDIUTIL:-} ;;
        plistbuddy) path=${SIXBRIDGE_PLISTBUDDY:-} ;;
        codesign) path=${SIXBRIDGE_CODESIGN:-} ;;
        jq) path=${SIXBRIDGE_JQ:-/usr/bin/jq} ;;
        shasum) path=${SIXBRIDGE_SHASUM:-/usr/bin/shasum} ;;
        *) release_fail '알 수 없는 test release 도구입니다.'; return 1 ;;
    esac
    [[ "${path}" = /* && -x "${path}" && ! -L "${path}" ]] || {
        release_fail 'test release 검증 도구를 실행할 수 없습니다.'
        return 1
    }
    printf '%s\n' "${path}"
}
