;;;; =============================================================================
;;;; Chapter 4 - Scanning  (the interpreter framework: the book's `Lox.java`)
;;;;
;;;; The command-line entry point. Book section 4.1 "The Interpreter Framework"
;;;; defines main / runFile / runPrompt / run, plus the exit-code conventions
;;;; borrowed from the UNIX sysexits.h header:
;;;;
;;;;   64 (EX_USAGE)    - bad command line
;;;;   65 (EX_DATAERR)  - the source had a static error
;;;;
;;;; At this stage `run` only scans: it prints the token stream so we can see
;;;; the scanner working. Later chapters will extend `run` with a parser and an
;;;; interpreter; for now, printing tokens is the whole program.
;;;; =============================================================================
(ns lox.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [lox.errors :as err]
            [lox.token :as tok]
            [lox.scanner :as scanner]))

(defn run
  "Book: `Lox.run`.

  Chapter 4 version: scan the source and print each token. There is no parser
  yet, so this is how we check that we are making progress."
  [source]
  (doseq [token (scanner/scan-tokens source)]
    (println (tok/token->string token))))

(defn run-file
  "Book: `Lox.runFile`. Execute a script; returns the process exit status.

  A static error means we never try to run the code and we exit with 65."
  [path]
  (err/reset-errors!)
  (run (slurp (io/file path)))
  (if @err/had-error 65 0))

(defn run-prompt
  "Book: `Lox.runPrompt`. The interactive REPL.

  `hadError` is reset after every line: a mistake on one line should not kill
  the whole session. Control-D (EOF) makes `read-line` return nil and ends it."
  []
  (loop []
    (print "> ")
    (flush)
    (when-let [line (read-line)]
      (err/reset-errors!)
      (run line)
      (recur))))

(defn -main
  "Book: `Lox.main`. With one argument, run that script; with none, start the
  REPL; with more, complain and exit 64."
  [& args]
  (let [status (cond
                 (> (count args) 1) (do (println "Usage: clox [script]") 64)
                 (= (count args) 1) (run-file (first args))
                 :else              (do (run-prompt) 0))]
    (flush)
    (System/exit status)))
