# Script Agent Notes

Repository scripts are executable local and CI contracts.

- Keep Node scripts compatible with Node 20 ESM and dependency-light.
- Keep shell scripts non-interactive unless their interface says otherwise.
- Use stable exit codes and machine-readable reports for composed gates.
- Never turn a deferred environment into a pass.
- Put reports under build/reports or another ignored artifact directory.
- Add or update node:test coverage before behavior changes.
- Update package.json, CONTRIBUTING.md, local CI, and GitHub Actions together when a canonical command changes.
