#!/bin/sh
# gradle-bg.sh NAME task [task ...]: runs gradle in the reviewer's clone in the background.
# Log: ~/north-build/audit-0.7.0/logs/NAME.log; its last line is "exit N hh:mm:ss" when the run has ended.
# One run at a time: a second one is refused while a gradle of this folder is still running.
name="${1:?name}"; shift
A="$HOME/north-build/audit-0.7.0"
cd "$A/polarka-wallet" || exit 1
if [ -f "$A/logs/.running" ] && kill -0 "$(cat "$A/logs/.running")" 2>/dev/null; then
    echo "a gradle run of this folder is still going (pid $(cat "$A/logs/.running")); wait for its log to end"; exit 1
fi
. "$HOME/north-build/env.sh"
rm -f "$A/logs/$name.log"
( gradle --offline --no-daemon -q --continue "$@" > "$A/logs/$name.log" 2>&1; echo "exit $? $(date +%H:%M:%S)" >> "$A/logs/$name.log"; rm -f "$A/logs/.running" ) &
echo $! > "$A/logs/.running"
echo "started $name $(date +%H:%M:%S)"
