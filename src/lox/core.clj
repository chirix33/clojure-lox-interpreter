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
;;;; UPDATED IN CHAPTER 6 - Parsing Expressions (section 6.4, "Wiring up the
;;;; Parser"). `run` scans, parses, and prints the resulting syntax tree with
;;;; chapter 5's AST printer.
;;;;
;;;; UPDATED IN CHAPTER 7 - Evaluating Expressions (section 7.4, "Hooking Up
;;;; the Interpreter"). The pipeline is now complete end to end:
;;;;
;;;;     scan -> tokens -> parse -> expression -> evaluate -> value
;;;;
;;;; and `run` prints the *value* rather than the tree. Chapter 7 also adds a
;;;; second failure mode and a third exit code, from sysexits.h:
;;;;
;;;;   64 (EX_USAGE)       - bad command line
;;;;   65 (EX_DATAERR)     - the source had a static (scan/parse) error
;;;;   70 (EX_SOFTWARE)    - the source ran and hit a runtime error
;;;;
;;;; The distinction matters: a static error means the code never ran, while
;;;; 70 means it ran and failed partway. The REPL, by contrast, ignores both
;;;; flags - "If the user is running the REPL, we don't care about tracking
;;;; runtime errors. After they are reported, we simply loop around."
;;;;
;;;; The book deletes each chapter's temporary output as the next one replaces
;;;; it. We keep the old behaviours behind flags instead - `--tokens` for
;;;; chapter 4's token dump and `--ast` for chapter 6's tree printer - because
;;;; each chapter is a graded submission of its own and its example script
;;;; should stay runnable.
;;;; =============================================================================
(ns lox.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [lox.ast-printer :as printer]
            [lox.errors :as err]
            [lox.interpreter :as interp]
            [lox.parser :as parser]
            [lox.scanner :as scanner]
            [lox.token :as tok]))

(defn print-tokens
  "Chapter 4's `run`: dump the token stream, one token per line.

  Superseded by parsing in chapter 6, but kept available behind `--tokens` so
  that chapter 4 can still be demonstrated."
  [source]
  (doseq [token (scanner/scan-tokens source)]
    (println (tok/token->string token))))

(defn print-ast
  "Chapter 6's `run`: scan, parse, and print the syntax tree.

  Superseded by evaluation in chapter 7, but kept available behind `--ast` so
  that chapter 6 can still be demonstrated - and because seeing the tree is
  still the quickest way to check that precedence came out right."
  [source]
  (let [expression (parser/parse (scanner/scan-tokens source))]
    (when-not @err/had-error
      (println (printer/print-ast expression)))))

(defn run
  "Book: `Lox.run`.

  Chapter 7 version: scan, parse, and *evaluate*, printing the resulting value.

  The `hadError` check between parsing and interpreting is the book's (\"Stop
  if there was a syntax error\"), and it is load-bearing: `parse` returns nil
  on a syntax error, and handing nil to the interpreter would replace the
  useful message the parser already reported with a meaningless one from
  `accept`. Checking the flag rather than the nil is also what generalises -
  from chapter 8 the parser returns a *partial* list of statements after an
  error, which is non-nil but still must not be run."
  [source]
  (let [tokens     (scanner/scan-tokens source)
        expression (parser/parse tokens)]
    (when-not @err/had-error
      (interp/interpret expression))))

(defn run-file
  "Book: `Lox.runFile`. Execute a script; returns the process exit status.

  A static error means we never ran the code at all, and exits 65. A runtime
  error means we ran it and it failed partway, and exits 70 (chapter 7). The
  static check comes first: if parsing failed, nothing ran, so there cannot be
  a meaningful runtime result to report."
  ([path] (run-file path run))
  ([path run-fn]
   (err/reset-errors!)
   (run-fn (slurp (io/file path)))
   (cond
     @err/had-error         65
     @err/had-runtime-error 70
     :else                  0)))

(defn run-prompt
  "Book: `Lox.runPrompt`. The interactive REPL.

  `hadError` is reset after every line: a mistake on one line should not kill
  the whole session. Control-D (EOF) makes `read-line` return nil and ends it."
  ([] (run-prompt run))
  ([run-fn]
   (loop []
     (print "> ")
     (flush)
     (when-let [line (read-line)]
       (err/reset-errors!)
       (run-fn line)
       (recur)))))

(def stage-flags
  "Optional leading flag -> the `run` variant it selects.

  Not in the book, which deletes each stage's output as the next replaces it.
  Keeping the earlier stages reachable means every chapter's example script
  stays runnable, and makes the pipeline visible from the command line:

    clox --tokens f.lox   chapter 4   source -> tokens
    clox --ast    f.lox   chapter 6   source -> syntax tree
    clox          f.lox   chapter 7   source -> value"
  {"--tokens" print-tokens
   "--ast"    print-ast})

(defn -main
  "Book: `Lox.main`. With one argument, run that script; with none, start the
  REPL; with more, complain and exit 64."
  [& args]
  (let [run-fn (get stage-flags (first args) run)
        args   (if (contains? stage-flags (first args)) (rest args) args)
        status (cond
                 (> (count args) 1)
                 (do (println "Usage: clox [--tokens | --ast] [script]") 64)

                 (= (count args) 1) (run-file (first args) run-fn)
                 :else              (do (run-prompt run-fn) 0))]
    (flush)
    (System/exit status)))
