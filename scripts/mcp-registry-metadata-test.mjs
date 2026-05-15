import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));

test("MCP registry server metadata points at the public npm wrapper", () => {
  const server = JSON.parse(readFileSync(join(repoRoot, "server.json"), "utf8"));
  const npmPackage = JSON.parse(readFileSync(join(repoRoot, "npm/fixthis/package.json"), "utf8"));

  assert.equal(server.$schema, "https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json");
  assert.equal(server.name, "io.github.beyondwin/fixthis");
  assert.equal(server.version, npmPackage.version);
  assert.equal(npmPackage.mcpName, server.name);
  assert.equal(server.repository.url, "https://github.com/beyondwin/FixThis");
  assert.deepEqual(server.packages, [
    {
      registryType: "npm",
      identifier: npmPackage.name,
      version: npmPackage.version,
      transport: {
        type: "stdio",
      },
    },
  ]);
});
