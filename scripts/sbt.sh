#!/usr/bin/env bash

if command -v sbt >/dev/null 2>&1; then
    sbt "$@"
    exit $?
fi

version="1.10.11"
cache_root="${XDG_CACHE_HOME:-$HOME/.cache}/ergo-history-doctor"
launcher="$cache_root/sbt-launch-$version.jar"

if [ ! -f "$launcher" ]; then
    mkdir -p "$cache_root" || exit $?
    url="https://repo.maven.apache.org/maven2/org/scala-sbt/sbt-launch/$version/sbt-launch-$version.jar"
    echo "Downloading sbt launcher $version ..."
    if command -v curl >/dev/null 2>&1; then
        curl -fL "$url" -o "$launcher"
        rc=$?
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$launcher" "$url"
        rc=$?
    else
        echo "ERROR: curl or wget is required to download sbt." >&2
        exit 2
    fi
    if [ "$rc" -ne 0 ]; then
        exit "$rc"
    fi
fi

java -jar "$launcher" "$@"
exit $?
