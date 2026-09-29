#!/usr/bin/env bash

if [ "$#" -lt 2 ]; then
    echo "usage: $0 /path/to/ergo-x.y.z.jar <doctor-command> [options...]" >&2
    exit 2
fi

ergo_jar="$1"
shift

if [ ! -f "$ergo_jar" ]; then
    echo "ERROR: Ergo JAR not found: $ergo_jar" >&2
    exit 2
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit $?
root="$(dirname -- "$script_dir")"
tool_jar="$(find "$root/target/scala-2.12" -maxdepth 1 -type f -name 'ergo-history-doctor_2.12-*.jar' -print | head -n 1)"

if [ -z "$tool_jar" ]; then
    echo "ERROR: Tool JAR not found. Run scripts/build.sh first." >&2
    exit 3
fi

java -cp "$tool_jar:$ergo_jar" org.ergoplatform.nodeView.history.ErgoHistoryDoctor "$@"
exit $?
