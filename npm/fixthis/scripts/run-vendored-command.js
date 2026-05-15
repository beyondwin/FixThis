import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function executableFor(command, releaseRoot) {
  const executable = process.platform === "win32" ? `${command}.bat` : command;
  return path.join(releaseRoot, command, "bin", executable);
}

export function runVendoredCommand(command) {
  const currentFile = path.join(packageRoot, "vendor", "current");
  if (!existsSync(currentFile)) {
    console.error(
      `[fixthis] missing vendored CLI package. Reinstall the npm package or run npm rebuild fixthis.`,
    );
    process.exit(1);
  }

  const packageName = readFileSync(currentFile, "utf8").trim();
  const releaseRoot = path.join(packageRoot, "vendor", packageName);
  const executable = executableFor(command, releaseRoot);
  if (!existsSync(executable)) {
    console.error(`[fixthis] missing executable: ${executable}`);
    process.exit(1);
  }

  const child = spawn(executable, process.argv.slice(2), { stdio: "inherit" });
  child.on("error", (error) => {
    console.error(`[fixthis] failed to launch ${command}: ${error.message}`);
    process.exit(1);
  });
  child.on("exit", (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code ?? 1);
  });
}
