# 3. Postman CLI instead of Newman

Date: 2026-11-11 · Status: Accepted

## Context
The build doc runs collections with Newman and lints the spec with Spectral. The Postman CLI runs collections stored as git-native v3 YAML, lints specs, collections and environments, and can later provide mocks and monitors from the same files.

## Decision
Use `postman collection run` everywhere the doc says Newman, and `postman spec lint` for OpenAPI syntax. Collections live in `postman/collections/`, environments in `postman/environments/`.

## Consequences
CI installs the Postman CLI. Spectral can still be added for custom style rules if the syntax lint proves too thin.
