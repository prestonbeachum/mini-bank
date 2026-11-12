#!/bin/sh
# Lambda entrypoint for the AWS Lambda Web Adapter (build doc section 10). The adapter
# (layer + AWS_LAMBDA_EXEC_WRAPPER=/opt/bootstrap) forwards Lambda events as HTTP to PORT.
# The same JAR runs unchanged under Docker Compose; only this wrapper differs.
exec java \
  -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
  -Dserver.port="${PORT:-8080}" \
  -jar /var/task/mini-bank.jar
