#!/bin/zsh
# matrix.sh SERIAL BENCH TAG APK OLD_APK [FIRST]
# The seven runs for one emulator, one after the other. BENCH is "" (first bench), 2 or 3. TAG names the logs
# (a12, a14, a16). FIRST (1 to 7) starts at a later run. Every log ends with a line "exit N". The logs are
# named after the environment variable RUN (run60 if it is not set). The sixth and the seventh run, a wallet
# server and a wallet server that says no, are new in 0.6.
serial=$1; bench=$2; tag=$3; apk=$4; old=$5; first=${6:-1}
cd "$HOME/north-build/emu" || exit 1
. ./emu-env.sh
export BENCH=$bench
prefix=${RUN:-run60}      # the logs of one version: run50, run51, run60, ...
run() {   # run NUMBER NAME COMMAND...
    number=$1; name=$2; shift 2
    [ "$number" -lt "$first" ] && return
    log="$prefix-$tag-$name.log"
    echo "$(date +%H:%M:%S) $name" >> "$prefix-$tag.progress"
    python3 -B -u "$@" > "$log" 2>&1
    echo "exit $?" >> "$log"
}
run 1 own      t02_wallet.py "$serial" "$apk" own
run 2 public   t02_wallet.py "$serial" "$apk" public
run 3 lock     t03_lock.py "$serial"
run 4 notgone  t04_not_gone_through.py "$serial" "$apk"
run 5 upgrade  t05_upgrade.py "$serial" "$old" "$apk"
run 6 server   t02_wallet.py "$serial" "$apk" server
run 7 saysno   t07_server_says_no.py "$serial" "$apk"
echo "$(date +%H:%M:%S) done" >> "$prefix-$tag.progress"
