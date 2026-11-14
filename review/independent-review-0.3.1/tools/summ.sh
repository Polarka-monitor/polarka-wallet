#!/bin/sh
# Summarises JUnit XML results of the audit copy: one line per test class, then totals.
# usage: sh tools/summ.sh [pattern]   (pattern filters file names, e.g. Audit)
cd "$(dirname "$0")/../north-wallet" || exit 1
pat="${1:-}"
tt=0; tf=0; te=0; ts=0
for f in */build/test-results/*/TEST-*.xml; do
  [ -f "$f" ] || continue
  case "$f" in *"$pat"*) ;; *) continue;; esac
  line=$(grep -m1 '<testsuite ' "$f")
  n=$(echo "$line" | sed -E 's/.* tests="([0-9]+)".*/\1/')
  s=$(echo "$line" | sed -E 's/.* skipped="([0-9]+)".*/\1/')
  fl=$(echo "$line" | sed -E 's/.* failures="([0-9]+)".*/\1/')
  e=$(echo "$line" | sed -E 's/.* errors="([0-9]+)".*/\1/')
  t=$(echo "$line" | sed -E 's/.* time="([0-9.]+)".*/\1/')
  ts2=$(echo "$line" | sed -E 's/.* timestamp="([^"]+)".*/\1/')
  echo "$(basename "$f" .xml | sed 's/^TEST-//')  tests=$n failures=$fl errors=$e skipped=$s time=${t}s at=$ts2"
  tt=$((tt+n)); tf=$((tf+fl)); te=$((te+e)); ts=$((ts+s))
done
echo "TOTAL tests=$tt failures=$tf errors=$te skipped=$ts"
