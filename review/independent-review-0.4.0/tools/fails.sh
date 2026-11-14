#!/bin/sh
# Prints, for the audit test classes, every test with its outcome, the first lines of a failure, and the AUDIT lines the tests printed.
# usage: sh tools/fails.sh [pattern]
cd "$(dirname "$0")/../polarka-wallet" || exit 1
pat="${1:-Retest}"
for f in */build/test-results/*/TEST-*.xml; do
  case "$f" in *"$pat"*) ;; *) continue;; esac
  echo "=== $(basename "$f" .xml | sed 's/^TEST-//')"
  awk '
    /<testcase / { name=$0; sub(/.*<testcase name="/,"",name); sub(/".*/,"",name); t=$0; sub(/.* time="/,"",t); sub(/".*/,"",t); cur=name; printed=0;
                   if ($0 ~ /\/>[ ]*$/) { print "  PASS  " name "  (" t "s)" } else { pend=name; ptime=t } next }
    /<failure / || /<error / { msg=$0; sub(/.*message="/,"",msg); sub(/" type=.*/,"",msg); print "  FAIL  " pend "  (" ptime "s)"; print "        " substr(msg,1,700); pend=""; next }
    /<\/testcase>/ { if (pend != "") print "  PASS  " pend "  (" ptime "s)"; pend=""; next }
    /(AUDIT|RETEST) / { line=$0; sub(/.*RETEST /,"RETEST ",line); print "  out:  " substr(line,1,900) }
    /SKIPPED/ { print "  out:  " $0 }
  ' "$f" | sed -e 's/&quot;/"/g' -e "s/&apos;/'/g" -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&#10;/ | /g' -e 's/&amp;/\&/g'
done
