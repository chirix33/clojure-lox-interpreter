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
;;;; Parser"). `run` no longer prints the token stream. It now scans, parses,
;;;; and prints the resulting syntax tree with chapter 5's AST printer:
;;;;
;;;;     scan -> tokens -> parse -> expression -> print
;;;;
;;;; There is still no interpreter, so printing the tree is how we see that
;;;; precedence and associativity came out right. Chapter 7 replaces the
;;;; printing step with evaluation.
;;;;
;;;; The book deletes the token-printing loop outright. We keep it behind a
;;;; `--tokens` flag instead: chapter 4 is a graded submission of its own, and
;;;; its example script should stay runnable.
;;;; =============================================================================
(ns lox.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [lox.ast-printer :as printer]
            [lox.errors :as err]
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

(defn run
  "Book: `Lox.run`.

  Chapter 6 version: scan, parse, and print the syntax tree.

  The `hadError` check between parsing and printing is the book's, and it is
  load-bearing: `parse` returns nil on a syntax error, and passing nil to the
  AST printer would replace the useful message the parser already reported with
  a meaningless one from the printer. Checking the flag rather than the nil is
  also what generalises - from chapter 8 the parser returns a partial list of
  statements after an error, which is non-nil but still must not be run."
  [source]
  (let [tokens     (scanner/scan-tokens source)
        expression (parser/parse tokens)]
    (when-not @err/had-error
      (println (printer/print-ast expression)))))

(defn run-file
  "Book: `Lox.runFile`. Execute a script; returns the process exit status.

  A static error means we never try to run the code and we exit with 65."
  ([path] (run-file path run))
  ([path run-fn]
   (err/reset-errors!)
   (run-fn (slurp (io/file path)))
   (if @err/had-error 65 0)))

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

(defn -main
  "Book: `Lox.main`. With one argument, run that script; with none, start the
  REPL; with more, complain and exit 64.

  The optional leading `--tokens` flag selects chapter 4's token dump instead
  of chapter 6's parse-and-print. It is not in the book - the book simply
  deletes the old behaviour - but it keeps the chapter 4 submission runnable."
  [& args]
  (let [tokens-mode? (= "--tokens" (first args))
        args         (if tokens-mode? (rest args) args)
        run-fn       (if tokens-mode? print-tokens run)
        status (cond
                 (> (count args) 1) (do (println "Usage: clox [--tokens] [script]") 64)
                 (= (count args) 1) (run-file (first args) run-fn)
                 :else              (do (run-prompt run-fn) 0))]
    (flush)
    (System/exit status)))
