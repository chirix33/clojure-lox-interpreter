# Runs the full unit-test suite using only a JDK + the jars in lib/.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "bootstrap.ps1")
$cp = @(
  (Join-Path $root "src"),
  (Join-Path $root "test"),
  (Join-Path $root "lib\clojure-1.11.1.jar"),
  (Join-Path $root "lib\spec.alpha-0.3.218.jar"),
  (Join-Path $root "lib\core.specs.alpha-0.2.62.jar")
) -join ";"
& java -cp $cp clojure.main -m lox.test-runner @args
exit $LASTEXITCODE
