#!/usr/bin/env bash
set -euo pipefail

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cli="$project_dir/cli/build/install/sixbridge/bin/sixbridge"
profile="$project_dir/profiles/sbrowser-30.0.0.67.json"

fail() {
  printf '오류: %s\n' "$1" >&2
  exit 1
}

[[ -x "$cli" ]] || fail "sixbridge CLI가 없습니다. 먼저 ./gradlew :cli:installDist를 실행하세요."
[[ -f "$profile" && ! -L "$profile" ]] || fail "검증용 삼성 브라우저 프로필이 없습니다."

fixture_root=$(mktemp -d "${TMPDIR:-/tmp}/sixbridge-api-probe-generation.XXXXXX")
case "$fixture_root" in
  "${TMPDIR:-/tmp}"/sixbridge-api-probe-generation.*|/tmp/sixbridge-api-probe-generation.*) ;;
  *) fail "임시 경로를 안전하게 검증할 수 없습니다: $fixture_root" ;;
esac
cleanup() {
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT HUP INT TERM

first="$fixture_root/first"
second="$fixture_root/second"
"$cli" probe generate --output "$first" >/dev/null
"$cli" probe generate --output "$second" >/dev/null

node - "$first" "$fixture_root/first.snapshot.json" <<'NODE'
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const [root, snapshotPath] = process.argv.slice(2);
const expectedGroups = [
  'advanced-runtime',
  'base-runtime',
  'identity-privacy',
  'network',
  'tab-page',
  'user-interface',
];
const groups = fs.readdirSync(root, {withFileTypes: true})
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .sort();
if (JSON.stringify(groups) !== JSON.stringify(expectedGroups)) {
  throw new Error(`group coverage 불일치: ${groups.join(',')}`);
}

const catalogDigests = new Set();
const snapshot = [];
function walk(directory) {
  for (const entry of fs.readdirSync(directory, {withFileTypes: true})
    .sort((left, right) => left.name.localeCompare(right.name))) {
    const absolute = path.join(directory, entry.name);
    if (entry.isSymbolicLink() || (!entry.isFile() && !entry.isDirectory())) {
      throw new Error(`허용되지 않은 파일 형식: ${absolute}`);
    }
    if (entry.isDirectory()) walk(absolute);
    if (entry.isFile()) {
      const bytes = fs.readFileSync(absolute);
      snapshot.push({
        path: path.relative(root, absolute).split(path.sep).join('/'),
        sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
      });
    }
  }
}
walk(root);

for (const group of groups) {
  const groupRoot = path.join(root, group);
  const manifest = JSON.parse(fs.readFileSync(path.join(groupRoot, 'manifest.json'), 'utf8'));
  const cases = JSON.parse(fs.readFileSync(path.join(groupRoot, 'probe-cases.json'), 'utf8'));
  if (manifest.manifest_version !== 3) throw new Error(`${group}: Manifest V3가 아닙니다.`);
  if (!Array.isArray(manifest.permissions) || manifest.permissions.length === 0) {
    throw new Error(`${group}: 최소 permission이 없습니다.`);
  }
  if (!Array.isArray(cases.cases) || cases.cases.length === 0) {
    throw new Error(`${group}: probe case가 없습니다.`);
  }
  if (!/^[0-9a-f]{64}$/.test(cases.catalogSha256)) {
    throw new Error(`${group}: catalog SHA-256이 올바르지 않습니다.`);
  }
  catalogDigests.add(cases.catalogSha256);

  for (const file of snapshot.filter((item) => item.path.startsWith(`${group}/`))) {
    if (!/\.(?:html|js|mjs|cjs)$/.test(file.path)) continue;
    const source = fs.readFileSync(path.join(root, file.path), 'utf8');
    if (/\beval\s*\(|\bnew\s+Function\s*\(/.test(source)) {
      throw new Error(`${file.path}: 동적 코드 실행 token이 있습니다.`);
    }
    if (/<script[^>]+src\s*=\s*["']https?:/i.test(source)) {
      throw new Error(`${file.path}: remote script가 있습니다.`);
    }
  }
}
if (catalogDigests.size !== 1) throw new Error('group 사이 catalog SHA-256이 다릅니다.');
fs.writeFileSync(snapshotPath, JSON.stringify(snapshot));
NODE

node - "$second" "$fixture_root/second.snapshot.json" <<'NODE'
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const [root, snapshotPath] = process.argv.slice(2);
const snapshot = [];
function walk(directory) {
  for (const entry of fs.readdirSync(directory, {withFileTypes: true})
    .sort((left, right) => left.name.localeCompare(right.name))) {
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(absolute);
    else if (entry.isFile()) snapshot.push({
      path: path.relative(root, absolute).split(path.sep).join('/'),
      sha256: crypto.createHash('sha256').update(fs.readFileSync(absolute)).digest('hex'),
    });
    else throw new Error(`허용되지 않은 파일 형식: ${absolute}`);
  }
}
walk(root);
fs.writeFileSync(snapshotPath, JSON.stringify(snapshot));
NODE

cmp -s "$fixture_root/first.snapshot.json" "$fixture_root/second.snapshot.json" ||
  fail "같은 catalog에서 생성한 source tree가 결정론적이지 않습니다."

for group in advanced-runtime base-runtime identity-privacy network tab-page user-interface; do
  "$cli" analyze \
    --input "$first/$group" \
    --profile "$profile" \
    --output "$fixture_root/$group.compatibility.json" >/dev/null
  [[ -s "$fixture_root/$group.compatibility.json" ]] ||
    fail "$group 분석 보고서가 생성되지 않았습니다."
done

printf '%s\n' 'API probe 생성·정적 안전성·결정론·분석 gate 통과'
