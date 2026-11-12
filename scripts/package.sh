#!/usr/bin/env bash
# Assembles the release bundle in dist/ from the JAR and SBOM that `./mvnw verify` already
# produced. Nothing is recompiled: every artifact wraps the same tested JAR (build doc section 11).
#
#   scripts/package.sh <version>
set -euo pipefail

VERSION="${1:?usage: scripts/package.sh <version>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

JAR="$(find app/target -maxdepth 1 -name 'mini-bank-*.jar' ! -name '*-plain.jar' | head -1)"
# Spring Boot's parent runs CycloneDX and embeds this SBOM in the JAR (META-INF/sbom).
SBOM="app/target/classes/META-INF/sbom/application.cdx.json"
[ -f "$JAR" ] || { echo "no JAR in app/target; run ./mvnw verify first" >&2; exit 1; }
[ -f "$SBOM" ] || { echo "no SBOM at $SBOM" >&2; exit 1; }

rm -rf dist
mkdir -p dist/lambda dist/docker

cp "$JAR" "dist/mini-bank-$VERSION.jar"
cp "$SBOM" "dist/mini-bank-$VERSION-sbom.cdx.json"
cp "$JAR" dist/docker/mini-bank.jar

# Lambda zip for the Web Adapter: the JAR plus the run.sh entrypoint, stable file order.
cp "$JAR" dist/lambda/mini-bank.jar
cp packaging/lambda/run.sh dist/lambda/run.sh
chmod 755 dist/lambda/run.sh
(cd dist/lambda && zip -q -X "../mini-bank-$VERSION-lambda.zip" run.sh mini-bank.jar)
rm -rf dist/lambda

# What the release was tested against, so it can be re-tested exactly as shipped.
cp api/openapi.yaml "dist/openapi-$VERSION.yaml"
tar -czf "dist/postman-$VERSION.tar.gz" postman/collections postman/environments
if [ -n "$(find perf -name '*.js' 2>/dev/null)" ]; then
  tar -czf "dist/k6-$VERSION.tar.gz" perf
fi

(cd dist && find . -maxdepth 1 -type f ! -name SHA256SUMS -exec basename {} \; | sort | xargs shasum -a 256 > SHA256SUMS)
echo "release bundle for $VERSION:"
ls -l dist | sed 1d
