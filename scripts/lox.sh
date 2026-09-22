#!/usr/bin/env bash
# Runs a Lox script (or the REPL) using only a JDK + the jars in lib/.
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/bootstrap.sh
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
CP="src${SEP}lib/clojure-1.11.1.jar${SEP}lib/spec.alpha-0.3.218.jar${SEP}lib/core.specs.alpha-0.2.62.jar"
exec java -cp "$CP" clojure.main -m lox.core "$@"
