#!/bin/sh
set -eu

case "${EDUCORE_SERVICE:-}" in
  identity) APP_JAR=/app/identity.jar ;;
  academics) APP_JAR=/app/academics.jar ;;
  finance) APP_JAR=/app/finance.jar ;;
  communication) APP_JAR=/app/communication.jar ;;
  gateway) APP_JAR=/app/gateway.jar ;;
  *)
    echo "EDUCORE_SERVICE must be identity, academics, finance, communication, or gateway." >&2
    exit 64
    ;;
esac

exec java -jar "$APP_JAR"
