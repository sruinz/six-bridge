import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
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
});
