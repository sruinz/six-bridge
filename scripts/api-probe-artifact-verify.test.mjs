import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const verifier = fileURLToPath(new URL('./api-probe-artifact-verify.mjs', import.meta.url));

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'api-probe-artifact-'));
  const group = 'base-runtime';
  const six = Buffer.from('six');
  const wrapper = Buffer.from('wrapper');
  const crx = Buffer.from('Cr24-crx');
  const sixPath = join(root, `${group}.six`);
  const wrapperPath = join(root, `${group}-wrapper.apk`);
  const crxPath = join(root, `${group}.crx`);
  const resultPath = join(root, `${group}-result.json`);
  writeFileSync(sixPath, six);
  writeFileSync(wrapperPath, wrapper);
  writeFileSync(crxPath, crx);
  const result = {
    schemaVersion: 1,
    group,
    sixFile: `${group}.six`,
    sixSha256: sha256(six),
    wrapperFile: `${group}-wrapper.apk`,
    wrapperSha256: sha256(wrapper),
    crxEntry: 'assets/extension.crx',
    crxSha256: sha256(crx),
    extensionId: 'a'.repeat(32),
    packageName: `dev.sixbridge.ext.${'a'.repeat(32)}`,
    signerSha256: '1'.repeat(64),
    versionCode: 1,
  };
  writeFileSync(resultPath, JSON.stringify(result));
  return { root, group, sixPath, wrapperPath, crxPath, resultPath, result };
}

function run(value) {
  return spawnSync(process.execPath, [
    verifier,
    '--group', value.group,
    '--six', value.sixPath,
    '--wrapper', value.wrapperPath,
    '--crx', value.crxPath,
    '--result', value.resultPath,
  ]);
}

test('정확한 Android 결과와 host artifact를 허용한다', () => {
  const result = run(fixture());
  assert.equal(result.status, 0, result.stderr.toString());
});

test('hash 불일치와 schema 확장을 fail closed 한다', () => {
  const hashMismatch = fixture();
  writeFileSync(hashMismatch.wrapperPath, 'changed');
  assert.notEqual(run(hashMismatch).status, 0);

  const extraField = fixture();
  writeFileSync(extraField.resultPath, JSON.stringify({ ...extraField.result, extra: true }));
  assert.notEqual(run(extraField).status, 0);
});

test('APK CRX entry 계약과 추출 CRX hash 불일치를 거부한다', () => {
  const wrongEntry = fixture();
  writeFileSync(wrongEntry.resultPath, JSON.stringify({
    ...wrongEntry.result,
    crxEntry: 'assets/other.crx',
  }));
  assert.notEqual(run(wrongEntry).status, 0);

  const wrongCrx = fixture();
  writeFileSync(wrongCrx.crxPath, 'different-crx');
  assert.notEqual(run(wrongCrx).status, 0);
});

test('symlink artifact를 거부한다', () => {
  const value = fixture();
  const link = join(value.root, 'linked.six');
  symlinkSync(value.sixPath, link);
  value.sixPath = link;
  assert.notEqual(run(value).status, 0);

  const crxValue = fixture();
  const crxLink = join(crxValue.root, 'linked.crx');
  symlinkSync(crxValue.crxPath, crxLink);
  crxValue.crxPath = crxLink;
  assert.notEqual(run(crxValue).status, 0);
});
