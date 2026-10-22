# Repository Guidelines

## Project Structure & Module Organization

This directory currently contains no application code, dependencies, or Git metadata. Establish the project layout alongside the first implementation and update this guide to match.

Use `src/` for application code, `tests/` for automated tests, `assets/` for static resources, and `docs/` for supporting documentation when those directories become relevant. Group modules by feature and keep related implementation files together. Commit only assets required to build or run the project.

## Build, Test, and Development Commands

No build system, package manager, or development commands are configured yet. When selecting a toolchain, provide documented commands for dependency installation, local development, production builds, linting, and tests in `README.md`.

For example, a JavaScript project could expose `npm run dev`, `npm run build`, `npm run lint`, and `npm test` through `package.json`. These commands are examples, not currently available scripts. Commit the selected package manager’s lockfile.

## Coding Style & Naming Conventions

Follow the chosen language’s standard conventions and configure a formatter and linter before substantial development. Keep indentation consistent within each language and encode it in configuration.

Use descriptive names that reflect a module’s responsibility. Prefer small, focused modules and consistent filename casing. Keep generated output and local tooling caches in `.gitignore`.

## Testing Guidelines

No test framework or coverage threshold is configured. Select a framework appropriate to the implementation language and document the test command. Use descriptive test names that explain expected behavior, and cover failure paths for critical logic. Add regression tests when fixing behavioral bugs.

## Commit & Pull Request Guidelines

No Git history exists from which to infer commit conventions. As an initial convention, use concise, imperative subjects such as `feat: add playback controls` or `fix: handle missing media`.

Pull requests should explain the change, link relevant issues, and describe validation performed. Include screenshots for visible interface changes and identify any required configuration or migration steps.

## Security & Configuration

Keep credentials outside version control. Document required environment variables in an example configuration containing placeholder values.
