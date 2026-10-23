# CI

Every workflow runs on GitHub-hosted runners (`ubuntu-latest`, `macos-14`, `windows-2022`), so a
copy of this repository does not need Microsoft's self-hosted pools or larger runners.

## What runs when

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `pr.yml` (`CI`) | push to `main`, pull request to `main` / `release/*`, manual | `Compile` compiles the `build/` folder and then core + extension bundles; `Hygiene & Type Checks` runs `hygiene`, `eslint`, the layer / class-field / `.d.ts` / `tsec` checks. The Rust CLI tests run in their own job. The two jobs are separate so that neither one needs more memory than a 4-core hosted runner has. |
| `pr.yml` platform matrix | label a pull request `ci:full`, or run the workflow by hand with `platform_tests` | Unit, integration and smoke tests on Linux (`pr-linux-test.yml`), macOS (`pr-darwin-test.yml`) and Windows (`pr-win32-test.yml`). Slow, because it downloads Electron and Playwright per OS. |
| `no-package-lock-changes.yml`, `no-yarn-lock-changes.yml` | pull request | Fails when a PR from someone without write access changes a lock file. |
| `telemetry.yml` | pull request | Runs `vscode-telemetry-extractor` over the tree. |
| `volt-stable.yml`, `volt-beta.yml`, `volt-nightly.yml` | `v*` tag, `v*-beta*` tag, schedule at 03:00 UTC, manual | Release pipeline. See [releasing.md](releasing.md). |
| `monaco-editor.yml` | manual | Standalone Monaco editor checks. Volt does not publish Monaco, so this is not part of CI. |
| `pr-node-modules.yml` (`Warm node_modules caches`) | manual | Fills the per-OS `node_modules-*` caches used by the matrix jobs. |
| `copilot-setup-steps.yml` | push / PR touching that file, manual | Development environment for the Copilot coding agent. |

Run the full matrix for a pull request by adding the label `ci:full` (create the label once under
Issues → Labels), or by hand:

```sh
gh workflow run pr.yml --ref <branch> -f platform_tests=true
```

## Settings a new repository needs

1. **Actions enabled** (Settings → Actions → General). A repository created as a *fork* starts with
   workflows disabled; GitHub asks for confirmation before it runs them.
2. **Workflow permissions**: Settings → Actions → General → Workflow permissions →
   *Read and write permissions*. `GITHUB_TOKEN` creates the GitHub Release and pushes the
   `volt-update-feed` branch, which needs `contents: write`. Without it the release build finishes
   but the publish job fails.
3. **Secrets** for signing are optional, see [releasing.md](releasing.md#signing-secrets). Without
   them macOS builds are ad-hoc signed and Windows builds are unsigned, with a warning in the run.
4. **Push the tags** for a release. Cloning a repository copies tags locally but not to a new
   remote: `git push origin --tags`. A `v*` tag starts the stable build, `v*-beta*` the beta.
5. **Scheduled workflows** are disabled after 60 days without repository activity. Push something
   or re-enable `Volt Nightly` from the Actions tab if the nightly stops.

## Notes

- The compiled `.js` output of `build/` is committed next to its `.ts` sources. After editing
  `build/**/*.ts`, run `cd build && npm run compile` and commit the result, otherwise the
  `Check /build/ folder` step fails with the offending files.
- `node_modules` is cached per OS/arch from a hash of the lock file. The first run on a new commit
  installs, later runs extract the archive.
- `concurrency` cancels superseded runs for the same branch, so pushing twice in a row keeps only
  the newest run.
