# v0.6 Release Evidence Template

Paste this into the v0.6 release issue before tagging.

## Handoff Intelligence

- [ ] `npm run handoff:eval:test`
- [ ] Corpus summary attached:
  - total cases:
  - high-confidence wrong edit surfaces:
  - top-3 source candidate coverage:
  - prompt budget result:

## Studio Reliability

- [ ] `npm run console:reliability:test`
- [ ] `npm run console:session:test`
- [ ] `npm run console:draft:test`
- [ ] `npm run console:preview:test`
- [ ] `npm run console:browser:reliability`

## Release Grade

- [ ] `npm run release:v06:evidence:test`
- [ ] `node scripts/check-release-readiness.mjs`
- [ ] `bash scripts/check-docs-cli-surface.sh`
- [ ] `npm run checks:observation -- --json`

## Observation Results

Paste the JSON from:

```bash
npm run checks:observation -- --json
```

If connected tests or nightly compatibility are not ready, either narrow the
release note claim or record the explicit acceptance here.

## Artifact Integrity

- [ ] GitHub CLI/MCP tarball exists.
- [ ] SHA-256 sidecar exists.
- [ ] npm wrapper points at the intended release.
- [ ] Homebrew formula points at the intended release.
- [ ] Gradle plugin and Maven coordinates match README.
- [ ] MCP Registry metadata matches `server.json`.
- [ ] Release/minify consumer fixture passed or deferral is recorded.
