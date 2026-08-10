#!/bin/bash -p

builtin trap - DEBUG RETURN ERR
if (builtin return 0 2>/dev/null); then
    printf 'release 검증 실패: publish-release-tag.sh는 source할 수 없는 direct-exec entrypoint입니다.\n' >&2
    builtin return 64
fi

case $- in
    *p*) ;;
    *) printf 'release 검증 실패: publish-release-tag.sh는 privileged Bash direct-exec로 실행해야 합니다.\n' >&2; exit 1 ;;
esac
[[ "${BASH:-}" = /bin/bash ]] || {
    printf 'release 검증 실패: publish-release-tag.sh의 Bash interpreter가 올바르지 않습니다.\n' >&2
    exit 1
}
PATH=/usr/bin:/bin
export PATH
unset BASH_ENV ENV CDPATH GLOBIGNORE
export -n BASHOPTS SHELLOPTS
set -euo pipefail

bootstrap_stat_value() {
    local format=$1 path=$2 linux_format
    if [[ "$(/usr/bin/uname -s)" = Darwin ]]; then
        /usr/bin/stat -f "${format}" "${path}" 2>/dev/null
        return
    fi
    case "${format}" in
        '%d') linux_format='%d' ;; '%i') linux_format='%i' ;; '%z') linux_format='%s' ;;
        '%l') linux_format='%h' ;; '%u') linux_format='%u' ;; '%Lp') linux_format='%a' ;;
        '%c') linux_format='%Z' ;;
        *) return 1 ;;
    esac
    /usr/bin/stat -c "${linux_format}" "${path}" 2>/dev/null
}

bootstrap_file_identity() {
    local path=$1 device inode size links owner mode ctime
    [[ -f "${path}" && ! -L "${path}" ]] || return 1
    device=$(bootstrap_stat_value '%d' "${path}") || return 1
    inode=$(bootstrap_stat_value '%i' "${path}") || return 1
    size=$(bootstrap_stat_value '%z' "${path}") || return 1
    links=$(bootstrap_stat_value '%l' "${path}") || return 1
    owner=$(bootstrap_stat_value '%u' "${path}") || return 1
    mode=$(bootstrap_stat_value '%Lp' "${path}") || return 1
    ctime=$(bootstrap_stat_value '%c' "${path}") || return 1
    printf '%s:%s:%s:%s:%s:%s:%s\n' "${device}" "${inode}" "${size}" "${links}" "${owner}" "${mode}" "${ctime}"
}

bootstrap_require_source_file() {
    local path=$1 current_uid=$2 identity owner mode links
    identity=$(bootstrap_file_identity "${path}") || {
        printf 'release 검증 실패: entrypoint source는 symbolic link가 아닌 regular file이어야 합니다.\n' >&2
        return 1
    }
    IFS=: read -r _ _ _ links owner mode _ <<<"${identity}"
    [[ "${links}" = 1 && "${owner}" = "${current_uid}" && "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        printf 'release 검증 실패: entrypoint source trust가 올바르지 않습니다.\n' >&2
        return 1
    }
    printf '%s\n' "${identity}"
}

bootstrap_fd_identity() {
    local fd=$1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
        /usr/bin/perl -e '
            open(my $fh, q{<&=} . $ARGV[0]) or exit 1;
            my @s = stat($fh); @s or exit 1;
            printf "%s:%s:%s:%s:%s:%o:%s\n", @s[0,1,7,3,4], $s[2] & 07777, $s[10];
        ' "${fd}"
}

current_uid=$(/usr/bin/id -u) || exit 1
source_input=${BASH_SOURCE[0]}
[[ ! -L "${source_input}" ]] || {
    printf 'release 검증 실패: publish-release-tag.sh symbolic link entrypoint는 허용되지 않습니다.\n' >&2
    exit 1
}
source_parent=$(/usr/bin/dirname -- "${source_input}") || exit 1
source_leaf=${source_input##*/}
SCRIPT_DIR=$(CDPATH= builtin cd -P -- "${source_parent}" && builtin pwd -P) || exit 1
ROOT_DIR=$(CDPATH= builtin cd -P -- "${SCRIPT_DIR}/.." && builtin pwd -P) || exit 1
[[ "${SCRIPT_DIR}" = "${ROOT_DIR}/scripts" ]] || {
    printf 'release 검증 실패: publish-release-tag.sh canonical containment가 올바르지 않습니다.\n' >&2
    exit 1
}
PUBLISH_SOURCE_PATH="${SCRIPT_DIR}/${source_leaf}"
RELEASE_LIB_PATH="${SCRIPT_DIR}/release-lib.sh"
bootstrap_require_source_file "${PUBLISH_SOURCE_PATH}" "${current_uid}" >/dev/null || exit 1
RELEASE_LIB_IDENTITY=$(bootstrap_require_source_file "${RELEASE_LIB_PATH}" "${current_uid}") || exit 1
readonly ROOT_DIR RELEASE_LIB_PATH RELEASE_LIB_IDENTITY

exec 7<"${RELEASE_LIB_PATH}" || exit 1
[[ "$(bootstrap_fd_identity 7)" = "${RELEASE_LIB_IDENTITY}" ]] || {
    printf 'release 검증 실패: release-lib.sh fd identity가 변경되었습니다.\n' >&2
    exit 1
}
# shellcheck source=release-lib.sh
source /dev/fd/7
exec 7<&-
release_bind_project_root "${ROOT_DIR}" || exit 1

readonly RELEASE_CONTAINER="${ROOT_DIR}/.local/releases"
readonly RELEASE_DIRECTORY="${RELEASE_CONTAINER}/v0.1.0"
readonly RELEASE_TAG='v0.1.0'
SIXBRIDGE_PUBLISH_ARTIFACT_SIGNER=''
SIXBRIDGE_PUBLISH_PREFLIGHT_COMMIT=''
SIXBRIDGE_PUBLISH_PREFLIGHT_SIGNER=''
OWNED_WORK_DIRECTORIES=()
PUBLISH_ACTIVE_CHILD_PID=''
PUBLISH_ACTIVE_CHILD_PGID=''
PUBLISH_DEFERRED_SIGNAL_STATUS=''
PUBLISH_SIGNAL_EXITING=0
PUBLISH_TRANSIENT_ROOT=''

stable_directory_identity() {
    local identity
    identity=$(release_stage_identity "$1") || return 1
    printf '%s\n' "${identity%:*}"
}

remove_owned_directory() {
    local path=$1 expected=$2
    [[ -n "${path}" && -n "${expected}" ]] || return 0
    [[ -e "${path}" || -L "${path}" ]] || return 0
    release_validate_stage_root "${path}" "${RELEASE_CONTAINER}" || return 1
    release_atomic_remove_owned_directory "${path}" "${expected}"
}

release_register_owned_work_directory() {
    local path=$1 expected=${2:-} identity
    release_validate_stage_root "${path}" "${RELEASE_CONTAINER}" || return 1
    identity=$(release_owned_directory_identity "${path}") || return 1
    [[ -z "${expected}" || "${identity}" = "${expected}" ]] || {
        release_fail 'owned work directory identity가 등록 전에 변경되었습니다.'
        return 1
    }
    OWNED_WORK_DIRECTORIES+=("${path}|${identity}")
}

cleanup_owned_work_directories() {
    local record path identity index
    index=$((${#OWNED_WORK_DIRECTORIES[@]} - 1))
    while [[ "${index}" -ge 0 ]]; do
        record=${OWNED_WORK_DIRECTORIES[${index}]}
        if [[ -n "${record}" ]]; then
            path=${record%%|*}
            identity=${record#*|}
            remove_owned_directory "${path}" "${identity}" || return 1
        fi
        index=$((index - 1))
    done
}

cleanup_publish_transient_root() {
    cleanup_owned_work_directories
    [[ -z "${PUBLISH_TRANSIENT_ROOT}" ||
       (! -e "${PUBLISH_TRANSIENT_ROOT}" && ! -L "${PUBLISH_TRANSIENT_ROOT}") ]] || {
        release_fail 'publish transient root를 정리하지 못했습니다.'
        return 1
    }
}

release_after_dmg_signal_cleanup() {
    cleanup_owned_work_directories
}

publish_install_signal_handlers() {
    trap 'publish_signal_exit 129' HUP
    trap 'publish_signal_exit 130' INT
    trap 'publish_signal_exit 143' TERM
}

publish_defer_signal() {
    local status=$1
    [[ -n "${PUBLISH_DEFERRED_SIGNAL_STATUS}" ]] ||
        PUBLISH_DEFERRED_SIGNAL_STATUS=${status}
}

publish_install_deferred_signal_handlers() {
    trap 'publish_defer_signal 129' HUP
    trap 'publish_defer_signal 130' INT
    trap 'publish_defer_signal 143' TERM
}

terminate_publish_child() {
    local pid=${PUBLISH_ACTIVE_CHILD_PID:-} pgroup=${PUBLISH_ACTIVE_CHILD_PGID:-} _
    [[ -n "${pid}" ]] || return 0
    if [[ -n "${pgroup}" ]] && /bin/kill -TERM -- "-${pgroup}" 2>/dev/null; then
        :
    else
        /bin/kill -TERM "${pid}" 2>/dev/null || true
    fi
    for _ in {1..20}; do
        /bin/kill -0 "${pid}" 2>/dev/null || break
        /bin/sleep 0.05
    done
    if /bin/kill -0 "${pid}" 2>/dev/null; then
        if [[ -n "${pgroup}" ]]; then
            /bin/kill -KILL -- "-${pgroup}" 2>/dev/null || true
        else
            /bin/kill -KILL "${pid}" 2>/dev/null || true
        fi
    fi
    wait "${pid}" 2>/dev/null || true
    if [[ -n "${pgroup}" ]] && /bin/kill -0 -- "-${pgroup}" 2>/dev/null; then
        /bin/kill -KILL -- "-${pgroup}" 2>/dev/null || true
        for _ in {1..20}; do
            /bin/kill -0 -- "-${pgroup}" 2>/dev/null || break
            /bin/sleep 0.05
        done
    fi
    if [[ -n "${pgroup}" ]] && /bin/kill -0 -- "-${pgroup}" 2>/dev/null; then
        release_fail 'publish verifier process group을 종료하지 못했습니다.'
        return 1
    fi
    PUBLISH_ACTIVE_CHILD_PID=''
    PUBLISH_ACTIVE_CHILD_PGID=''
}

publish_signal_exit() {
    local status=$1
    [[ "${PUBLISH_SIGNAL_EXITING}" = 0 ]] || {
        trap '' HUP INT TERM
        exit "${status}"
    }
    PUBLISH_SIGNAL_EXITING=1
    trap '' HUP INT TERM
    if ! terminate_publish_child; then
        exit "${status}"
    fi
    cleanup_publish_transient_root || true
    exit "${status}"
}

publish_exit_cleanup() {
    local status=$?
    trap - EXIT
    trap '' HUP INT TERM
    if ! release_dmg_has_active_state && [[ -z "${PUBLISH_ACTIVE_CHILD_PID}" ]]; then
        cleanup_publish_transient_root || true
    fi
    exit "${status}"
}

run_release_step() {
    local status deferred_status spawned_pid spawn_barrier_status=0
    [[ -z "${PUBLISH_ACTIVE_CHILD_PID}" ]] || {
        release_fail 'publish verifier child가 이미 실행 중입니다.'
        return 1
    }
    PUBLISH_DEFERRED_SIGNAL_STATUS=''
    publish_install_deferred_signal_handlers
    set -m
    "$@" &
    spawned_pid=$!
    if declare -F publish_after_release_step_spawn >/dev/null; then
        publish_after_release_step_spawn "${spawned_pid}" || spawn_barrier_status=$?
    fi
    PUBLISH_ACTIVE_CHILD_PID=${spawned_pid}
    PUBLISH_ACTIVE_CHILD_PGID=${spawned_pid}
    publish_install_signal_handlers
    deferred_status=${PUBLISH_DEFERRED_SIGNAL_STATUS}
    PUBLISH_DEFERRED_SIGNAL_STATUS=''
    [[ -z "${deferred_status}" ]] || publish_signal_exit "${deferred_status}"
    if [[ "${spawn_barrier_status}" -ne 0 ]]; then
        terminate_publish_child || true
        set +m
        publish_install_signal_handlers
        return "${spawn_barrier_status}"
    fi
    if wait "${PUBLISH_ACTIVE_CHILD_PID}"; then
        status=0
    else
        status=$?
    fi
    PUBLISH_ACTIVE_CHILD_PID=''
    PUBLISH_ACTIVE_CHILD_PGID=''
    set +m
    publish_install_signal_handlers
    return "${status}"
}

create_publish_transient_root() {
    local candidate='' create_status=0 deferred_status
    release_validate_release_container "${RELEASE_CONTAINER}" || return 1
    PUBLISH_DEFERRED_SIGNAL_STATUS=''
    publish_install_deferred_signal_handlers
    candidate=$(/usr/bin/mktemp -d \
        "${RELEASE_CONTAINER}/.sixbridge-publish-transient.XXXXXX") || create_status=$?
    if [[ "${create_status}" = 0 ]]; then
        release_private_directory "${candidate}" || create_status=$?
    fi
    if [[ "${create_status}" = 0 ]]; then
        release_register_owned_work_directory "${candidate}" || create_status=$?
    fi
    if [[ "${create_status}" = 0 ]]; then
        PUBLISH_TRANSIENT_ROOT=${candidate}
        SIXBRIDGE_RELEASE_TRANSIENT_ROOT=${candidate}
        TMPDIR=${candidate}
        export SIXBRIDGE_RELEASE_TRANSIENT_ROOT TMPDIR
    elif [[ -n "${candidate}" ]]; then
        /bin/rmdir -- "${candidate}" 2>/dev/null || true
    fi
    publish_install_signal_handlers
    deferred_status=${PUBLISH_DEFERRED_SIGNAL_STATUS}
    PUBLISH_DEFERRED_SIGNAL_STATUS=''
    [[ -z "${deferred_status}" ]] || publish_signal_exit "${deferred_status}"
    [[ "${create_status}" = 0 ]] || {
        release_fail 'publish transient root를 만들지 못했습니다.'
        return 1
    }
}

publish_install_signal_handlers
trap publish_exit_cleanup EXIT

release_git() {
    release_system_executable /usr/bin/git || return 1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME="${ROOT_DIR}" TMPDIR=/tmp LANG=C LC_ALL=C \
        /usr/bin/git -C "${ROOT_DIR}" "$@"
}

release_current_branch() {
    local branch
    branch=$(release_git symbolic-ref --quiet --short HEAD 2>&1) || {
        release_fail 'detached HEAD입니다.'
        return 1
    }
    [[ "${branch}" = main ]] || {
        release_fail 'main branch에서만 게시할 수 있습니다.'
        return 1
    }
}

release_clean_main_at_origin() {
    local status head_commit origin_commit
    release_current_branch || return 1
    status=$(release_git status --porcelain=v1 --untracked-files=all 2>&1) || {
        release_fail 'git worktree 상태를 읽지 못했습니다.'
        return 1
    }
    [[ -z "${status}" ]] || {
        release_fail 'worktree가 깨끗하지 않습니다.'
        return 1
    }
    if ! release_git fetch --quiet origin main --tags; then
        release_fail 'origin main/tag 정보를 가져오지 못했습니다.'
        return 1
    fi
    head_commit=$(release_git rev-parse HEAD 2>&1) || return 1
    origin_commit=$(release_git rev-parse refs/remotes/origin/main 2>&1) || {
        release_fail 'origin/main commit을 읽지 못했습니다.'
        return 1
    }
    [[ "${head_commit}" = "${origin_commit}" ]] || {
        release_fail 'main과 origin/main commit이 다릅니다.'
        return 1
    }
    [[ "${head_commit}" =~ ^[0-9a-f]{40}$ ]] || {
        release_fail '현재 git commit 형식이 올바르지 않습니다.'
        return 1
    }
    printf '%s\n' "${head_commit}"
}

release_require_absent_tag() {
    ! release_git ls-remote --exit-code --tags origin "refs/tags/${RELEASE_TAG}" >/dev/null 2>&1 || {
        release_fail 'remote tag가 이미 있습니다.'
        return 1
    }
    ! release_git show-ref --verify --quiet "refs/tags/${RELEASE_TAG}" || {
        release_fail 'local tag가 이미 있습니다.'
        return 1
    }
}

release_require_tagger_identity() {
    local identity
    identity=$(release_git var GIT_COMMITTER_IDENT 2>&1) || {
        release_fail 'Git tagger identity가 유효하지 않습니다.'
        return 1
    }
    [[ -n "${identity}" ]] || {
        release_fail 'Git tagger identity가 유효하지 않습니다.'
        return 1
    }
}

release_metadata_value() {
    local filter=$1 jq raw_output
    jq=$(release_tool jq) || return 1
    if ! raw_output=$("${jq}" -er "${filter}" "${RELEASE_DIRECTORY}/RELEASE-METADATA.json" 2>&1); then
        release_fail 'release metadata 값을 읽지 못했습니다.'
        return 1
    fi
    printf '%s\n' "${raw_output}"
}

release_verify_artifacts() {
    local expected_commit=$1 signer metadata_commit
    SIXBRIDGE_PUBLISH_ARTIFACT_SIGNER=''
    release_validate_release_container "${RELEASE_CONTAINER}" || return 1
    signer=$(release_metadata_value '.android.signerSha256') || return 1
    [[ "${signer}" =~ ^[0-9a-f]{64}$ ]] || {
        release_fail 'release metadata signer SHA-256 형식이 올바르지 않습니다.'
        return 1
    }
    release_verify_directory "${RELEASE_DIRECTORY}" "${signer}" "${RELEASE_CONTAINER}" || return 1
    metadata_commit=$(release_metadata_value '.gitCommit') || return 1
    [[ "${metadata_commit}" = "${expected_commit}" ]] || {
        release_fail 'release metadata gitCommit이 현재 HEAD와 다릅니다.'
        return 1
    }
    SIXBRIDGE_PUBLISH_ARTIFACT_SIGNER=${signer}
}

release_preflight() {
    local head_commit signer
    SIXBRIDGE_PUBLISH_PREFLIGHT_COMMIT=''
    SIXBRIDGE_PUBLISH_PREFLIGHT_SIGNER=''
    head_commit=$(release_clean_main_at_origin) || return 1
    release_require_absent_tag || return 1
    release_require_tagger_identity || return 1
    release_verify_artifacts "${head_commit}" || return 1
    signer=${SIXBRIDGE_PUBLISH_ARTIFACT_SIGNER}
    [[ -n "${signer}" ]] || {
        release_fail 'release artifact signer 결과가 비어 있습니다.'
        return 1
    }
    SIXBRIDGE_PUBLISH_PREFLIGHT_COMMIT=${head_commit}
    SIXBRIDGE_PUBLISH_PREFLIGHT_SIGNER=${signer}
}

release_verify_created_tag() {
    local expected_commit=$1 tag_oid tag_type peeled_commit
    tag_oid=$(release_git rev-parse "refs/tags/${RELEASE_TAG}" 2>&1) || {
        release_fail '생성한 tag object를 읽지 못했습니다.'
        return 1
    }
    [[ "${tag_oid}" =~ ^[0-9a-f]{40}$ ]] || {
        release_fail '생성한 tag object OID 형식이 올바르지 않습니다.'
        return 1
    }
    tag_type=$(release_git cat-file -t "${tag_oid}" 2>&1) || return 1
    [[ "${tag_type}" = tag ]] || {
        release_fail '생성한 tag는 annotated tag여야 합니다.'
        return 1
    }
    peeled_commit=$(release_git rev-parse "${tag_oid}^{}" 2>&1) || return 1
    [[ "${peeled_commit}" = "${expected_commit}" ]] || {
        release_fail '생성한 tag가 검증한 commit을 가리키지 않습니다.'
        return 1
    }
    printf '%s\n' "${tag_oid}"
}

[[ "$#" = 1 ]] || { release_fail '사용법: publish-release-tag.sh 0.1.0'; exit 1; }
release_require_version "$1" || exit 1
create_publish_transient_root || exit 1

release_preflight || exit 1
head_commit=${SIXBRIDGE_PUBLISH_PREFLIGHT_COMMIT}
signer=${SIXBRIDGE_PUBLISH_PREFLIGHT_SIGNER}

printf '계속하려면 v0.1.0 KEY BACKUP COMPLETE를 입력하세요: '
IFS= read -r confirmation || { release_fail '백업 확인 입력을 읽지 못했습니다.'; exit 1; }
[[ "${confirmation}" = 'v0.1.0 KEY BACKUP COMPLETE' ]] || {
    release_fail '백업 확인 문구가 일치하지 않습니다.'
    exit 1
}

release_preflight || exit 1
verified_commit=${SIXBRIDGE_PUBLISH_PREFLIGHT_COMMIT}
verified_signer=${SIXBRIDGE_PUBLISH_PREFLIGHT_SIGNER}
[[ "${verified_commit}" = "${head_commit}" && "${verified_signer}" = "${signer}" ]] || {
    release_fail '확인 중 git 또는 release artifact가 변경되었습니다.'
    exit 1
}
cleanup_publish_transient_root || exit 1
trap - EXIT HUP INT TERM

# These hooks are intentionally absent from the production release library.
# The fixture-only library supplies them to exercise the two publish races.
if declare -F release_before_tag_creation >/dev/null; then
    release_before_tag_creation "${verified_commit}" || exit 1
fi
release_git tag -a "${RELEASE_TAG}" "${verified_commit}" -m 'SIX Bridge v0.1.0' || exit 1
tag_oid=$(release_verify_created_tag "${verified_commit}") || exit 1
if declare -F release_before_tag_push >/dev/null; then
    release_before_tag_push "${tag_oid}" "${verified_commit}" || exit 1
fi
release_git push origin "${tag_oid}:refs/tags/${RELEASE_TAG}" || {
    release_fail 'tag push에 실패했습니다. local tag 상태를 확인하세요.'
    exit 1
}

printf 'v0.1.0 annotated tag를 origin에 게시했습니다.\n'
printf '수동 Releases 화면에서 다음 정확히 다섯 파일을 첨부하세요:\n'
for file in "${SIXBRIDGE_RELEASE_FILES[@]}"; do
    printf '%s\n' "${file}"
done
printf 'Android signer SHA-256: %s\n' "${signer}"
printf '경고: macOS DMG는 v0.1.0에서 미서명·미공증입니다.\n'
