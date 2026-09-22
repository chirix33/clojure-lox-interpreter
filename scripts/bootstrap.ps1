# Downloads the three Clojure core jars into lib/ so the project can be built
# and tested with nothing but a JDK installed (no Leiningen / clojure CLI).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lib  = Join-Path $root "lib"
New-Item -ItemType Directory -Force -Path $lib | Out-Null
$maven = "https://repo1.maven.org/maven2"
$jars = @(
  @{ Path = "org/clojure/clojure/1.11.1";          File = "clojure-1.11.1.jar" },
  @{ Path = "org/clojure/spec.alpha/0.3.218";      File = "spec.alpha-0.3.218.jar" },
  @{ Path = "org/clojure/core.specs.alpha/0.2.62"; File = "core.specs.alpha-0.2.62.jar" }
)
foreach ($j in $jars) {
  $dest = Join-Path $lib $j.File
  if (-not (Test-Path $dest)) {
    Invoke-WebRequest -Uri "$maven/$($j.Path)/$($j.File)" -OutFile $dest
  }
}
Write-Output "Clojure jars ready in lib/"
