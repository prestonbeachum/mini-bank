## What and why

<!-- One or two sentences. Link the build-doc section or issue if there is one. -->

## Checklist (build doc section 15)

- [ ] Tests at the right layers, including an invariant/property test if money moves
- [ ] `api/openapi.yaml` and the Postman collections updated if the API changed
- [ ] Migration is forward-only, backward compatible with the running release, and grants `ledger_app` explicitly
- [ ] Metrics, logs and a runbook entry for any new failure mode
- [ ] ADR added or superseded if a decision changed
- [ ] Commit title follows Conventional Commits (`feat:`, `fix:`, `chore:` …); release-please reads it
- [ ] PR stays under ~400 changed lines, or says why not
