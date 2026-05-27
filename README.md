# orcestra-jenkins-shared-library

This repository contains a Jenkins shared library for Orcestra pipeline helpers.

It provides common pipeline utilities that make it easier to:

- send stage notifications,
- wrap stage execution with safe notification handling,
- detect aborted pipeline errors,
- run a Pixi/Snakemake pipeline with checkout, environment setup, dry run, execution, optional QC, and upload to Google Cloud Storage.

## Key helpers in `vars/`

- `notifyStage.groovy` — sends stage status notifications.
- `notifyStageSafe.groovy` — calls `notifyStage` safely and logs a warning if notifications fail.
- `runStageWithNotification.groovy` — runs a stage body with `running`/`succeeded`/`failed` notifications and abort-aware handling.
- `runPixiSnakemakePipeline.groovy` — orchestrates a Pixi-backed Snakemake pipeline inside a container and uploads outputs to GCS.
- `runShellWithCapturedError.groovy` — executes a shell script with error capture, writing output to a log file and reporting the last 80 lines on failure.
- `isAbortError.groovy` — detects abort/interrupt exceptions so failed notifications can be skipped on user abort.

Use this library from a Jenkinsfile by loading it as a shared library and invoking the provided vars as pipeline steps.

## Linting

This project uses `npm-groovy-lint` for Groovy linting and formatting.
The lint configuration is defined in `.groovylintrc.json`, and that config controls the rules used by the linter.

Run linting with Pixi commands from `pixi.toml`:

```bash
pixi run lint
```

Other available commands:

```bash
pixi run format
pixi run fix
pixi run lint-fix
```

These map to:

- `lint`: `npx npm-groovy-lint -c .groovylintrc.json vars/**/*.groovy`
- `format`: `npx npm-groovy-lint -c .groovylintrc.json --format vars/**/*.groovy`
- `fix`: `npx npm-groovy-lint -c .groovylintrc.json --fix vars/**/*.groovy`
- `lint-fix`: runs `format`, `fix`, and `lint` in sequence.

If you need to run the linter directly without Pixi, use the same `npx` command with `.groovylintrc.json`.
