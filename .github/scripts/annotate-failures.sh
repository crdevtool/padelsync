#!/usr/bin/env bash
# Passes a build's output through unchanged, and repeats the lines that say
# why it failed as GitHub error notes, so the reason shows at the top of the
# run's page without opening the log.
#
# Usage: some-build 2>&1 | annotate-failures.sh "Title for the notes"
#
# At most ten lines are repeated; a failed build can print hundreds.
title="${1:-Build}"
awk -v title="$title" '
  { print; fflush() }
  /^FAILED: / || /(^|[ :])error: / {
    if (notes < 10) {
      notes++
      line = $0
      # GitHub reads "::" inside a note as the end of it.
      gsub(/::/, ": :", line)
      print "::error title=" title "::" line
      fflush()
    }
  }
'
