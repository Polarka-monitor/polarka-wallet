#!/bin/zsh
# Reviewer's own mutants, one at a time; each file is restored from git afterwards.
cd ~/north-build/audit-0.6.0/polarka-wallet || exit 1
. ~/north-build/env.sh
C=node/src/main/kotlin/north/node/ServerClient.kt
E=app/src/main/kotlin/org/polarka/wallet/ServerEngine.kt
run() { # name, gradle args...
  local name=$1; shift
  if gradle --offline --no-daemon -q "$@" > ../logs/mutant-$name.log 2>&1; then echo "$name: SURVIVED"; else echo "$name: caught"; fi
}
# M1: redirects followed
sed -i '' 's/c.instanceFollowRedirects = false/c.instanceFollowRedirects = true/' $C
run redirects :node:test; git checkout -- $C
# M2: no depth check on coins
sed -i '' 's/if (height > now.first || deep != (now.first - height + 1).toLong()) {/if (false) {/' $C
run depth :node:test; git checkout -- $C
# M3: index freshness: node height not compared
sed -i '' 's/&& height == nodeHeight && hash == nodeHash/\&\& hash == nodeHash/' $C
run nodeheight :node:test; git checkout -- $C
# M4: history line whose part got back at least as much is not left out
sed -i '' 's/some.any { it.sentSat == 0L } -> null/false -> null/' $E
run sentzero :app:testDebugUnitTest --tests 'org.polarka.wallet.ServerEngineTest'; git checkout -- $E
# M5: unsure (cut-short parts) ignored
sed -i '' 's/if (it.txid in told.unsure) null else movement(it, rewards)/movement(it, rewards)/' $E
run unsure :app:testDebugUnitTest --tests 'org.polarka.wallet.ServerEngineTest'; git checkout -- $E
git status --short
echo done
