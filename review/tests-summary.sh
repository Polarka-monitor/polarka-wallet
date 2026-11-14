#!/bin/sh
# One line per test class from the JUnit results of the last test run, then the totals, then every failure.
# usage: sh review/tests-summary.sh [pattern]     (pattern filters class names, for example Audit)
# Based on the two scripts the independent auditor wrote for the same purpose (review/independent-review-0.3.1/tools).
cd "$(dirname "$0")/.." || exit 1
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
  echo "$(basename "$f" .xml | sed 's/^TEST-//')  tests=$n failures=$fl errors=$e skipped=$s time=${t}s"
  tt=$((tt+n)); tf=$((tf+fl)); te=$((te+e)); ts=$((ts+s))
done
echo "TOTAL tests=$tt failures=$tf errors=$te skipped=$ts"
for f in */build/test-results/*/TEST-*.xml; do
  [ -f "$f" ] || continue
  case "$f" in *"$pat"*) ;; *) continue;; esac
  awk -v class="$(basename "$f" .xml | sed 's/^TEST-//')" '
    /<testcase / { name=$0; sub(/.*<testcase name="/,"",name); sub(/".*/,"",name); pend=name; next }
    /<failure / || /<error / { msg=$0; sub(/.*message="/,"",msg); sub(/" type=.*/,"",msg); print "FAIL  " class "." pend; print "      " substr(msg,1,900); next }
    /SKIPPED/ { print "note  " class ": " $0 }
  ' "$f" | sed -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&#10;/ | /g' -e 's/&amp;/\&/g'
done
