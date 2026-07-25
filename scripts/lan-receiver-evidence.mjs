import { lstat, open, realpath } from "node:fs/promises";

export async function prepareCliEvidence(
  evidencePath,
  evidenceRootPath = ".local/evidence",
) {
  const rootInfo = await lstat(evidenceRootPath);
  if (!rootInfo.isDirectory() || rootInfo.isSymbolicLink()) {
    throw new Error(`${evidenceRootPath} must be a real directory`);
  }
  const evidenceRoot = await realpath(evidenceRootPath);
  const absolutePath = evidencePath.startsWith("/")
    ? evidencePath
    : `${process.cwd()}/${evidencePath}`;
  const separator = absolutePath.lastIndexOf("/");
  const parentPath = absolutePath.slice(0, separator) || "/";
  const filename = absolutePath.slice(separator + 1);
  if (
    !filename ||
    filename === "." ||
    filename === ".." ||
    await realpath(parentPath) !== evidenceRoot
  ) {
    const evidenceRootLabel = evidenceRootPath === ".local/evidence"
      ? ".local/evidence"
      : "the evidence root";
    throw new Error(`--evidence must be directly inside ${evidenceRootLabel}`);
  }

  const resolvedEvidencePath = `${evidenceRoot}/${filename}`;
  try {
    if ((await lstat(resolvedEvidencePath)).isSymbolicLink()) {
      throw new Error("--evidence must not be a symbolic link");
    }
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }

  process.chdir(evidenceRoot);
  const currentDirectory = await lstat(".");
  if (
    currentDirectory.dev !== rootInfo.dev ||
    currentDirectory.ino !== rootInfo.ino
  ) {
    throw new Error(evidenceRootPath === ".local/evidence"
      ? ".local/evidence changed while preparing evidence"
      : "evidence root changed while preparing evidence");
  }
  return {
    evidencePath: filename,
    evidenceFile: await open(filename, "wx", 0o600),
  };
}
