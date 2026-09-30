#!/usr/bin/env bash

if [ "$#" -lt 2 ]; then
    echo "usage: $0 /path/to/ergo-x.y.z.jar <doctor-command> [options...]" >&2
    exit 2
fi

invocation_dir="$(pwd -P)" || exit $?

abspath() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *)  printf '%s/%s\n' "$invocation_dir" "$1" ;;
    esac
}

ergo_jar="$(abspath "$1")"
shift

if [ ! -f "$ergo_jar" ]; then
    echo "ERROR: Ergo JAR not found: $ergo_jar" >&2
    exit 2
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)" || exit $?
root="$(dirname -- "$script_dir")"

tool_jar="$(find "$root/target/scala-2.12" \
    -maxdepth 1 \
    -type f \
    -name 'ergo-history-doctor_2.12-*.jar' \
    -print | head -n 1)"

if [ -z "$tool_jar" ]; then
    echo "ERROR: Tool JAR not found. Run scripts/build.sh first." >&2
    exit 3
fi

args=("$@")
config_path=""

i=0
while [ "$i" -lt "${#args[@]}" ]; do
    arg="${args[$i]}"

    case "$arg" in
        --config|--out|--plan)
            next=$((i + 1))

            if [ "$next" -ge "${#args[@]}" ]; then
                echo "ERROR: Missing value for $arg" >&2
                exit 4
            fi

            resolved="$(abspath "${args[$next]}")"
            args[$next]="$resolved"

            if [ "$arg" = "--config" ]; then
                config_path="$resolved"
            fi

            i=$next
            ;;
    esac

    i=$((i + 1))
done

work_dir="$invocation_dir"

if [ -n "$config_path" ]; then
    if [ ! -f "$config_path" ]; then
        echo "ERROR: Config file not found: $config_path" >&2
        exit 5
    fi

    work_dir="$(dirname -- "$config_path")"
fi

cd "$work_dir" || exit $?

java \
    -cp "$tool_jar:$ergo_jar" \
    org.ergoplatform.nodeView.history.ErgoHistoryDoctor \
    "${args[@]}"

exit $?
