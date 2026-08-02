import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, symlink } from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { createEvidenceReceiver } from './api-probe-receiver.mjs';

const ORIGIN = 'chrome-extension://abcdefghijklmnopabcdefghijklmnop';
const SHA_A = 'a'.repeat(64);
const SHA_B = 'b'.repeat(64);
const SHA_C = 'c'.repeat(64);
const SHA_D = 'd'.repeat(64);

function evidence(overrides = {}) {
  return {
    schemaVersion: 1,
    sessionId: 'session-20260802',
    groupId: 'base-runtime',
    catalogSha256: SHA_A,
    extensionId: 'abcdefghijklmnopabcdefghijklmnop',
    wrapperSignerSha256: SHA_B,
    browserPackage: 'com.sec.android.app.sbrowser',
    browserVersionName: '30.0.2.61',
    browserVersionCode: 300020061,
    browserApkSetSha256: SHA_C,
    browserSignerSha256: SHA_D,
    results: [{
      caseId: 'runtime.getPlatformInfo',
      context: 'service_worker',
      detail: { kind: 'success', valueType: 'object' },
      durationMs: 1,
      member: 'getPlatformInfo',
      namespace: 'runtime',
      runToken: 'run-token-00000001',
      status: 'VERIFIED',
    }],
    ...overrides,
  };
}

async function fixture() {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-receiver-'));
  const receiver = createEvidenceReceiver({ expectedOrigin: ORIGIN, rootDir });
  await receiver.listen({ host: '127.0.0.1', port: 0 });
  return {
    baseUrl: `http://127.0.0.1:${receiver.address().port}`,
    receiver,
    rootDir,
  };
}

function request(baseUrl, { body, headers = {}, method = 'POST', pathname = '/evidence' }) {
  const url = new URL(pathname, baseUrl);
  return new Promise((resolve, reject) => {
    const req = http.request(url, { headers, method }, (res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({
        body: Buffer.concat(chunks).toString('utf8'),
        headers: res.headers,
        status: res.statusCode,
      }));
    });
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

function post(baseUrl, value, extra = {}) {
  const body = typeof value === 'string' || Buffer.isBuffer(value)
    ? value
    : Buffer.from(JSON.stringify(value));
  return request(baseUrl, {
    body,
    headers: {
      'content-length': String(Buffer.byteLength(body)),
      'content-type': 'application/json; charset=utf-8',
      origin: ORIGIN,
      ...(extra.headers ?? {}),
    },
    pathname: extra.pathname,
  });
}

test('127.0.0.1 외 주소 bind를 거부한다', async () => {
  const rootDir = await mkdtemp(path.join(os.tmpdir(), 'six-probe-bind-'));
  const receiver = createEvidenceReceiver({ expectedOrigin: ORIGIN, rootDir });
  await assert.rejects(receiver.listen({ host: '0.0.0.0', port: 0 }), /127\.0\.0\.1/);
});

test('정확한 extension origin과 JSON만 저장하고 CORS origin을 echo한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const bytes = Buffer.from(JSON.stringify(evidence()));
  const response = await post(f.baseUrl, bytes);
  assert.equal(response.status, 201);
  assert.equal(response.headers['access-control-allow-origin'], ORIGIN);
  assert.equal(response.headers['access-control-allow-credentials'], undefined);
  assert.deepEqual(
    await readFile(path.join(f.rootDir, 'session-20260802', 'base-runtime.json')),
    bytes,
  );

  const preflight = await request(f.baseUrl, {
    headers: {
      'access-control-request-headers': 'content-type',
      'access-control-request-method': 'POST',
      origin: ORIGIN,
    },
    method: 'OPTIONS',
  });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers['access-control-allow-origin'], ORIGIN);
  assert.equal(preflight.headers['access-control-allow-credentials'], undefined);
});

test('없거나 null 또는 웹 origin과 잘못된 content type을 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  for (const origin of [undefined, 'null', 'https://example.com', `${ORIGIN}/`]) {
    const headers = { 'content-type': 'application/json' };
    if (origin !== undefined) headers.origin = origin;
    const response = await request(f.baseUrl, {
      body: JSON.stringify(evidence()),
      headers,
    });
    assert.equal(response.status, 403);
  }
  assert.equal((await request(f.baseUrl, {
    body: JSON.stringify(evidence()),
    headers: { 'content-type': 'text/plain', origin: ORIGIN },
  })).status, 415);
  assert.equal((await post(f.baseUrl, evidence({ extensionId: 'b'.repeat(32) }))).status, 400);
});

test('1 MiB를 streaming 중 즉시 413으로 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const response = await post(f.baseUrl, Buffer.alloc(1_048_577, 0x20));
  assert.equal(response.status, 413);
});

test('JSON과 top-level metadata를 엄격히 검증한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  assert.equal((await post(f.baseUrl, '{')).status, 400);
  assert.equal((await post(f.baseUrl, { ...evidence(), unexpected: true })).status, 400);
  assert.equal((await post(f.baseUrl, evidence({ catalogSha256: 'A'.repeat(64) }))).status, 400);
  assert.equal((await post(f.baseUrl, evidence({ results: [] }))).status, 400);
  const duplicate = JSON.stringify(evidence()).replace(
    '"schemaVersion":1',
    '"schemaVersion":1,"schemaVersion":1',
  );
  assert.equal((await post(f.baseUrl, duplicate)).status, 400);
  const mismatched = evidence();
  mismatched.results[0].status = 'ABSENT';
  assert.equal((await post(f.baseUrl, mismatched)).status, 400);
});

test('잘못된 UTF-8 JSON bytes를 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const bytes = Buffer.from(JSON.stringify(evidence()));
  const marker = Buffer.from('"valueType":"object"');
  const markerIndex = bytes.indexOf(marker);
  assert.notEqual(markerIndex, -1);
  bytes[markerIndex + marker.length - 2] = 0xff;

  assert.equal((await post(f.baseUrl, bytes)).status, 400);
});

test('path traversal Unicode separator와 encoded separator ID를 거부한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const values = [
    evidence({ sessionId: '../escape' }),
    evidence({ sessionId: 'session／escape' }),
    evidence({ groupId: 'base%2fruntime' }),
    evidence({ groupId: 'base\\runtime' }),
  ];
  for (const value of values) assert.equal((await post(f.baseUrl, value)).status, 400);
  assert.equal((await post(f.baseUrl, evidence(), { pathname: '/evidence/x%2fy' })).status, 404);
});

test('동일 bytes는 idempotent이고 다른 bytes는 overwrite하지 않는다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());

  const first = Buffer.from(JSON.stringify(evidence()));
  const different = Buffer.from(JSON.stringify(evidence({ browserVersionName: '30.0.2.62' })));
  assert.equal((await post(f.baseUrl, first)).status, 201);
  assert.equal((await post(f.baseUrl, first)).status, 200);
  assert.equal((await post(f.baseUrl, different)).status, 409);
  assert.deepEqual(
    await readFile(path.join(f.rootDir, 'session-20260802', 'base-runtime.json')),
    first,
  );
});

test('동시 저장도 exact bytes 한 개만 승리한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const one = Buffer.from(JSON.stringify(evidence({ browserVersionName: '30.0.2.61' })));
  const two = Buffer.from(JSON.stringify(evidence({ browserVersionName: '30.0.2.62' })));

  const responses = await Promise.all([post(f.baseUrl, one), post(f.baseUrl, two)]);
  assert.deepEqual(responses.map((it) => it.status).sort(), [201, 409]);
  const stored = await readFile(path.join(f.rootDir, 'session-20260802', 'base-runtime.json'));
  assert.ok(stored.equals(one) || stored.equals(two));
});

test('symlink parent와 target을 거부하고 target을 보존한다', async (t) => {
  const f = await fixture();
  t.after(() => f.receiver.close());
  const outside = await mkdtemp(path.join(os.tmpdir(), 'six-probe-outside-'));
  await symlink(outside, path.join(f.rootDir, 'session-20260802'));
  assert.equal((await post(f.baseUrl, evidence())).status, 409);

  const second = await fixture();
  t.after(() => second.receiver.close());
  const session = path.join(second.rootDir, 'session-20260802');
  await mkdir(session);
  const outsideFile = path.join(outside, 'outside.json');
  await symlink(outsideFile, path.join(session, 'base-runtime.json'));
  assert.equal((await post(second.baseUrl, evidence())).status, 409);
  await assert.rejects(readFile(outsideFile), { code: 'ENOENT' });
});
