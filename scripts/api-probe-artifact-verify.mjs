#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { basename } from 'node:path';

function fail(message) {
  process.stderr.write(`오류: ${message}\n`);
  process.exit(1);
}

function parseArgs(args) {
  const allowed = new Set(['--group', '--six', '--wrapper', '--crx', '--result']);
  const values = new Map();
  if (args.length !== 10) fail('artifact 검증 인수가 올바르지 않습니다.');
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    if (!allowed.has(key) || values.has(key) || !args[index + 1]) {
      fail('artifact 검증 인수가 올바르지 않습니다.');
    }
    values.set(key, args[index + 1]);
  }
  return values;
}

function regularBytes(path, label) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) fail(`${label}은 regular file이어야 합니다.`);
  return readFileSync(path);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

const values = parseArgs(process.argv.slice(2));
const group = values.get('--group');
if (!/^[a-z]+(?:-[a-z]+)*$/.test(group)) fail('group 형식이 올바르지 않습니다.');
const six = regularBytes(values.get('--six'), 'SixBundle');
const wrapper = regularBytes(values.get('--wrapper'), 'wrapper APK');
const crx = regularBytes(values.get('--crx'), '추출 CRX');
const resultBytes = regularBytes(values.get('--result'), 'Android 결과');
let result;
try {
  result = JSON.parse(resultBytes.toString('utf8'));
} catch {
  fail('Android 결과 JSON이 올바르지 않습니다.');
}
const keys = [
  'crxEntry', 'crxSha256', 'extensionId', 'group', 'packageName', 'schemaVersion', 'signerSha256',
  'sixFile', 'sixSha256', 'versionCode', 'wrapperFile', 'wrapperSha256',
];
if (!result || Array.isArray(result) || typeof result !== 'object' ||
    JSON.stringify(Object.keys(result).sort()) !== JSON.stringify(keys)) {
  fail('Android 결과 schema가 올바르지 않습니다.');
}
if (result.schemaVersion !== 1 || result.group !== group ||
    result.sixFile !== basename(values.get('--six')) ||
    result.wrapperFile !== basename(values.get('--wrapper')) ||
    basename(values.get('--crx')) !== `${group}.crx` ||
    result.crxEntry !== 'assets/extension.crx' ||
    !/^[0-9a-f]{64}$/.test(result.crxSha256) ||
    !/^[a-p]{32}$/.test(result.extensionId) ||
    result.packageName !== `dev.sixbridge.ext.${result.extensionId}` ||
    !/^[0-9a-f]{64}$/.test(result.signerSha256) ||
    !Number.isSafeInteger(result.versionCode) || result.versionCode < 1 ||
    result.sixSha256 !== sha256(six) || result.wrapperSha256 !== sha256(wrapper) ||
    result.crxSha256 !== sha256(crx)) {
  fail('Android 결과 provenance 또는 SHA-256 검증에 실패했습니다.');
}
