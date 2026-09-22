;;;; =============================================================================
;;;; Test runner
;;;;
;;;; A dependency-free entry point so the suite can be run with nothing but a
;;;; JDK and the Clojure jar:
;;;;
;;;;   ./scripts/test.sh     (or)  clojure -M:test     (or)  lein test
;;;;
;;;; Each chapter gets its own test namespace; they are listed here in book
;;;; order and new ones are added as each chapter is implemented.
;;;; =============================================================================
(ns lox.test-runner
  (:require [clojure.test :as t]
            [lox.chapter-04-scanning-test]))

(def chapter-namespaces
  "Chapter test namespaces, in book order."
  ['lox.chapter-04-scanning-test])

(defn -main
  "Run every chapter's tests (or just the namespaces named on the command
  line). Exits non-zero if anything failed, so CI and shell scripts work."
  [& args]
  (let [nss    (if (seq args) (map symbol args) chapter-namespaces)
        result (apply t/run-tests nss)
        broken (+ (:fail result) (:error result))]
    (println)
    (printf "%d tests, %d assertions, %d failures, %d errors.%n"
            (:test result) (:pass result) (:fail result) (:error result))
    (flush)
    (System/exit (if (zero? broken) 0 1))))
