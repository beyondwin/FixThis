import os from "node:os";
import { execSync } from "node:child_process";

export function captureEnv() {
  const cpus = os.cpus();
  return {
    os: process.platform,
    arch: process.arch,
    cpu_model: cpus[0]?.model ?? "unknown",
    cpu_count: cpus.length,
    ram_mb: Math.round(os.totalmem() / 1024 / 1024),
    jdk: safeExec("java -version 2>&1 | head -1") || "unknown",
    node: process.version,
  };
}

function safeExec(cmd) {
  try {
    return execSync(cmd, { stdio: ["ignore", "pipe", "pipe"] }).toString().trim();
  } catch {
    return null;
  }
}

const isMain = process.argv[1]?.endsWith("env-fingerprint.mjs");
if (isMain) {
  process.stdout.write(JSON.stringify(captureEnv(), null, 2) + "\n");
}
