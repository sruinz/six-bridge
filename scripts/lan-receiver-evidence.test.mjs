import assert from "node:assert/strict";
import { chmod, lstat, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { prepareCliEvidence } from "./lan-receiver-evidence.mjs";

test("evidence 직접 자식을 mode 0600으로 선점한다", async (t) => {
  const cwd = process.cwd();
  const root = await mkdtemp(path.join(os.tmpdir(), "six-lan-evidence-"));
  const file = path.join(root, "result.json");
  t.after(async () => {
    process.chdir(cwd);
    await rm(root, { recursive: true, force: true });
  });

  const prepared = await prepareCliEvidence(file, root);
  await prepared.evidenceFile.writeFile("{}\n", "utf8");
  await prepared.evidenceFile.close();

  assert.equal(prepared.evidencePath, "result.json");
  assert.equal(await readFile(file, "utf8"), "{}\n");
  assert.equal((await lstat(file)).mode & 0o777, 0o600);
});

test("기존 evidence 파일을 덮어쓰지 않는다", async (t) => {
  const cwd = process.cwd();
  const root = await mkdtemp(path.join(os.tmpdir(), "six-lan-evidence-"));
  const file = path.join(root, "result.json");
  await writeFile(file, "keep", "utf8");
  await chmod(file, 0o600);
  t.after(async () => {
    process.chdir(cwd);
    await rm(root, { recursive: true, force: true });
  });

  await assert.rejects(prepareCliEvidence(file, root), {
    code: "EEXIST",
  });
  assert.equal(await readFile(file, "utf8"), "keep");
});
