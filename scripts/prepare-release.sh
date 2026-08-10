#!/bin/bash -p

builtin trap - DEBUG RETURN ERR
if (builtin return 0 2>/dev/null); then
    printf 'release 검증 실패: prepare-release.sh는 source할 수 없는 direct-exec entrypoint입니다.\n' >&2
    builtin return 64
fi

case $- in
    *p*) ;;
    *) printf 'release 검증 실패: prepare-release.sh는 privileged Bash direct-exec로 실행해야 합니다.\n' >&2; exit 1 ;;
esac
[[ "${BASH:-}" = /bin/bash ]] || {
    printf 'release 검증 실패: prepare-release.sh의 Bash interpreter가 올바르지 않습니다.\n' >&2
    exit 1
}
ENTRY_INHERITED_PATH=${PATH:-}
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

bootstrap_require_directory() {
    local path=$1 current_uid=$2 owner mode
    [[ -d "${path}" && ! -L "${path}" ]] || {
        printf 'release 검증 실패: entrypoint ancestor는 symbolic link가 아닌 directory여야 합니다.\n' >&2
        return 1
    }
    owner=$(bootstrap_stat_value '%u' "${path}") || return 1
    [[ "${owner}" = 0 || "${owner}" = "${current_uid}" ]] || {
        printf 'release 검증 실패: entrypoint ancestor owner가 신뢰 범위 밖입니다.\n' >&2
        return 1
    }
    mode=$(bootstrap_stat_value '%Lp' "${path}") || return 1
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        printf 'release 검증 실패: entrypoint ancestor가 group/other write를 허용합니다.\n' >&2
        return 1
    }
}

bootstrap_require_source_file() {
    local path=$1 current_uid=$2 identity owner mode links
    identity=$(bootstrap_file_identity "${path}") || {
        printf 'release 검증 실패: entrypoint source는 symbolic link가 아닌 regular file이어야 합니다.\n' >&2
        return 1
    }
    IFS=: read -r _ _ _ links owner mode _ <<<"${identity}"
    [[ "${links}" = 1 && "${owner}" = "${current_uid}" ]] || {
        printf 'release 검증 실패: entrypoint source owner 또는 link count가 올바르지 않습니다.\n' >&2
        return 1
    }
    [[ "${mode}" =~ ^[0-7]{3,4}$ && $((8#${mode} & 8#022)) = 0 ]] || {
        printf 'release 검증 실패: entrypoint source가 group/other write를 허용합니다.\n' >&2
        return 1
    }
    printf '%s\n' "${identity}"
}

bootstrap_fd_identity() {
    local fd=$1
    /usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
        /usr/bin/perl -e '
            open(my $fh, q{<&=} . $ARGV[0]) or exit 1;
            my @s = stat($fh);
            @s or exit 1;
            printf "%s:%s:%s:%s:%s:%o:%s\n", @s[0,1,7,3,4], $s[2] & 07777, $s[10];
        ' "${fd}"
}

current_uid=$(/usr/bin/id -u) || exit 1
source_input=${BASH_SOURCE[0]}
[[ ! -L "${source_input}" ]] || {
    printf 'release 검증 실패: prepare-release.sh symbolic link entrypoint는 허용되지 않습니다.\n' >&2
    exit 1
}
source_parent=$(/usr/bin/dirname -- "${source_input}") || exit 1
source_leaf=${source_input##*/}
SCRIPT_DIR=$(CDPATH= cd -P -- "${source_parent}" && pwd -P) || exit 1
PREPARE_SOURCE_PATH="${SCRIPT_DIR}/${source_leaf}"
ROOT_DIR=$(CDPATH= cd -P -- "${SCRIPT_DIR}/.." && pwd -P) || exit 1
[[ "${SCRIPT_DIR}" = "${ROOT_DIR}/scripts" ]] || {
    printf 'release 검증 실패: prepare-release.sh canonical containment가 올바르지 않습니다.\n' >&2
    exit 1
}
ancestor=${ROOT_DIR}
while :; do
    bootstrap_require_directory "${ancestor}" "${current_uid}" || exit 1
    [[ "${ancestor}" = / ]] && break
    ancestor=$(/usr/bin/dirname -- "${ancestor}") || exit 1
done
bootstrap_require_directory "${SCRIPT_DIR}" "${current_uid}" || exit 1
PREPARE_SOURCE_IDENTITY=$(bootstrap_require_source_file "${PREPARE_SOURCE_PATH}" "${current_uid}") || exit 1
RELEASE_LIB_PATH="${SCRIPT_DIR}/release-lib.sh"
RELEASE_LIB_IDENTITY=$(bootstrap_require_source_file "${RELEASE_LIB_PATH}" "${current_uid}") || exit 1
readonly ROOT_DIR PREPARE_SOURCE_PATH RELEASE_LIB_PATH PREPARE_SOURCE_IDENTITY RELEASE_LIB_IDENTITY
readonly SIXBRIDGE_RELEASE_ROOT=${ROOT_DIR}
export SIXBRIDGE_RELEASE_ROOT

# shellcheck source=release-lib.sh
exec 7<"${RELEASE_LIB_PATH}" || exit 1
[[ "$(bootstrap_fd_identity 7)" = "${RELEASE_LIB_IDENTITY}" ]] || {
    printf 'release 검증 실패: release-lib.sh fd identity가 변경되었습니다.\n' >&2
    exit 1
}
source /dev/fd/7
exec 7<&-
release_bind_project_root "${ROOT_DIR}" || exit 1

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
    if [[ -n "${pgroup}" ]] && /bin/kill -TERM -- "-${pgroup}" 2>/dev/null; then
        :
    else
        /bin/kill -TERM "${pid}" 2>/dev/null || true
    fi
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

emit_preparation_worker_program() {
    local name
    declare -p ROOT_DIR KEYSTORE RELEASE_NOTES RELEASE_CONTAINER FINAL_DIR LOCK_DIR \
        PUBLISH_HELPER_SOURCE ANDROID_OUTPUT DMG_OUTPUT STAGE_DIR STAGE_IDENTITY \
        PUBLISH_DIR PUBLISH_IDENTITY PUBLISH_HELPER PUBLISH_HELPER_IDENTITY \
        PUBLISH_HELPER_SOURCE_IDENTITY PUBLISH_PERL_PRIVLIB PUBLISH_PERL_ARCHLIB \
        RELEASE_TMPDIR CONTAINER_IDENTITY LOCK_IDENTITY OWNED_WORK_DIRECTORIES \
        ACTIVE_CHILD_PID ACTIVE_CHILD_PGID
    for name in \
        stable_directory_identity remove_owned_directory release_register_owned_work_directory \
        cleanup_owned_work_directories cleanup_stage release_worker_signal_exit \
        require_android_credentials validate_sanitized_worker_environment validate_keystore \
        release_keytool release_git release_clean_commit read_signer_sha256 \
        create_release_container acquire_release_lock create_release_temp_directory \
        copy_to_stage capture_release_file_identities assert_release_file_identities \
        assert_exact_release_entries create_publish_snapshot compile_atomic_publish_helper \
        publish_manifest_identity publish_verified_snapshot prepare_release_worker; do
        declare -f "${name}" || return 1
    done
    printf 'readonly SIXBRIDGE_PREPARE_WORKER_PROGRAM_COMPLETE=1\n'
}

run_preparation_worker() {
    local status java_path java_home node_path rg_path gradle_home gradle_mode local_root physical_local
    local runtime_path resolved_node resolved_rg sanitizer_script perl_sanitizer worker_bootstrap
    release_require_version "${1:-}" || return 1
    require_android_credentials || return 1
    release_utf8_locale >/dev/null || return 1
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
    gradle_mode=$(release_stat_value '%Lp' "${gradle_home}") || return 1
    [[ "${gradle_mode}" = 700 ]] || {
        release_fail 'GRADLE_USER_HOME mode는 정확히 0700이어야 합니다.'
        return 1
    }
    [[ "$(CDPATH= cd -P -- "${gradle_home}" && pwd -P)" = "${ROOT_DIR}/.local/gradle-home" ]] || { release_fail 'GRADLE_USER_HOME containment가 올바르지 않습니다.'; return 1; }
    [[ -r "${gradle_home}" && -w "${gradle_home}" && -x "${gradle_home}" ]] || {
        release_fail 'GRADLE_USER_HOME은 read/write/search 가능한 private directory여야 합니다.'
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
    sanitizer_script='
set -euo pipefail
perl_sanitizer=$1
worker_bootstrap=$2
root=$3
lib_identity=$4
runtime_path=$5
java_home=$6
gradle_home=$7
shift 7
values=(
    "${runtime_path}"
    "${java_home}"
    "${gradle_home}"
    /var/empty
    C
    UTF-8
    "${SIXBRIDGE_RELEASE_ROOT:-}"
    "${SIXBRIDGE_PREPARE_FIXTURE_BIN:-}"
    "${SIXBRIDGE_PREPARE_LOG:-}"
    "${SIXBRIDGE_PREPARE_SIGNER:-}"
    "${SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD:-}"
    "${SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD:-}"
    "${SIXBRIDGE_PREPARE_VERIFY_FAIL:-}"
    "${SIXBRIDGE_PREPARE_ANDROID_FAIL:-}"
    "${SIXBRIDGE_PREPARE_DMG_BUILD_FAIL:-}"
    "${SIXBRIDGE_PREPARE_APK_VERIFY_FAIL:-}"
    "${SIXBRIDGE_PREPARE_JQ_FAIL:-}"
    "${SIXBRIDGE_PREPARE_MUTATE_SOURCE:-}"
    "${SIXBRIDGE_PREPARE_MUTATE_HEAD:-}"
    "${SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION:-}"
    "${SIXBRIDGE_PREPARE_PUBLISH_BARRIER:-}"
    "${SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER:-}"
    "${SIXBRIDGE_PREPARE_WAIT_IN_VERIFY:-}"
    "${SIXBRIDGE_PREPARE_READY:-}"
    "${SIXBRIDGE_PREPARE_CONTINUE:-}"
    "${SIXBRIDGE_PREPARE_CHILD_PID:-}"
    "${SIXBRIDGE_PREPARE_TMPDIR_RECORD:-}"
)
exec 9< <(
    for ((index = 0; index < ${#values[@]}; index++)); do
        printf "%s\0" "${values[index]}"
    done
)
exec -c /usr/bin/perl -e "${perl_sanitizer}" "${worker_bootstrap}" "${root}" \
    "${lib_identity}" "$@"
'
    perl_sanitizer='
use strict;
use warnings;
my @names = qw(
    PATH JAVA_HOME GRADLE_USER_HOME HOME LANG LC_CTYPE SIXBRIDGE_RELEASE_ROOT
    SIXBRIDGE_PREPARE_FIXTURE_BIN SIXBRIDGE_PREPARE_LOG SIXBRIDGE_PREPARE_SIGNER
    SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD
    SIXBRIDGE_PREPARE_VERIFY_FAIL SIXBRIDGE_PREPARE_ANDROID_FAIL
    SIXBRIDGE_PREPARE_DMG_BUILD_FAIL SIXBRIDGE_PREPARE_APK_VERIFY_FAIL
    SIXBRIDGE_PREPARE_JQ_FAIL SIXBRIDGE_PREPARE_MUTATE_SOURCE
    SIXBRIDGE_PREPARE_MUTATE_HEAD SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION
    SIXBRIDGE_PREPARE_PUBLISH_BARRIER SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER
    SIXBRIDGE_PREPARE_WAIT_IN_VERIFY SIXBRIDGE_PREPARE_READY
    SIXBRIDGE_PREPARE_CONTINUE SIXBRIDGE_PREPARE_CHILD_PID
    SIXBRIDGE_PREPARE_TMPDIR_RECORD
);
open(my $values_fh, q{<&=9}) or die q{worker environment fd open failed\n};
binmode($values_fh);
local $/;
my $payload = <$values_fh>;
defined($payload) or die q{worker environment fd read failed\n};
close($values_fh) or die q{worker environment fd close failed\n};
my @values = split(/\0/, $payload, -1);
pop(@values) if @values && $values[-1] eq q{};
@values == @names or die q{worker environment field count failed\n};
%ENV = ();
@ENV{@names} = @values;
$SIG{HUP} = q{DEFAULT};
$SIG{INT} = q{DEFAULT};
$SIG{TERM} = q{DEFAULT};
my ($worker_bootstrap, $root, $lib_identity, @args) = @ARGV;
exec {q{/bin/bash}} q{/bin/bash}, q{--noprofile}, q{--norc}, q{-p}, q{-c},
    $worker_bootstrap, q{sixbridge-worker}, $root, $lib_identity, @args
    or die q{worker exec failed\n};
'
    worker_bootstrap='
set -euo pipefail
case $- in *p*) ;; *) printf "release 검증 실패: worker privileged Bash가 아닙니다.\n" >&2; exit 1 ;; esac
ENTRY_INHERITED_PATH=${PATH:-}
PATH=/usr/bin:/bin
export PATH
unset BASH_ENV ENV CDPATH GLOBIGNORE
export -n BASHOPTS SHELLOPTS
worker_root=$1
expected_lib_identity=$2
shift 2
actual_lib_identity=$(/usr/bin/env -i PATH=/usr/bin:/bin HOME=/var/empty LC_ALL=C \
    /usr/bin/perl -e '\''open(my $fh, q{<&=} . 7) or exit 1; my @s = stat($fh); @s or exit 1; print join(q{:}, @s[0,1,7,3,4], sprintf(q{%o}, $s[2] & 07777), $s[10]), chr(10);'\'')
[[ "${actual_lib_identity}" = "${expected_lib_identity}" ]] || {
    printf "release 검증 실패: worker release-lib fd identity가 올바르지 않습니다.\n" >&2
    exit 1
}
source /dev/fd/7
release_bind_project_root "${worker_root}"
source /dev/fd/6
exec 6<&- 7<&-
[[ "${SIXBRIDGE_PREPARE_WORKER_PROGRAM_COMPLETE:-}" = 1 ]] || {
    release_fail "worker program이 완전하지 않습니다."
    exit 1
}
for worker_function in prepare_release_worker cleanup_stage release_worker_signal_exit \
    validate_sanitized_worker_environment require_android_credentials; do
    declare -F "${worker_function}" >/dev/null || {
        release_fail "worker program 필수 함수가 없습니다: ${worker_function}"
        exit 1
    }
done
release_require_version "${1:-}"
require_android_credentials
validate_sanitized_worker_environment
set +m
trap cleanup_stage EXIT
trap "release_worker_signal_exit 129" HUP
trap "release_worker_signal_exit 130" INT
trap "release_worker_signal_exit 143" TERM
prepare_release_worker "$@"
'
    [[ "$(bootstrap_file_identity "${PREPARE_SOURCE_PATH}")" = "${PREPARE_SOURCE_IDENTITY}" &&
       "$(bootstrap_file_identity "${ROOT_DIR}/scripts/release-lib.sh")" = "${RELEASE_LIB_IDENTITY}" ]] || {
        release_fail 'worker source path identity가 변경되었습니다.'
        return 1
    }
    exec 6< <(emit_preparation_worker_program)
    exec 7<"${ROOT_DIR}/scripts/release-lib.sh" || { exec 6<&-; return 1; }
    [[ "$(bootstrap_fd_identity 7)" = "${RELEASE_LIB_IDENTITY}" ]] || {
        exec 6<&- 7<&-
        release_fail 'worker release-lib fd identity가 변경되었습니다.'
        return 1
    }
    set -m
    /bin/bash --noprofile --norc -p -c "${sanitizer_script}" sixbridge-sanitizer \
        "${perl_sanitizer}" "${worker_bootstrap}" "${ROOT_DIR}" \
        "${RELEASE_LIB_IDENTITY}" \
        "${runtime_path}" "${java_home}" "${gradle_home}" "$@" </dev/null &
    ACTIVE_CHILD_PID=$!
    ACTIVE_CHILD_PGID=$!
    exec 6<&- 7<&-
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

validate_sanitized_worker_environment() {
    local name exported_names expected_path java_path node_path rg_path gradle_mode
    exported_names=$(compgen -e)
    while IFS= read -r name; do
        [[ -n "${name}" ]] || continue
        case "${name}" in
            PATH|JAVA_HOME|GRADLE_USER_HOME|HOME|LANG|LC_CTYPE|PWD|SHLVL|_|\
            SIXBRIDGE_RELEASE_ROOT|SIXBRIDGE_PREPARE_FIXTURE_BIN|SIXBRIDGE_PREPARE_LOG|\
            SIXBRIDGE_PREPARE_SIGNER|SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD|\
            SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD|SIXBRIDGE_PREPARE_VERIFY_FAIL|\
            SIXBRIDGE_PREPARE_ANDROID_FAIL|SIXBRIDGE_PREPARE_DMG_BUILD_FAIL|\
            SIXBRIDGE_PREPARE_APK_VERIFY_FAIL|SIXBRIDGE_PREPARE_JQ_FAIL|\
            SIXBRIDGE_PREPARE_MUTATE_SOURCE|SIXBRIDGE_PREPARE_MUTATE_HEAD|\
            SIXBRIDGE_PREPARE_POST_VERIFY_MUTATION|SIXBRIDGE_PREPARE_PUBLISH_BARRIER|\
            SIXBRIDGE_PREPARE_NATIVE_PUBLISH_BARRIER|SIXBRIDGE_PREPARE_WAIT_IN_VERIFY|\
            SIXBRIDGE_PREPARE_READY|SIXBRIDGE_PREPARE_CONTINUE|\
            SIXBRIDGE_PREPARE_CHILD_PID|SIXBRIDGE_PREPARE_TMPDIR_RECORD) ;;
            *) release_fail "sanitized worker environment에 허용되지 않은 export가 있습니다: ${name}"; return 1 ;;
        esac
    done <<<"${exported_names}"
    release_utf8_locale >/dev/null || return 1
    [[ "${HOME:-}" = /var/empty && "${LANG:-}" = C && "${LC_CTYPE:-}" = UTF-8 &&
       -z "${LC_ALL+x}" ]] || {
        release_fail 'sanitized worker locale 또는 HOME이 올바르지 않습니다.'
        return 1
    }
    java_path=$(release_java_executable) || return 1
    node_path=$(release_node_executable) || return 1
    rg_path=$(release_ripgrep_executable) || return 1
    [[ "${JAVA_HOME:-}" = "${java_path%/bin/java}" ]] || {
        release_fail 'sanitized worker JAVA_HOME이 검증된 canonical JDK와 다릅니다.'
        return 1
    }
    [[ "${GRADLE_USER_HOME:-}" = "${ROOT_DIR}/.local/gradle-home" ]] || {
        release_fail 'sanitized worker GRADLE_USER_HOME이 canonical 경로와 다릅니다.'
        return 1
    }
    [[ ! -L "${GRADLE_USER_HOME}" ]] || {
        release_fail 'sanitized worker GRADLE_USER_HOME symbolic link는 허용되지 않습니다.'
        return 1
    }
    release_private_directory "${GRADLE_USER_HOME}" || return 1
    gradle_mode=$(release_stat_value '%Lp' "${GRADLE_USER_HOME}") || return 1
    [[ "${gradle_mode}" = 700 && -r "${GRADLE_USER_HOME}" && -w "${GRADLE_USER_HOME}" && -x "${GRADLE_USER_HOME}" ]] || {
        release_fail 'sanitized worker GRADLE_USER_HOME은 정확한 0700 read/write/search directory여야 합니다.'
        return 1
    }
    expected_path="/usr/bin:/bin:$(/usr/bin/dirname -- "${node_path}"):$(/usr/bin/dirname -- "${rg_path}")"
    [[ "${ENTRY_INHERITED_PATH}" = "${expected_path}" ]] || {
        release_fail 'sanitized worker PATH가 검증된 canonical runtime 경로와 다릅니다.'
        return 1
    }
    PATH=${ENTRY_INHERITED_PATH}
    export PATH
    [[ "$(/bin/bash --noprofile --norc -p -c 'command -v node')" = "${node_path}" ]] || {
        release_fail 'sanitized worker node가 검증된 canonical 도구와 다릅니다.'
        return 1
    }
    [[ "$(/bin/bash --noprofile --norc -p -c 'command -v rg')" = "${rg_path}" ]] || {
        release_fail 'sanitized worker rg가 검증된 canonical 도구와 다릅니다.'
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
    release_utf8_locale >/dev/null || return 1
    PATH=/usr/bin:/bin HOME="${ROOT_DIR}" TMPDIR="${RELEASE_TMPDIR:-/tmp}" \
        LANG=C LC_CTYPE=UTF-8 \
        /usr/bin/env -u LC_ALL "${keytool_path}" "$@"
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
local android_sdk_root
release_require_version "${1:-}"
release_system_executable /usr/bin/env
require_android_credentials
validate_keystore
android_sdk_root=$(release_android_sdk_root) || exit 1
release_validate_android_sdk "${android_sdk_root}" || exit 1
ANDROID_HOME=${android_sdk_root}
ANDROID_SDK_ROOT=${android_sdk_root}
readonly ANDROID_HOME ANDROID_SDK_ROOT
export ANDROID_HOME ANDROID_SDK_ROOT
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

unset SIXBRIDGE_PREPARE_WORKER
run_preparation_worker "$@"
