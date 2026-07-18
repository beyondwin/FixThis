---
name: fixthis-release-maintenance
description: Use when auditing or executing FixThis release, publication, compatibility, or public-install work from the source checkout.
---

# FixThis Release Maintenance

Use only for FixThis release work. Keep repository proof, connected Android proof, Git state, and public registry reality separate.

1. Read `docs/contributing/release-readiness.md`, `docs/contributing/release-process.md`, and `CONTRIBUTING.md`.
2. Run `npm run agent:route -- --task release --json`.
3. Establish intended version and channels without changing them.
4. Run `npm run release:reality` and record exact registry, tag, and downstream evidence.
5. Run `npm run evidence:release` for repository evidence.
6. Run `npm run android:proof -- --strict --continue` when the contract requires connected proof.
7. Run `npm run release:check` before a release PR or tag.
8. Report PASS, FAIL, DEFERRED, and SKIPPED separately with reason and residual risk.
9. Verify local HEAD, upstream, remote branch, tag, registry, and downstream state independently.

Tag, publish, push, or edit a downstream repository only when the user explicitly requests that state change. Never treat a proxy marker, cached page, deferred device check, or local commit as publication proof.
