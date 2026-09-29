#!/usr/bin/env bash

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/ergo-x.y.z.jar" >&2
    exit 2
fi

ergo_jar="$1"
if [ ! -f "$ergo_jar" ]; then
    echo "ERROR: Ergo JAR not found: $ergo_jar" >&2
    exit 2
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit $?
root="$(dirname -- "$script_dir")"
ERGO_JAR="$(cd -- "$(dirname -- "$ergo_jar")" && pwd)/$(basename -- "$ergo_jar")"
export ERGO_JAR

cd "$root" || exit $?
"$script_dir/sbt.sh" clean package
rc=$?
if [ "$rc" -ne 0 ]; then
    exit "$rc"
fi

built="$(find "$root/target/scala-2.12" -maxdepth 1 -type f -name 'ergo-history-doctor_2.12-*.jar' -print | head -n 1)"
if [ -z "$built" ]; then
    echo "ERROR: Build completed but tool JAR was not found." >&2
    exit 3
fi

echo
echo "Built: $built"
echo "Against Ergo JAR: $ERGO_JAR"
