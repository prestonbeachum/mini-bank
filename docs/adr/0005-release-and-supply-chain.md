# 5. Release pipeline and supply chain

Date: 2026-11-11 · Status: Accepted

## Context
Build doc section 11: one immutable artifact per release, promoted through every environment, scanned, signed, with an SBOM, re-testable exactly as shipped. Phase 2 has no AWS yet, so "published" means GitHub Releases and GHCR.

## Decision
- **Versioning.** release-please (`simple` strategy) reads Conventional Commits on `main` and maintains a release PR that bumps `.release-please-manifest.json`, `app/pom.xml` (via the `x-release-please-version` marker) and `CHANGELOG.md`. Merging it tags `vX.Y.Z`.
- **Build once.** The release job runs `./mvnw verify` once at the tag. `scripts/package.sh` wraps that JAR; `Dockerfile.release` copies it into a digest-pinned `eclipse-temurin:21-jre` image (never compiles). The Lambda zip is the same JAR plus `packaging/lambda/run.sh` for the Lambda Web Adapter.
- **Bundle.** Each GitHub Release carries: JAR, Lambda zip, CycloneDX SBOM (Spring Boot's own, also embedded in the JAR), `openapi.yaml`, Postman collections and environments, k6 scripts once they exist, the Trivy image report, the image digest, `SHA256SUMS`, and Sigstore bundles.
- **Signing.** Keyless cosign through GitHub OIDC: the image (by digest) is signed and carries a CycloneDX SBOM attestation; the JAR, Lambda zip and `SHA256SUMS` get `.sigstore.json` bundles. No signing keys exist to leak.
- **Scan gates.** PRs fail on any fixable HIGH or CRITICAL in dependencies, Dockerfiles or committed secrets (Trivy fs). Releases fail on any fixable CRITICAL in the image; HIGH findings are recorded in the attached report. At the time of writing the newest Temurin base image carries a fixable HIGH in `apt`, which the app never runs; blocking every release on it would invite skipping the gate.
- **Pinning.** Every action by commit SHA, tool versions explicit (Trivy, Schemathesis, palantir-java-format), and only releases at least two weeks old when pinned.
- **Release PR checks.** PRs opened with `GITHUB_TOKEN` do not trigger workflows, so the release PR shows no checks. It only touches versions and the changelog; the owner merges it with admin bypass. A GitHub App token would remove the bypass if a second maintainer ever joins.

## Consequences
Deploy jobs (phase 3) take an image digest or release asset and must run `cosign verify` / `cosign verify-blob` with the certificate identity of `release.yml` on `main`, and refuse anything without a signature or scan report.

Verify a release by hand:

```sh
cosign verify ghcr.io/prestonbeachum/mini-bank@sha256:<digest> \
  --certificate-identity-regexp '^https://github.com/prestonbeachum/mini-bank/.github/workflows/release.yml@refs/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-blob mini-bank-X.Y.Z.jar --bundle mini-bank-X.Y.Z.jar.sigstore.json \
  --certificate-identity-regexp '^https://github.com/prestonbeachum/mini-bank/.github/workflows/release.yml@refs/' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```
