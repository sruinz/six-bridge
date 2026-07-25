import assert from "node:assert/strict";
import { mkdtemp, readFile, rename, rm, symlink, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  startReceiver,
  validateReceiverRequest,
} from "./ai-recipe-lan-receiver.mjs";

const expectedUrl = "https://www.youtube.com/watch?v=test123";

function request(url, method = "GET") {
  return new Promise((resolve, reject) => {
    const requestHandle = http.request(url, { method }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => {
        body += chunk;
      });
      response.on("end", () => {
        resolve({ status: response.statusCode, body });
      });
    });
    requestHandle.on("error", reject);
    requestHandle.end();
  });
}

function runCli(argumentsList, onStdout) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["scripts/ai-recipe-lan-receiver.mjs", ...argumentsList], {
      cwd: process.cwd(),
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
      onStdout?.(stdout);
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", reject);
    child.on("close", (code) => resolve({ code, stdout, stderr }));
  });
}

test("정확한 일회성 요청만 허용한다", () => {
  const result = validateReceiverRequest({
    method: "GET",
    pathname: "/abc123/ext_extract",
    token: "abc123",
    actualUrl: expectedUrl,
    expectedUrl,
  });

  assert.deepEqual(result, { ok: true, status: 200, reason: "matched" });
});

test("잘못된 요청을 거부한다", async (t) => {
  const rejectedCases = [
    ["POST", { method: "POST" }, { status: 405, reason: "method" }],
    [
      "다른 token",
      { pathname: "/other/ext_extract" },
      { status: 404, reason: "route" },
    ],
    [
      "다른 path",
      { pathname: "/abc123/other" },
      { status: 404, reason: "route" },
    ],
    [
      "다른 URL",
      { actualUrl: "https://www.youtube.com/watch?v=other" },
      { status: 400, reason: "url" },
    ],
  ];

  for (const [name, input, expected] of rejectedCases) {
    await t.test(name, () => {
      const result = validateReceiverRequest({
        method: "GET",
        pathname: "/abc123/ext_extract",
        token: "abc123",
        actualUrl: expectedUrl,
        expectedUrl,
        ...input,
      });

      assert.deepEqual(result, { ok: false, ...expected });
    });
  }
});

test("첫 정확한 요청을 기록하고 서버를 닫는다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const receiver = await startReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  const response = await request(
    `${receiver.baseUrl}/ext_extract?url=${encodeURIComponent(expectedUrl)}`,
  );
  assert.equal(response.status, 200);
  assert.match(response.body, /URL 전달 확인 완료/);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.equal(evidence.requestMatched, true);
  assert.equal(evidence.reason, "matched");
  assert.equal(evidence.route, "/<redacted>/ext_extract");
  assert.match(evidence.expectedUrlSha256, /^[0-9a-f]{64}$/);
  assert.equal(evidence.expectedUrlSha256, evidence.receivedUrlSha256);
  assert.doesNotMatch(evidenceText, /youtube\.com|test123|url=/i);
  await assert.rejects(request(`${receiver.baseUrl}/ext_extract`));
});

test("추가 또는 중복 URL query를 거부한다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const receiver = await startReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 1_000,
    evidencePath: path.join(directory, "evidence.json"),
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  for (const query of [
    `url=${encodeURIComponent(expectedUrl)}&extra=1`,
    `url=${encodeURIComponent(expectedUrl)}&url=other`,
  ]) {
    const response = await request(`${receiver.baseUrl}/ext_extract?${query}`);
    assert.equal(response.status, 400);
  }
});

test("동시의 정확한 요청 중 한 건만 성공한다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const receiver = await startReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 1_000,
    evidencePath: path.join(directory, "evidence.json"),
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  const exactRequest = `${receiver.baseUrl}/ext_extract?url=${encodeURIComponent(expectedUrl)}`;
  const responses = await Promise.all([request(exactRequest), request(exactRequest)]);
  assert.deepEqual(responses.map(({ status }) => status).sort(), [200, 404]);
  assert.deepEqual(await receiver.completed, {
    requestMatched: true,
    reason: "matched",
  });
});

test("기존 evidence 파일을 선점하지 못하면 시작하지 않는다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  await writeFile(evidencePath, "keep", "utf8");
  t.after(async () => {
    await rm(directory, { recursive: true, force: true });
  });
  await assert.rejects(startReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
  }), (error) => {
    assert.equal(error.code, "EEXIST");
    return true;
  });
  assert.equal(await readFile(evidencePath, "utf8"), "keep");
});

test("evidence를 listen 전에 선점하고 close에서 handle을 정리한다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const receiver = await startReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  assert.equal(await readFile(evidencePath, "utf8"), "");
  await receiver.close();
  await writeFile(evidencePath, "closed", { encoding: "utf8", flag: "r+" });
  assert.equal(await readFile(evidencePath, "utf8"), "closed");
});

test("listen 실패 뒤에도 evidence handle을 정리한다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  t.after(async () => {
    await rm(directory, { recursive: true, force: true });
  });

  await assert.rejects(startReceiver({
    bindHost: "invalid-listen-host",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 1_000,
    evidencePath,
  }));
  await writeFile(evidencePath, "released", { encoding: "utf8", flag: "r+" });
  assert.equal(await readFile(evidencePath, "utf8"), "released");
});

test("timeout은 실패 evidence를 redaction해 기록한다", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const evidencePath = path.join(directory, "evidence.json");
  const receiver = await startReceiver({
    bindHost: "127.0.0.1",
    advertiseHost: "127.0.0.1",
    port: 0,
    expectedUrl,
    timeoutMs: 25,
    evidencePath,
  });
  t.after(async () => {
    await receiver.close();
    await rm(directory, { recursive: true, force: true });
  });

  assert.deepEqual(await receiver.completed, {
    requestMatched: false,
    reason: "timeout",
  });

  const evidenceText = await readFile(evidencePath, "utf8");
  const evidence = JSON.parse(evidenceText);
  assert.deepEqual(Object.keys(evidence).sort(), [
    "completedAt",
    "expectedUrlSha256",
    "method",
    "reason",
    "receivedUrlSha256",
    "requestMatched",
    "route",
    "schemaVersion",
  ]);
  assert.equal(evidence.requestMatched, false);
  assert.equal(evidence.reason, "timeout");
  assert.equal(evidence.route, "/<redacted>/ext_extract");
  assert.match(evidence.expectedUrlSha256, /^[0-9a-f]{64}$/);
  assert.match(evidence.receivedUrlSha256, /^[0-9a-f]{64}$/);
  assert.doesNotMatch(evidenceText, /youtube\.com|test123|url=/i);
  await assert.rejects(request(`${receiver.baseUrl}/ext_extract`));
});

test("CLI는 evidence 직접 자식만 허용하고 symlink를 거부한다", async (t) => {
  const evidenceDirectory = ".local/evidence";
  const suffix = `${process.pid}-${Date.now()}`;
  const directEvidence = `${evidenceDirectory}/ai-recipe-lan-test-${suffix}.json`;
  const symlinkEvidence = `${evidenceDirectory}/ai-recipe-lan-link-${suffix}.json`;
  const externalDirectory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  const externalEvidence = path.join(externalDirectory, "evidence.json");
  t.after(async () => {
    await rm(directEvidence, { force: true });
    await rm(symlinkEvidence, { force: true });
    await rm(externalDirectory, { recursive: true, force: true });
  });

  const outside = await runCli([
    "--advertise-host", "127.0.0.1",
    "--expected-url", expectedUrl,
    "--evidence", externalEvidence,
  ]);
  assert.equal(outside.code, 1);
  assert.match(outside.stderr, /directly inside \.local\/evidence/);

  await symlink(externalEvidence, symlinkEvidence);
  const symlinkResult = await runCli([
    "--advertise-host", "127.0.0.1",
    "--expected-url", expectedUrl,
    "--evidence", symlinkEvidence,
  ]);
  assert.equal(symlinkResult.code, 1);
  assert.match(symlinkResult.stderr, /symbolic link/);

  const direct = await runCli([
    "--advertise-host", "127.0.0.1",
    "--expected-url", expectedUrl,
    "--evidence", directEvidence,
    "--timeout-ms", "1",
  ]);
  assert.equal(direct.code, 1);
  assert.equal(JSON.parse(await readFile(directEvidence, "utf8")).requestMatched, false);
});

test("CLI는 실행 중 evidence root가 바뀌어도 외부에 쓰지 않는다", async (t) => {
  const evidenceRoot = ".local/evidence";
  const suffix = `${process.pid}-${Date.now()}`;
  const movedRoot = `.local/evidence-moved-${suffix}`;
  const filename = `ai-recipe-lan-root-race-${suffix}.json`;
  const externalDirectory = await mkdtemp(path.join(os.tmpdir(), "ai-recipe-receiver-"));
  let rootSwapped = false;
  t.after(async () => {
    if (rootSwapped) {
      await rm(evidenceRoot, { recursive: true, force: true });
      await rename(movedRoot, evidenceRoot);
    }
    await rm(externalDirectory, { recursive: true, force: true });
  });

  let receiverStarted;
  const started = new Promise((resolve) => {
    receiverStarted = resolve;
  });
  const resultPromise = runCli([
    "--advertise-host", "127.0.0.1",
    "--expected-url", expectedUrl,
    "--evidence", `${evidenceRoot}/${filename}`,
    "--timeout-ms", "75",
  ], (stdout) => {
    if (stdout.startsWith("baseUrl=")) {
      receiverStarted();
    }
  });
  await started;
  await rename(evidenceRoot, movedRoot);
  await symlink(externalDirectory, evidenceRoot);
  rootSwapped = true;

  const result = await resultPromise;
  assert.equal(result.code, 1);
  await assert.rejects(readFile(path.join(externalDirectory, filename), "utf8"));
  assert.equal(JSON.parse(await readFile(path.join(movedRoot, filename), "utf8")).requestMatched, false);
});
