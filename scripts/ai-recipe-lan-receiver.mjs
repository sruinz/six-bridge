import { createServer } from "node:http";
import { createHash, randomBytes } from "node:crypto";
import { open } from "node:fs/promises";
import { prepareCliEvidence } from "./lan-receiver-evidence.mjs";

const successHtml = `<!doctype html>
<meta charset="utf-8">
<title>SIX Bridge AI 레시피 검증</title>
<h1>URL 전달 확인 완료</h1>
<p>이 창을 닫고 검증 터미널로 돌아가세요.</p>`;

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function closeServer(server, sockets) {
  return new Promise((resolve) => {
    server.close(() => resolve());
    for (const socket of sockets) {
      socket.destroy();
    }
  });
}

function writeEvidence({ evidenceFile, expectedUrl, requestMatched, reason, receivedUrl }) {
  const evidence = {
    schemaVersion: 1,
    requestMatched,
    reason,
    method: "GET",
    route: "/<redacted>/ext_extract",
    expectedUrlSha256: sha256(expectedUrl),
    receivedUrlSha256: sha256(receivedUrl),
    completedAt: new Date().toISOString(),
  };

  return evidenceFile.writeFile(`${JSON.stringify(evidence)}\n`, {
    encoding: "utf8",
  });
}

export function validateReceiverRequest(input) {
  if (input.method !== "GET") {
    return { ok: false, status: 405, reason: "method" };
  }
  if (input.pathname !== `/${input.token}/ext_extract`) {
    return { ok: false, status: 404, reason: "route" };
  }
  if (input.actualUrl !== input.expectedUrl) {
    return { ok: false, status: 400, reason: "url" };
  }
  return { ok: true, status: 200, reason: "matched" };
}

export async function startReceiver({
  bindHost,
  advertiseHost,
  port,
  expectedUrl,
  timeoutMs,
  evidencePath,
  evidenceFile: providedEvidenceFile,
}) {
  const token = randomBytes(24).toString("hex");
  const evidenceFile = providedEvidenceFile ?? await open(evidencePath, "wx", 0o600);
  let timeoutHandle;
  let finish;
  let settled = false;
  let evidenceClosed = false;
  let resolveCompleted;
  const sockets = new Set();
  const completed = new Promise((resolve) => {
    resolveCompleted = resolve;
  });
  const claim = () => {
    if (settled) {
      return false;
    }
    settled = true;
    clearTimeout(timeoutHandle);
    return true;
  };
  const closeEvidence = async () => {
    if (!evidenceClosed) {
      evidenceClosed = true;
      await evidenceFile.close();
    }
  };
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url, "http://receiver.invalid");
    const queryEntries = [...requestUrl.searchParams];
    const actualUrl = queryEntries.length === 1 && queryEntries[0][0] === "url"
      ? queryEntries[0][1]
      : "";
    const validation = validateReceiverRequest({
      method: request.method,
      pathname: requestUrl.pathname,
      token,
      actualUrl,
      expectedUrl,
    });

    if (!validation.ok) {
      response.writeHead(validation.status, { "content-type": "text/plain; charset=utf-8" });
      response.end(validation.reason);
      return;
    }

    if (!claim()) {
      response.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
      response.end("route");
      return;
    }

    response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    response.end(successHtml, () => {
      void finish({ requestMatched: true, reason: "matched", receivedUrl: actualUrl });
    });
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });

  const close = async () => {
    claim();
    await closeServer(server, sockets);
    await closeEvidence();
  };

  finish = async ({ requestMatched, reason, receivedUrl }) => {
    let result = { requestMatched, reason };
    try {
      await writeEvidence({ evidenceFile, expectedUrl, requestMatched, reason, receivedUrl });
    } catch {
      result = { requestMatched: false, reason: "evidence" };
    } finally {
      try {
        await closeEvidence();
      } catch {
        result = { requestMatched: false, reason: "evidence" };
      }
    }

    await closeServer(server, sockets);
    resolveCompleted(result);
  };

  try {
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(port, bindHost, () => {
        server.off("error", reject);
        resolve();
      });
    });
  } catch (error) {
    await closeEvidence();
    throw error;
  }
  const address = server.address();
  timeoutHandle = setTimeout(() => {
    if (claim()) {
      void finish({ requestMatched: false, reason: "timeout", receivedUrl: "" });
    }
  }, timeoutMs);

  return {
    baseUrl: `http://${advertiseHost}:${address.port}/${token}`,
    port: address.port,
    completed,
    close,
  };
}

function parseArguments(argumentsList) {
  const values = {};
  for (let index = 0; index < argumentsList.length; index += 2) {
    const option = argumentsList[index];
    const value = argumentsList[index + 1];
    if (!option?.startsWith("--") || value === undefined) {
      throw new Error("usage: --advertise-host --expected-url --evidence [--port] [--timeout-ms]");
    }
    values[option] = value;
  }

  for (const requiredOption of ["--advertise-host", "--expected-url", "--evidence"]) {
    if (!values[requiredOption]) {
      throw new Error(`missing required option: ${requiredOption}`);
    }
  }

  const port = Number(values["--port"] ?? "0");
  const timeoutMs = Number(values["--timeout-ms"] ?? "600000");
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new Error("--port must be an integer from 0 to 65535");
  }
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) {
    throw new Error("--timeout-ms must be a positive integer");
  }

  return {
    advertiseHost: values["--advertise-host"],
    expectedUrl: values["--expected-url"],
    evidencePath: values["--evidence"],
    port,
    timeoutMs,
  };
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  const evidence = await prepareCliEvidence(options.evidencePath);
  const receiver = await startReceiver({
    bindHost: "0.0.0.0",
    ...options,
    ...evidence,
  });
  console.log(`baseUrl=${receiver.baseUrl}`);
  const result = await receiver.completed;
  if (!result.requestMatched) {
    throw new Error(`receiver completed without a matched request: ${result.reason}`);
  }
}

if (process.argv[1] && new URL(`file://${process.argv[1]}`).href === import.meta.url) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
