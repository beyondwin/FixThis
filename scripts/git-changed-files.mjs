import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

function defaultRunGit(args, cwd) {
  return spawnSync("git", args, { cwd, encoding: "utf8" });
}

function lines(text) {
  return text.split("\0").filter(Boolean);
}

export function collectChangedFiles({
  cwd = process.cwd(),
  base = null,
  upstream = null,
  runGit = defaultRunGit,
} = {}) {
  const warnings = [];
  let target = base || upstream;
  if (!target) {
    const parent = runGit(["rev-parse", "--verify", "HEAD^"], cwd);
    if (parent.status === 0) {
      target = "HEAD^";
    } else {
      target = "HEAD";
      warnings.push("Git parent revision is unavailable; using HEAD as the change base.");
    }
  }
  let mergeBase = target;
  if (target !== "HEAD" && target !== "HEAD^") {
    const result = runGit(["merge-base", "HEAD", target], cwd);
    if (result.status === 0) mergeBase = result.stdout.trim();
    else warnings.push("Unable to resolve merge-base for " + target + ".");
  }
  const commands = [
    ["diff", "--no-renames", "--name-only", "-z", mergeBase + "..HEAD"],
    ["diff", "--no-renames", "--name-only", "-z", "--cached"],
    ["diff", "--no-renames", "--name-only", "-z"],
    ["ls-files", "--others", "--exclude-standard", "-z"],
  ];
  const changedFiles = [];
  for (const args of commands) {
    const result = runGit(args, cwd);
    if (result.status === 0) changedFiles.push(...lines(result.stdout));
    else warnings.push("Git command failed: git " + args.join(" "));
  }
  return { changedFiles: [...new Set(changedFiles)], warnings };
}

// The shell gate uses the same inventory as agent routing. Fail closed when
// Git cannot establish the diff; a partial list must never skip verification.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== "--base" || !args[1]) {
    process.stderr.write("Usage: node scripts/git-changed-files.mjs --base <ref>\n");
    process.exitCode = 2;
  } else {
    const result = collectChangedFiles({ base: args[1] });
    if (result.warnings.length) {
      process.stderr.write(result.warnings.join("\n") + "\n");
      process.exitCode = 1;
    } else {
      process.stdout.write(result.changedFiles.join("\n") + "\n");
    }
  }
}
