#!/usr/bin/env bash
# Downloads the three Clojure core jars into lib/ so the project can be built
# and tested with nothing but a JDK installed (no Leiningen / clojure CLI).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p lib
M=https://repo1.maven.org/maven2
fetch () { [ -f "lib/$2" ] || curl -sSL -o "lib/$2" "$M/$1/$2"; }
fetch org/clojure/clojure/1.11.1                 clojure-1.11.1.jar
fetch org/clojure/spec.alpha/0.3.218             spec.alpha-0.3.218.jar
fetch org/clojure/core.specs.alpha/0.2.62        core.specs.alpha-0.2.62.jar
echo "Clojure jars ready in lib/"
