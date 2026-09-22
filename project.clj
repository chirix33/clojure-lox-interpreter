;;;; Leiningen build configuration for the Clojure Lox interpreter.
;;;;
;;;; Usage:
;;;;   lein test          ; run the full unit-test suite (all chapters)
;;;;   lein run script.lox; run a Lox script
;;;;   lein run           ; start the Lox REPL
;;;;   lein uberjar       ; build a standalone jar
(defproject clojure-lox "0.13.0"
  :description
  "A tree-walking interpreter for the Lox programming language, written in
   Clojure, following Robert Nystrom's _Crafting Interpreters_ chapters 4-13."
  :url "https://craftinginterpreters.com"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.11.1"]]
  :source-paths ["src"]
  :test-paths ["test"]
  :main ^:skip-aot lox.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
