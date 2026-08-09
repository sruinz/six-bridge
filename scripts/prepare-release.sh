#!/usr/bin/env bash
set -euo pipefail

ROOT_INPUT="${SIXBRIDGE_RELEASE_ROOT:-$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)}"
[[ -d "${ROOT_INPUT}" && ! -L "${ROOT_INPUT}" ]] || {
    printf 'release 검증 실패: release root는 symbolic link가 아닌 directory여야 합니다.\n' >&2
    exit 1
}
ROOT_DIR="$(CDPATH= cd -P -- "${ROOT_INPUT}" && pwd -P)"
readonly ROOT_DIR

# shellcheck source=release-lib.sh
source "${ROOT_DIR}/scripts/release-lib.sh"

readonly KEYSTORE="${ROOT_DIR}/.local/keystore/six-bridge-manager-release.p12"
readonly RELEASE_NOTES="${ROOT_DIR}/docs/releases/v0.1.0-ko.md"
readonly RELEASE_CONTAINER="${ROOT_DIR}/.local/releases"
readonly FINAL_DIR="${RELEASE_CONTAINER}/v0.1.0"
readonly LOCK_DIR="${RELEASE_CONTAINER}/.sixbridge-v0.1.0.lock"
readonly PUBLISH_HELPER_SOURCE="${ROOT_DIR}/scripts/release-atomic-publish.pl"
readonly ANDROID_OUTPUT="${ROOT_DIR}/android-manager/build/outputs/apk/release/android-manager-release.apk"
readonly DMG_OUTPUT="${ROOT_DIR}/desktop-app/build/compose/binaries/main/dmg/SIX Bridge-1.0.0.dmg"

STAGE_DIR=''
STAGE_IDENTITY=''
PUBLISH_DIR=''
PUBLISH_IDENTITY=''
PUBLISH_HELPER=''
PUBLISH_HELPER_IDENTITY=''
PUBLISH_HELPER_SOURCE_IDENTITY=''
PUBLISH_PERL_PRIVLIB=''
PUBLISH_PERL_ARCHLIB=''
RELEASE_TMPDIR=''
CONTAINER_IDENTITY=''
LOCK_IDENTITY=''
OWNED_WORK_DIRECTORIES=()
ACTIVE_CHILD_PID=''
ACTIVE_CHILD_PGID=''

stable_directory_identity() {
    local identity
    identity=$(release_stage_identity "$1") || return 1
    printf '%s\n' "${identity%:*}"
}

remove_owned_directory() {
    local path=$1 expected=$2 actual
    [[ -n "${path}" && -n "${expected}" ]] || return 0
    [[ -d "${path}" && ! -L "${path}" ]] || return 0
    release_validate_stage_root "${path}" "${RELEASE_CONTAINER}" || return 0
    actual=$(stable_directory_identity "${path}") || return 0
    [[ "${actual}" = "${expected}" ]] || return 0
    /bin/rm -rf -- "${path}"
}

release_register_owned_work_directory() {
    local path=$1 identity
    release_validate_stage_root "${path}" "${RELEASE_CONTAINER}" || return 1
    identity=$(stable_directory_identity "${path}") || return 1
    OWNED_WORK_DIRECTORIES+=("${path}|${identity}")
}

cleanup_owned_work_directories() {
    local record path identity
    for record in "${OWNED_WORK_DIRECTORIES[@]:-}"; do
        [[ -n "${record}" ]] || continue
        path=${record%%|*}
        identity=${record#*|}
        remove_owned_directory "${path}" "${identity}" || true
    done
}

cleanup_stage() {
    remove_owned_directory "${STAGE_DIR}" "${STAGE_IDENTITY%:*}" || true
    cleanup_owned_work_directories || true
    remove_owned_directory "${LOCK_DIR}" "${LOCK_IDENTITY}" || true
}

terminate_preparation_worker() {
    local pid=${ACTIVE_CHILD_PID:-} pgroup=${ACTIVE_CHILD_PGID:-}
    [[ -n "${pid}" ]] || return 0
    if [[ -n "${pgroup}" ]]; then
        /bin/kill -TERM -- "-${pgroup}" 2>/dev/null || true
    fi
    /bin/kill -TERM "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
    ACTIVE_CHILD_PID=''
    ACTIVE_CHILD_PGID=''
}

release_signal_exit() {
    local status=$1
    trap - HUP INT TERM
    terminate_preparation_worker
    exit "${status}"
}

release_worker_signal_exit() {
    local status=$1
    trap - HUP INT TERM
    cleanup_stage
    exit "${status}"
}

run_preparation_worker() {
    local status java_path java_home node_path rg_path gradle_home local_root physical_local
    local runtime_path resolved_node resolved_rg
    java_path=$(release_java_executable) || return 1
    java_home=${java_path%/bin/java}
    node_path=$(release_node_executable) || return 1
    rg_path=$(release_ripgrep_executable) || return 1
    [[ -d "${java_home}" && ! -L "${java_home}" ]] || return 1
    local_root="${ROOT_DIR}/.local"
    release_trusted_directory "${local_root}" || return 1
    physical_local="$(CDPATH= cd -P -- "${local_root}" && pwd -P)" || return 1
    [[ "${physical_local}" = "${ROOT_DIR}/.local" ]] || {
        release_fail '.local canonical containment가 올바르지 않습니다.'
        return 1
    }
    gradle_home="${ROOT_DIR}/.local/gradle-home"
    [[ ! -L "${gradle_home}" ]] || { release_fail 'GRADLE_USER_HOME symbolic link는 허용되지 않습니다.'; return 1; }
    if [[ ! -e "${gradle_home}" ]]; then
        (umask 077; /bin/mkdir -- "${gradle_home}") || return 1
    fi
    release_private_directory "${gradle_home}" || return 1
    [[ "$(CDPATH= cd -P -- "${gradle_home}" && pwd -P)" = "${ROOT_DIR}/.local/gradle-home" ]] || { release_fail 'GRADLE_USER_HOME containment가 올바르지 않습니다.'; return 1; }
    [[ -w "${gradle_home}" && -x "${gradle_home}" ]] || {
        release_fail 'GRADLE_USER_HOME은 write 가능한 private directory여야 합니다.'
        return 1
    }
    runtime_path="/usr/bin:/bin:$(/usr/bin/dirname -- "${node_path}"):$(/usr/bin/dirname -- "${rg_path}")"
    resolved_node=$(/usr/bin/env -i PATH="${runtime_path}" HOME=/var/empty LC_ALL=C \
        /bin/bash --noprofile --norc -c 'command -v node') || {
        release_fail 'sanitized PATH에서 node를 찾지 못했습니다.'
        return 1
    }
    [[ "${resolved_node}" = "${node_path}" ]] || {
        release_fail 'sanitized PATH의 node가 검증된 canonical 도구와 다릅니다.'
        return 1
    }
    resolved_rg=$(/usr/bin/env -i PATH="${runtime_path}" HOME=/var/empty LC_ALL=C \
        /bin/bash --noprofile --norc -c 'command -v rg') || {
        release_fail 'sanitized PATH에서 rg를 찾지 못했습니다.'
        return 1
    }
    [[ "${resolved_rg}" = "${rg_path}" ]] || {
        release_fail 'sanitized PATH의 rg가 검증된 canonical 도구와 다릅니다.'
        return 1
    }
    set -m
    /usr/bin/env -i PATH="${runtime_path}" JAVA_HOME="${java_home}" GRADLE_USER_HOME="${gradle_home}" HOME=/var/empty LANG=C LC_ALL=C \
        SIXBRIDGE_PREPARE_WORKER=1 \
        SIXBRIDGE_RELEASE_ROOT="${SIXBRIDGE_RELEASE_ROOT:-}" \
        SIXBRIDGE_PREPARE_FIXTURE_BIN="${SIXBRIDGE_PREPARE_FIXTURE_BIN:-}" \
        SIXBRIDGE_PREPARE_LOG="${SIXBRIDGE_PREPARE_LOG:-}" \
        SIXBRIDGE_PREPARE_SIGNER="${SIXBRIDGE_PREPARE_SIGNER:-}" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD:-}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD:-}" \
        SIXBRIDGE_PREPARE_VERIFY_FAIL="${SIXBRIDGE_PREPARE_VERIFY_FAIL:-}" \
        SIXBRIDGE_PREPARE_ANDROID_FAIL="${SIXBRIDGE_PREPARE_ANDROID_FAIL:-}" \
        SIXBRIDGE_PREPARE_DMG_BUILD_FAIL="${SIXBRIDGE_PREPARE_DMG_BUILD_FAIL:-}" \
        SIXBRIDGE_PREPARE_APK_VERIFY_FAIL="${SIXBRIDGE_PREPARE_APK_VERIFY_FAIL:-}" \
        SIXBRIDGE_PREPARE_JQ_FAIL="${SIXBRIDGE_PREPARE_JQ_FAIL:-}" \
        SIXBRIDGE_PREPARE_MUTATE_SOURCE="${SIXBRIDGE_PREPARE_MUTATE_SOURCE:-}" \
        SIXBRIDGE_PREPARE_MUTATE_HEAD="${SIXBRIDGE_PREPARE_MUTATE_HEAD:-}" \
        SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION="${SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION:-}" \
        SIXBRIDGE_PREPARE_PUBLISH_BARRIER="${SIXBRIDGE_PREPARE_PUBLISH_BARRIER:-}" \
        SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER="${SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER:-}" \
        SIXBRIDGE_PREPARE_WAIT_IN_VERIFY="${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-}" \
        SIXBRIDGE_PREPARE_READY="${SIXBRIDGE_PREPARE_READY:-}" \
        SIXBRIDGE_PREPARE_CONTINUE="${SIXBRIDGE_PREPARE_CONTINUE:-}" \
        SIXBRIDGE_PREPARE_CHILD_PID="${SIXBRIDGE_PREPARE_CHILD_PID:-}" \
        SIXBRIDGE_PREPARE_TMPDIR_RECORD="${SIXBRIDGE_PREPARE_TMPDIR_RECORD:-}" \
        /usr/bin/perl -e '$SIG{HUP} = "DEFAULT"; $SIG{INT} = "DEFAULT"; $SIG{TERM} = "DEFAULT"; exec @ARGV or die "worker exec failed\n";' \
        /usr/bin/env "$0" "$@" &
    ACTIVE_CHILD_PID=$!
    ACTIVE_CHILD_PGID=$!
    if wait "${ACTIVE_CHILD_PID}"; then
        status=0
    else
        status=$?
    fi
    ACTIVE_CHILD_PID=''
    ACTIVE_CHILD_PGID=''
    set +m
    return "${status}"
}

trap cleanup_stage EXIT
trap 'release_signal_exit 129' HUP
trap 'release_signal_exit 130' INT
trap 'release_signal_exit 143' TERM

require_android_credentials() {
    local store_password=${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD:-}
    local key_password=${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD:-}
    [[ -n "${store_password}" ]] || {
        release_fail 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD를 설정하세요.'
        return 1
    }
    [[ -n "${key_password}" ]] || {
        release_fail 'SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD를 설정하세요.'
        return 1
    }
    [[ "${#store_password}" -ge 16 && "${#key_password}" -ge 16 ]] || {
        release_fail 'release key 비밀번호는 각각 16자 이상이어야 합니다.'
        return 1
    }
    [[ "${store_password}" = "${key_password}" ]] || {
        release_fail 'store 및 key 비밀번호는 동일해야 합니다.'
        return 1
    }
}

validate_keystore() {
    local keystore_dir physical_dir expected_dir mode
    release_trusted_directory "${ROOT_DIR}/.local" || return 1
    keystore_dir="${ROOT_DIR}/.local/keystore"
    release_private_directory "${keystore_dir}" || return 1
    physical_dir="$(CDPATH= cd -P -- "${keystore_dir}" && pwd -P)"
    expected_dir="${ROOT_DIR}/.local/keystore"
    [[ "${physical_dir}" = "${expected_dir}" ]] || {
        release_fail 'release keystore parent가 프로젝트 .local/keystore 밖을 가리킵니다.'
        return 1
    }
    release_private_file "${KEYSTORE}" || return 1
    mode=$(release_stat_value '%Lp' "${KEYSTORE}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#077)) = 0 ]] || {
        release_fail 'release keystore mode는 0600 상당이어야 합니다.'
        return 1
    }
}

release_keytool() {
    local java_path keytool_path
    java_path=$(release_java_executable) || return 1
    keytool_path="${java_path%/java}/keytool"
    release_trusted_executable "${keytool_path}" || return 1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME="${ROOT_DIR}" TMPDIR="${RELEASE_TMPDIR:-/tmp}" LANG=C LC_ALL=C \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD}" \
        "${keytool_path}" "$@"
}

release_git() {
    release_system_executable /usr/bin/git || return 1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME="${ROOT_DIR}" TMPDIR=/tmp LANG=C LC_ALL=C \
        /usr/bin/git -C "${ROOT_DIR}" "$@"
}

release_clean_commit() {
    local status commit
    if ! status=$(release_git status --porcelain=v1 --untracked-files=all 2>&1); then
        release_fail 'git worktree 상태를 읽지 못했습니다.'
        return 1
    fi
    [[ -z "${status}" ]] || {
        release_fail 'release 준비는 clean git worktree에서만 실행할 수 있습니다.'
        return 1
    }
    if ! commit=$(release_git rev-parse HEAD 2>&1); then
        release_fail '현재 git commit을 읽지 못했습니다.'
        return 1
    fi
    [[ "${commit}" =~ ^[0-9a-f]{40}$ ]] || {
        release_fail '현재 git commit 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${commit}"
}

read_signer_sha256() {
    local raw_output signer_line signer
    if ! raw_output=$(release_keytool -list -v -storetype PKCS12 -keystore "${KEYSTORE}" \
        -storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
        -alias six-bridge-manager 2>&1); then
        release_fail 'keytool로 Android release signer를 읽지 못했습니다.'
        return 1
    fi
    signer_line=$(printf '%s\n' "${raw_output}" | /usr/bin/awk '/^[[:space:]]*SHA256:[[:space:]]*/ { print; found++ } END { if (found != 1) exit 1 }') || {
        release_fail 'keytool signer SHA-256 fingerprint가 정확히 하나여야 합니다.'
        return 1
    }
    signer=$(printf '%s' "${signer_line#*:}" | /usr/bin/tr -d '[:space:]:' | /usr/bin/tr '[:upper:]' '[:lower:]')
    [[ "${signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'keytool signer SHA-256 fingerprint 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${signer}"
}

create_release_container() {
    [[ ! -L "${ROOT_DIR}/.local" && -d "${ROOT_DIR}/.local" ]] || {
        release_fail '.local directory가 필요합니다.'
        return 1
    }
    if [[ ! -e "${RELEASE_CONTAINER}" && ! -L "${RELEASE_CONTAINER}" ]]; then
        (umask 077 && /bin/mkdir "${RELEASE_CONTAINER}") || {
            release_fail 'release staging container를 만들지 못했습니다.'
            return 1
        }
    fi
    release_validate_release_container "${RELEASE_CONTAINER}"
}

acquire_release_lock() {
    if ! (umask 077 && /bin/mkdir "${LOCK_DIR}"); then
        release_fail '같은 release version 준비가 이미 실행 중입니다.'
        return 1
    fi
    release_validate_stage_root "${LOCK_DIR}" "${RELEASE_CONTAINER}" || return 1
    LOCK_IDENTITY=$(stable_directory_identity "${LOCK_DIR}") || return 1
}

create_release_temp_directory() {
    RELEASE_TMPDIR=$(/usr/bin/mktemp -d "${RELEASE_CONTAINER}/.sixbridge-release-tmp.XXXXXX") || {
        release_fail 'release private TMPDIR을 만들지 못했습니다.'
        return 1
    }
    release_validate_stage_root "${RELEASE_TMPDIR}" "${RELEASE_CONTAINER}" || return 1
    release_register_owned_work_directory "${RELEASE_TMPDIR}" || return 1
    export TMPDIR="${RELEASE_TMPDIR}"
}

copy_to_stage() {
    local source=$1 destination=$2
    release_regular_file "${source}" || return 1
    /bin/cp -p -- "${source}" "${destination}" || {
        release_fail 'release artifact를 stage에 복사하지 못했습니다.'
        return 1
    }
    release_require_single_link_file "${destination}" || return 1
    STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}") || return 1
    CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}") || return 1
}

capture_release_file_identities() {
    local directory=$1 file
    for file in "${SIXBRIDGE_RELEASE_FILES[@]}"; do
        printf '%s|%s\n' "${file}" "$(release_file_identity "${directory}/${file}")" || return 1
    done
}

assert_release_file_identities() {
    local directory=$1 identities=$2 file identity
    while IFS='|' read -r file identity; do
        [[ -n "${file}" ]] || continue
        release_assert_file_identity "${directory}/${file}" "${identity}" || return 1
    done <<<"${identities}"
}

assert_exact_release_entries() {
    local directory=$1 entries entry basename count=0
    if ! entries=$(/usr/bin/find "${directory}" -maxdepth 1 -mindepth 1 -print 2>&1); then
        release_fail 'release directory를 읽지 못했습니다.'
        return 1
    fi
    while IFS= read -r entry; do
        [[ -n "${entry}" ]] || continue
        basename=$(/usr/bin/basename -- "${entry}")
        case "${basename}" in
            "${SIXBRIDGE_ANDROID_FILE}"|"${SIXBRIDGE_MACOS_FILE}"|SHA256SUMS|RELEASE-METADATA.json|RELEASE-NOTES-KO.md)
                release_require_single_link_file "${entry}" || return 1
                count=$((count + 1))
                ;;
            *)
                release_fail 'release directory는 정확히 다섯 개의 필수 release file만 가져야 합니다.'
                return 1
                ;;
        esac
    done <<<"${entries}"
    [[ "${count}" = 5 ]] || release_fail '필수 release file이 누락되었습니다.'
}

create_publish_snapshot() {
    local file
    PUBLISH_DIR=$(/usr/bin/mktemp -d "${RELEASE_CONTAINER}/.sixbridge-release-publish.XXXXXX") || {
        release_fail 'publish snapshot directory를 만들지 못했습니다.'
        return 1
    }
    release_validate_stage_root "${PUBLISH_DIR}" "${RELEASE_CONTAINER}" || return 1
    release_register_owned_work_directory "${PUBLISH_DIR}" || return 1
    for file in "${SIXBRIDGE_RELEASE_FILES[@]}"; do
        release_require_single_link_file "${STAGE_DIR}/${file}" || return 1
        /bin/cp -p -- "${STAGE_DIR}/${file}" "${PUBLISH_DIR}/${file}" || {
            release_fail '검증된 release artifact를 publish snapshot에 복사하지 못했습니다.'
            return 1
        }
        release_require_single_link_file "${PUBLISH_DIR}/${file}" || return 1
    done
    PUBLISH_IDENTITY=$(release_stage_identity "${PUBLISH_DIR}") || return 1
    CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}") || return 1
}

compile_atomic_publish_helper() {
    local source_identity perl perl_contract
    release_system_executable /usr/bin/env || return 1
    release_darwin_atomic_publish_preflight || return 1
    perl_contract=$(release_system_perl) || return 1
    IFS='|' read -r perl PUBLISH_PERL_PRIVLIB PUBLISH_PERL_ARCHLIB <<<"${perl_contract}"
    release_private_file "${PUBLISH_HELPER_SOURCE}" || return 1
    source_identity=$(release_file_identity "${PUBLISH_HELPER_SOURCE}") || return 1
    release_assert_file_identity "${PUBLISH_HELPER_SOURCE}" "${source_identity}" || return 1
    PUBLISH_HELPER="${perl}"
    PUBLISH_HELPER_IDENTITY=$(release_file_identity "${PUBLISH_HELPER}")
    PUBLISH_HELPER_SOURCE_IDENTITY="${source_identity}"
}

publish_manifest_identity() {
    local name=$1 manifest=$2 file identity
    while IFS='|' read -r file identity; do
        [[ "${file}" = "${name}" ]] && { printf '%s\n' "${identity}"; return 0; }
    done <<<"${manifest}"
    release_fail 'native publish identity manifest가 불완전합니다.'
}

publish_verified_snapshot() {
    local source_leaf final_leaf apk_identity dmg_identity sums_identity metadata_identity notes_identity status source_device source_inode source_size source_links source_digest source_fd_identity
    source_leaf=${PUBLISH_DIR##*/}
    final_leaf=${FINAL_DIR##*/}
    [[ "${source_leaf}" = .sixbridge-release-publish.* && "${final_leaf}" = v0.1.0 ]] || {
        release_fail 'atomic publish helper path가 올바르지 않습니다.'
        return 1
    }
    apk_identity=$(publish_manifest_identity "${SIXBRIDGE_ANDROID_FILE}" "${PUBLISH_FILE_IDENTITIES}") || return 1
    dmg_identity=$(publish_manifest_identity "${SIXBRIDGE_MACOS_FILE}" "${PUBLISH_FILE_IDENTITIES}") || return 1
    sums_identity=$(publish_manifest_identity SHA256SUMS "${PUBLISH_FILE_IDENTITIES}") || return 1
    metadata_identity=$(publish_manifest_identity RELEASE-METADATA.json "${PUBLISH_FILE_IDENTITIES}") || return 1
    notes_identity=$(publish_manifest_identity RELEASE-NOTES-KO.md "${PUBLISH_FILE_IDENTITIES}") || return 1
    release_assert_file_identity "${PUBLISH_HELPER}" "${PUBLISH_HELPER_IDENTITY}" || return 1
    release_assert_file_identity "${PUBLISH_HELPER_SOURCE}" "${PUBLISH_HELPER_SOURCE_IDENTITY}" || return 1
    exec 9<"${PUBLISH_HELPER_SOURCE}" || return 1
    IFS=: read -r source_device source_inode source_size source_links source_digest <<<"${PUBLISH_HELPER_SOURCE_IDENTITY}"
    source_fd_identity=$(/usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
        /usr/bin/perl -e 'open(my $fh, q{<&=9}) or exit 1; my @s = stat($fh); print "$s[0]:$s[1]:$s[7]\n";') || { exec 9<&-; return 1; }
    [[ "${source_fd_identity}" = "${source_device}:${source_inode}:${source_size}" ]] || { printf 'publisher source fd identity mismatch\n' >&2; exec 9<&-; return 1; }
    /usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty TMPDIR="${RELEASE_TMPDIR}" LANG=C LC_ALL=C \
        SIXBRIDGE_PERL_INC="${PUBLISH_PERL_PRIVLIB}:${PUBLISH_PERL_ARCHLIB}" \
        SIXBRIDGE_PUBLISHER_SOURCE_IDENTITY="${PUBLISH_HELPER_SOURCE_IDENTITY}" \
        "${PUBLISH_HELPER}" -e 'BEGIN { @INC = split(/:/, $ENV{SIXBRIDGE_PERL_INC}); } use Digest::SHA qw(sha256_hex); my $script = shift @ARGV; open(my $fh, q{<&=9}) or die "publisher fd open failed\n"; binmode($fh); my @s = stat($fh); my ($d,$i,$z,$n,$h) = split(/:/, $ENV{SIXBRIDGE_PUBLISHER_SOURCE_IDENTITY}, 5); @s && $s[0] == $d && $s[1] == $i && $s[7] == $z && $s[3] == $n or die "publisher fd identity failed\n"; local $/; my $bytes = <$fh>; defined($bytes) && sha256_hex($bytes) eq $h or die "publisher fd digest failed\n"; eval $bytes; die $@ if $@;' /dev/fd/9 "${RELEASE_CONTAINER}" "${RELEASE_CONTAINER}" \
        "${source_leaf}" "${final_leaf}" "${PUBLISH_IDENTITY}" "${CONTAINER_IDENTITY}" "${apk_identity}" "${dmg_identity}" \
        "${sums_identity}" "${metadata_identity}" "${notes_identity}"
    status=$?
    exec 9<&-
    return "${status}"
}

prepare_release_worker() {
release_require_version "${1:-}"
release_system_executable /usr/bin/env
require_android_credentials
validate_keystore
release_regular_file "${RELEASE_NOTES}"
START_COMMIT=$(release_clean_commit)
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}
create_release_container
acquire_release_lock
create_release_temp_directory
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}

cd "${ROOT_DIR}"
if ! "${ROOT_DIR}/scripts/verify.sh"; then
    release_fail '전체 검증에 실패했습니다.'
    exit 1
fi
if ! ./gradlew :android-manager:assembleRelease; then
    release_fail 'Android release build에 실패했습니다.'
    exit 1
fi
if ! /usr/bin/env -u SIXBRIDGE_MAC_SIGNING_IDENTITY \
    -u SIXBRIDGE_MAC_NOTARIZATION_APPLE_ID \
    -u SIXBRIDGE_MAC_NOTARIZATION_PASSWORD \
    -u SIXBRIDGE_MAC_NOTARIZATION_TEAM_ID \
    ./gradlew -Pcompose.desktop.packaging.checkJdkVendor=false :desktop-app:packageDmg; then
    release_fail 'macOS DMG build에 실패했습니다.'
    exit 1
fi

release_regular_file "${ANDROID_OUTPUT}"
release_regular_file "${DMG_OUTPUT}"
[[ "$(release_clean_commit)" = "${START_COMMIT}" ]] || {
    release_fail 'build 또는 검증 중 git worktree 또는 commit이 변경되었습니다.'
    exit 1
}
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}

STAGE_DIR=$(/usr/bin/mktemp -d "${RELEASE_CONTAINER}/.sixbridge-release-stage.XXXXXX") || {
    release_fail 'release stage directory를 만들지 못했습니다.'
    exit 1
}
release_validate_stage_root "${STAGE_DIR}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")

copy_to_stage "${ANDROID_OUTPUT}" "${STAGE_DIR}/${SIXBRIDGE_ANDROID_FILE}"
copy_to_stage "${DMG_OUTPUT}" "${STAGE_DIR}/${SIXBRIDGE_MACOS_FILE}"
copy_to_stage "${RELEASE_NOTES}" "${STAGE_DIR}/RELEASE-NOTES-KO.md"

SIGNER_SHA256=$(read_signer_sha256)
release_verify_apk "${STAGE_DIR}/${SIXBRIDGE_ANDROID_FILE}" "${SIGNER_SHA256}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"

DMG_WORK_DIR=$(/usr/bin/mktemp -d "${RELEASE_CONTAINER}/.sixbridge-prepare-dmg.XXXXXX") || {
    release_fail 'DMG verification work directory를 만들지 못했습니다.'
    exit 1
}
release_private_directory "${DMG_WORK_DIR}" || { /bin/rm -rf -- "${DMG_WORK_DIR}"; exit 1; }
release_register_owned_work_directory "${DMG_WORK_DIR}"
if ! release_verify_dmg "${STAGE_DIR}/${SIXBRIDGE_MACOS_FILE}" "${DMG_WORK_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"; then
    /bin/rm -rf -- "${DMG_WORK_DIR}"
    exit 1
fi
/bin/rm -rf -- "${DMG_WORK_DIR}"

release_scan_tree "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}"
GIT_COMMIT=$(release_clean_commit)
[[ "${GIT_COMMIT}" = "${START_COMMIT}" ]] || {
    release_fail 'build 또는 검증 중 git worktree 또는 commit이 변경되었습니다.'
    exit 1
}
release_write_metadata "${STAGE_DIR}" "${GIT_COMMIT}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")
release_write_checksums "${STAGE_DIR}" "${RELEASE_CONTAINER}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")
release_scan_tree "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}" "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD}"
RELEASE_FILE_IDENTITIES=$(capture_release_file_identities "${STAGE_DIR}")
release_verify_directory "${STAGE_DIR}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
assert_release_file_identities "${STAGE_DIR}" "${RELEASE_FILE_IDENTITIES}"
release_verify_checksums "${STAGE_DIR}" "${STAGE_DIR}" "${RELEASE_CONTAINER}"
assert_release_file_identities "${STAGE_DIR}" "${RELEASE_FILE_IDENTITIES}"
assert_exact_release_entries "${STAGE_DIR}"
STAGE_IDENTITY=$(release_stage_identity "${STAGE_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")

create_publish_snapshot
compile_atomic_publish_helper
PUBLISH_FILE_IDENTITIES=$(capture_release_file_identities "${PUBLISH_DIR}")
[[ ! -e "${FINAL_DIR}" && ! -L "${FINAL_DIR}" ]] || {
    release_fail 'release directory가 이미 존재합니다.'
    exit 1
}
release_verify_directory "${PUBLISH_DIR}" "${SIGNER_SHA256}" "${RELEASE_CONTAINER}"
assert_release_file_identities "${PUBLISH_DIR}" "${PUBLISH_FILE_IDENTITIES}"
release_verify_checksums "${PUBLISH_DIR}" "${PUBLISH_DIR}" "${RELEASE_CONTAINER}"
assert_release_file_identities "${PUBLISH_DIR}" "${PUBLISH_FILE_IDENTITIES}"
assert_exact_release_entries "${PUBLISH_DIR}"
PUBLISH_IDENTITY=$(release_stage_identity "${PUBLISH_DIR}")
CONTAINER_IDENTITY=$(release_container_identity "${RELEASE_CONTAINER}")

[[ "$(release_clean_commit)" = "${START_COMMIT}" ]] || {
    release_fail '게시 직전 git worktree 또는 commit이 변경되었습니다.'
    exit 1
}
release_assert_stage_identity "${PUBLISH_DIR}" "${RELEASE_CONTAINER}" "${PUBLISH_IDENTITY}" "${CONTAINER_IDENTITY}"
release_before_atomic_publish
if ! publish_verified_snapshot; then
    release_fail 'release directory를 atomic no-replace 방식으로 publish하지 못했습니다.'
    exit 1
fi
[[ ! -e "${PUBLISH_DIR}" && ! -L "${PUBLISH_DIR}" ]] || {
    release_fail 'release directory publish 중 기존 final path를 교체하지 못했습니다.'
    exit 1
}
FINAL_IDENTITY=$(stable_directory_identity "${FINAL_DIR}") || {
    release_fail '게시된 final release directory inode를 읽지 못했습니다.'
    exit 1
}
[[ "${FINAL_IDENTITY}" = "${PUBLISH_IDENTITY%:*}" ]] || {
    release_fail '게시된 final release directory inode가 stage와 다릅니다.'
    exit 1
}
PUBLISH_DIR=''
PUBLISH_IDENTITY=''

printf 'v%s release directory를 준비했습니다: %s\n' "${SIXBRIDGE_RELEASE_VERSION}" "${FINAL_DIR}"
}

if [[ "${SIXBRIDGE_PREPARE_WORKER:-}" = 1 ]]; then
    set +m
    trap cleanup_stage EXIT
    trap 'release_worker_signal_exit 129' HUP
    trap 'release_worker_signal_exit 130' INT
    trap 'release_worker_signal_exit 143' TERM
    prepare_release_worker "$@"
    exit $?
fi

run_preparation_worker "$@"
