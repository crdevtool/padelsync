#!/usr/bin/env bash
# Publishes the tail of a build log to the branch ci-logs/<name>, replacing
# whatever was there. Build logs are otherwise only readable in the GitHub
# web UI; this makes the latest one for each job fetchable with plain git.
#
# Usage: publish-log.sh <name> <log-file> <job-status>
set -euo pipefail

name="$1"
log="$2"
status="${3:-unknown}"

work="$(mktemp -d)"
git init --quiet "$work"
cd "$work"
git checkout --quiet --orphan logs

if [ -f "$GITHUB_WORKSPACE/$log" ]; then
  tail -c 300000 "$GITHUB_WORKSPACE/$log" > build.log
else
  echo "(no log file was produced)" > build.log
fi
{
  echo "status: $status"
  echo "commit: $GITHUB_SHA"
  echo "run: $GITHUB_RUN_ID"
  echo "time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > status.txt

git add build.log status.txt
git -c user.name="padelsync-ci" -c user.email="ci@users.noreply.github.com" \
  commit --quiet -m "$name: $status at ${GITHUB_SHA:0:7}"
git push --quiet --force \
  "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" \
  "HEAD:refs/heads/ci-logs/$name"
