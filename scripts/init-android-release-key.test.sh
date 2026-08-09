#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
readonly INIT_SCRIPT="${ROOT_DIR}/scripts/init-android-release-key.sh"
readonly FIXTURE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-release-key-test.XXXXXX")"
readonly KEYTOOL_LOG="${FIXTURE_ROOT}/keytool.argv"
readonly RELEASE_PASSWORD='fixture-release-password'

cleanup() {
    rm -rf "${FIXTURE_ROOT}"
}
trap cleanup EXIT

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

assert_not_contains() {
    local file=$1
    local unexpected=$2
    local message=$3
    if grep -Fq -- "$unexpected" "$file"; then
        cat "$file" >&2
        fail "$message"
    fi
}

assert_keytool_calls() {
    local expected=$1
    local actual=0
    if [[ -f "${KEYTOOL_LOG}" ]]; then
        actual=$(wc -l <"${KEYTOOL_LOG}" | tr -d ' ')
    fi
    [[ "$actual" = "$expected" ]] || fail "keytool 호출 횟수가 기대와 다릅니다: ${actual}"
}

reset_case() {
    local name=$1
    CASE_ROOT="${FIXTURE_ROOT}/${name}"
    KEYSTORE="${CASE_ROOT}/.local/keystore/six-bridge-manager-release.p12"
    rm -rf "${CASE_ROOT}"
    mkdir -p "${CASE_ROOT}"
    : >"${KEYTOOL_LOG}"
}

run_init() {
    env \
        SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_KEYTOOL="${FIXTURE_ROOT}/keytool" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "$@" \
        "${INIT_SCRIPT}"
}

expect_failure() {
    local label=$1
    local expected=$2
    shift 2
    local output="${FIXTURE_ROOT}/${label}.log"
    local status
    set +e
    "$@" >"${output}" 2>&1
    status=$?
    set -e
    [[ "$status" -ne 0 ]] || {
        cat "${output}" >&2
        fail "${label}: 실패해야 할 초기화가 성공했습니다."
    }
    assert_contains "${output}" "${expected}" "${label}: 거부 사유가 없습니다."
}

mkdir -p "${FIXTURE_ROOT}/bin"
cat >"${FIXTURE_ROOT}/keytool" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${SIXBRIDGE_TEST_KEYTOOL_LOG}"

keystore=''
for ((index = 1; index <= $#; index += 1)); do
    if [[ "${!index}" = '-keystore' ]]; then
        next=$((index + 1))
        keystore="${!next}"
        break
    fi
done

case "${1:-}" in
    -genkeypair)
        mkdir -p "$(dirname "${keystore}")"
        printf 'partial key material\n' >"${keystore}"
        if [[ "${SIXBRIDGE_TEST_KEYTOOL_FAIL:-0}" = '1' ]]; then
            exit 42
        fi
        printf 'fixture PKCS12 key material\n' >"${keystore}"
        ;;
    -list)
        printf 'SHA256: FA:KE\n'
        ;;
    *)
        exit 43
        ;;
esac
EOF
chmod +x "${FIXTURE_ROOT}/keytool"
export SIXBRIDGE_TEST_KEYTOOL_LOG="${KEYTOOL_LOG}"

reset_case missing-password
expect_failure missing-password 'SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD' \
    env -u SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD \
        SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_KEYTOOL="${FIXTURE_ROOT}/keytool" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${INIT_SCRIPT}"
assert_keytool_calls 0

reset_case short-password
expect_failure short-password '16자 이상' \
    env SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_KEYTOOL="${FIXTURE_ROOT}/keytool" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD='short-password' \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD='short-password' \
        "${INIT_SCRIPT}"
assert_keytool_calls 0

reset_case mismatched-password
expect_failure mismatched-password '동일해야' \
    env SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_KEYTOOL="${FIXTURE_ROOT}/keytool" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD='different-release-password' \
        "${INIT_SCRIPT}"
assert_keytool_calls 0

reset_case existing-regular-file
mkdir -p "$(dirname "${KEYSTORE}")"
printf 'existing release key\n' >"${KEYSTORE}"
regular_before=$(shasum -a 256 "${KEYSTORE}" | awk '{print $1}')
expect_failure existing-regular-file '이미 존재' run_init
regular_after=$(shasum -a 256 "${KEYSTORE}" | awk '{print $1}')
[[ "${regular_before}" = "${regular_after}" ]] || fail '기존 regular file이 변경되었습니다.'
assert_keytool_calls 0

reset_case existing-symlink
mkdir -p "$(dirname "${KEYSTORE}")"
target="${CASE_ROOT}/existing-target.p12"
printf 'symlink target bytes\n' >"${target}"
ln -s "${target}" "${KEYSTORE}"
symlink_before=$(shasum -a 256 "${target}" | awk '{print $1}')
expect_failure existing-symlink '이미 존재' run_init
symlink_after=$(shasum -a 256 "${target}" | awk '{print $1}')
[[ "${symlink_before}" = "${symlink_after}" ]] || fail 'symlink target이 변경되었습니다.'
assert_keytool_calls 0

reset_case success
run_init >"${FIXTURE_ROOT}/success.log" 2>&1
[[ -f "${KEYSTORE}" && ! -L "${KEYSTORE}" ]] || fail '정상 초기화가 regular PKCS12 파일을 만들지 못했습니다.'
assert_contains "${KEYTOOL_LOG}" '-genkeypair -storetype PKCS12' 'PKCS12 생성 인자가 없습니다.'
assert_contains "${KEYTOOL_LOG}" '-storepass:env SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD' 'storepass env 인자가 없습니다.'
assert_contains "${KEYTOOL_LOG}" '-keypass:env SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD' 'keypass env 인자가 없습니다.'
assert_contains "${KEYTOOL_LOG}" '-alias six-bridge-manager' '고정 alias가 없습니다.'
assert_contains "${KEYTOOL_LOG}" '-keyalg RSA -keysize 3072 -validity 3650' 'RSA 3072 / validity 3650 인자가 없습니다.'
assert_contains "${KEYTOOL_LOG}" '-list -v -storetype PKCS12' 'SHA-256 probe가 없습니다.'
assert_not_contains "${KEYTOOL_LOG}" "${RELEASE_PASSWORD}" 'keytool argv에 실제 비밀번호가 기록되었습니다.'
success_sha=$(shasum -a 256 "${KEYSTORE}" | awk '{print $1}')
expect_failure second-run '이미 존재' run_init
[[ "${success_sha}" = "$(shasum -a 256 "${KEYSTORE}" | awk '{print $1}')" ]] || fail '두 번째 실행이 최초 key를 변경했습니다.'
assert_keytool_calls 2

reset_case keytool-failure
expect_failure keytool-failure 'keytool이 release key 생성에 실패' \
    env SIXBRIDGE_TEST_KEYTOOL_FAIL=1 \
        SIXBRIDGE_RELEASE_ROOT="${CASE_ROOT}" \
        SIXBRIDGE_KEYTOOL="${FIXTURE_ROOT}/keytool" \
        SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD="${RELEASE_PASSWORD}" \
        SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD="${RELEASE_PASSWORD}" \
        "${INIT_SCRIPT}"
[[ ! -e "${KEYSTORE}" ]] || fail 'keytool 실패 뒤 이번 실행의 불완전 key가 남았습니다.'
assert_keytool_calls 1

printf 'init Android release key 검증 통과\n'
