#!/bin/zsh
# matrix.sh SERIAL BENCH TAG APK OLD_APK [FIRST]
# The five runs for one emulator, one after the other. BENCH is "" (first bench), 2 or 3. TAG names the logs
# (a12, a14, a16). FIRST (1 to 5) starts at a later run. Every log ends with a line "exit N". The logs are
# named after the environment variable RUN (run51 if it is not set).
serial=$1; bench=$2; tag=$3; apk=$4; old=$5; first=${6:-1}
cd "$HOME/north-build/emu" || exit 1
. ./emu-env.sh
export BENCH=$bench
prefix=${RUN:-run51}      # the logs of one version: run50, run51, ...
run() {   # run NUMBER NAME COMMAND...
    number=$1; name=$2; shift 2
    [ "$number" -lt "$first" ] && return
    log="$prefix-$tag-$name.log"
    echo "$(date +%H:%M:%S) $name" >> "$prefix-$tag.progress"
    python3 -u "$@" > "$log" 2>&1
    echo "exit $?" >> "$log"
}
run 1 own      t02_wallet.py "$serial" "$apk" own
run 2 public   t02_wallet.py "$serial" "$apk" public
run 3 lock     t03_lock.py "$serial"
run 4 notgone  t04_not_gone_through.py "$serial" "$apk"
run 5 upgrade  t05_upgrade.py "$serial" "$old" "$apk"
echo "$(date +%H:%M:%S) done" >> "$prefix-$tag.progress"
