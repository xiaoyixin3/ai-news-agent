#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
command -v java >/dev/null 2>&1 || { echo 'Please install Java 17 or newer.' >&2; exit 1; }
if [ ! -f target/ai-news-agent.jar ] || [ "${1:-}" = "--rebuild" ]; then
  ./mvnw -q -DskipTests package
fi
echo 'Open http://127.0.0.1:8080 (or the port configured by PORT). Ctrl+C stops the service.'
exec java -jar target/ai-news-agent.jar
