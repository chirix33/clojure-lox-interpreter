;;;; =============================================================================
;;;; Shared test helpers (all chapters)
;;;;
;;;; Every chapter's tests need the same few things:
;;;;   * a clean, silent error reporter around each test        (quiet-errors)
;;;;   * scan / parse / run helpers that return data, not stdout
;;;;   * a way to capture what a Lox program printed             (run-capturing)
;;;;
;;;; Keeping them here means each chapter's test file is about that chapter.
;;;;
;;;; UPDATED IN CHAPTER 8: a program is now a list of statements whose only
;;;; observable behaviour is what it printed, so the chapter 8 helpers at the
;;;; bottom return *printed lines* where the chapter 7 ones return values.
;;;; =============================================================================
(ns lox.test-util
  (:require [clojure.string :as str]
            [lox.ast-printer :as printer]
            [lox.errors :as err]
            [lox.interpreter :as interp]
            [lox.parser :as parser]
            [lox.scanner :as scanner]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn quiet-errors
  "Fixture: reset Lox error state before each test and keep stderr clean.

  Tests assert on `err/error-texts` instead of scraping stderr."
  [f]
  (err/reset-errors!)
  (binding [err/*print-errors?* false]
    (f))
  (err/reset-errors!))

(defn fresh-globals
  "Fixture (chapter 8): run each test against an empty global environment.

  The interpreter's global scope has process lifetime on purpose - that is
  what makes REPL globals persist from line to line - so without this a
  `var a` defined by one test would still be defined in the next, and tests
  would pass or fail depending on the order they happened to run in."
  [f]
  (interp/reset-globals!)
  (f)
  (interp/reset-globals!))

;;; ---------------------------------------------------------------------------
;;; Phase helpers
;;; ---------------------------------------------------------------------------

(defn scan
  "Scan `src` into tokens (errors land in `lox.errors`)."
  [src]
  (scanner/scan-tokens src))

;;; Chapter 6 --------------------------------------------------------------

(defn parse
  "Scan and parse `src` as a *single expression*, returning the expression
  node (or nil on error).

  Chapter 6's entry point. From chapter 8 `parser/parse` itself returns a list
  of statements, so this calls `parse-one-expression` by name."
  [src]
  (parser/parse-one-expression (scanner/scan-tokens src)))

(defn parse-str
  "Scan, parse and pretty-print `src`. The compact way to assert on the *shape*
  of a tree: `(is (= \"(+ 1.0 2.0)\" (parse-str \"1 + 2\")))` says everything
  about precedence and associativity that a nested map comparison would, and
  says it in one readable line."
  [src]
  (let [expr (parse src)]
    (when expr (printer/print-ast expr))))

;;; Chapter 7 --------------------------------------------------------------

(defn evaluate
  "Scan, parse and evaluate `src`, returning the resulting **Lox value**.

  Runtime errors propagate as exceptions, which is what a test wanting to
  assert on the thrown error should use. Use `run` for the reporting path."
  [src]
  (interp/evaluate (parse src)))

(defn run
  "Scan, parse and evaluate `src` the way `lox.core/run` did in chapter 7: a
  runtime error is *reported* (into `lox.errors`) rather than thrown, and the
  result is nil.

  Returns nil without evaluating if `src` had a static error, mirroring the
  `hadError` guard in `lox.core/run`."
  [src]
  (let [expr (parse src)]
    (when-not @err/had-error
      (interp/try-evaluate expr))))

(defn eval-str
  "Scan, parse, evaluate and `stringify` `src` - the value as the *user* would
  see it printed.

  This is the compact way to assert on chapter 7 behaviour end to end:
  `(is (= \"7\" (eval-str \"1 + 2 * 3\")))` states the arithmetic, the
  precedence and the `.0`-trimming of `stringify` in one readable line, the
  way `parse-str` does for tree shape in chapter 6."
  [src]
  (interp/stringify (evaluate src)))

(defn run-printing
  "Scan, parse and evaluate `src` through chapter 7's `interpret`, which
  *prints* the stringified result the way the book's chapter 7 `interpret` did.

  Pair it with `capture-out` to assert on what the user would have seen."
  [src]
  (let [expr (parse src)]
    (when-not @err/had-error
      (interp/interpret-expression expr))))

(defn runtime-error-message-of-node
  "Evaluate an already-built expression `node` from a clean error log and
  return the first reported error message.

  Needed for the handful of trees that the parser cannot produce - an
  unreachable operator, say - which are therefore constructed by hand."
  [node]
  (err/reset-errors!)
  (interp/try-evaluate node)
  (first (err/error-messages)))

(defn runtime-error-of
  "Evaluate `src` from a clean error log and return the first reported error
  line, or nil if it ran without error.

  Resets first for the same reason `parse-error-of` does in the chapter 6
  tests: the `quiet-errors` fixture runs once per `deftest`, so an `are` block
  of several sources would otherwise keep seeing the first one's error."
  [src]
  (err/reset-errors!)
  (run src)
  (first (err/error-texts)))

(defn runtime-error-message-of
  "Like `runtime-error-of`, but just the message, with no line suffix."
  [src]
  (err/reset-errors!)
  (run src)
  (first (err/error-messages)))

(defn errors
  "The formatted error lines reported so far."
  []
  (err/error-texts))

(defn error-messages
  "The bare error messages reported so far."
  []
  (err/error-messages))

(defn first-error
  "The first formatted error line, or nil."
  []
  (first (err/error-texts)))

(defn had-error? [] @err/had-error)
(defn had-runtime-error? [] @err/had-runtime-error)

;;; ---------------------------------------------------------------------------
;;; Output capture
;;; ---------------------------------------------------------------------------

(defn capture-out
  "Run `f`, returning [result printed-lines]."
  [f]
  (let [sw (java.io.StringWriter.)
        result (binding [*out* sw] (f))]
    [result (let [s (str sw)]
              (if (str/blank? s)
                []
                (str/split-lines (str/replace s "\r\n" "\n"))))]))

(defn capture-err
  "Run `f` with `lox.errors` printing *enabled* and stderr captured,
  returning [result printed-error-lines].

  The `quiet-errors` fixture silences the reporter so that tests can assert on
  recorded error data instead of scraping text. That is the right default, but
  it also means a test cannot normally see what a user would actually have
  read - and \"the error state was rolled back\" and \"nothing was printed\" are
  different claims. This helper is for the cases where the second one is the
  one under test."
  [f]
  (let [sw (java.io.StringWriter.)
        result (binding [*err* sw err/*print-errors?* true] (f))]
    [result (let [s (str sw)]
              (if (str/blank? s)
                []
                (str/split-lines (str/replace s "\r\n" "\n"))))]))

(defn lines-of
  "Split captured output text into lines."
  [s]
  (if (str/blank? s) [] (str/split-lines s)))

;;; ---------------------------------------------------------------------------
;;; Chapter 8 - whole-program helpers
;;;
;;; These live after `capture-out` because they use it. From chapter 8 a Lox
;;; program's observable behaviour *is* the lines it printed, so most of this
;;; chapter's assertions are of the form
;;;
;;;     (is (= ["3"] (run-program "print 1 + 2;")))
;;;
;;; which states the parse, the evaluation, the statement semantics and the
;;; `stringify` rules together, in the only terms a Lox user can see.
;;; ---------------------------------------------------------------------------

(defn parse-program
  "Scan and parse `src` as a whole program, returning a vector of statements."
  [src]
  (parser/parse-program src))

(defn program-str
  "Scan, parse and pretty-print `src` as a program: one statement per line.

  The chapter 8 counterpart of `parse-str`. One string says everything about
  which statements were produced and how their expressions nested, in a form a
  human can check against the source by eye."
  [src]
  (printer/print-program (parse-program src)))

(defn interpret-program
  "Parse and run `src` the way `lox.core/run` does: nothing runs if there was
  a static error, and a runtime error is reported rather than thrown.

  Returns true if the program ran to completion, false otherwise."
  [src]
  (let [statements (parse-program src)]
    (if @err/had-error
      false
      (interp/interpret statements))))

(defn run-program
  "Parse and run `src`, returning the vector of lines it printed."
  [src]
  (second (capture-out #(interpret-program src))))

(defn run-fresh
  "`run-program`, from a clean error log and an empty global scope.

  Needed for the same reason `runtime-error-of` resets: the `quiet-errors` and
  `fresh-globals` fixtures run once per `deftest`, so an `are` block of
  several programs would otherwise inherit the previous one's globals and
  errors."
  [src]
  (err/reset-errors!)
  (interp/reset-globals!)
  (run-program src))

(defn program-error
  "Run `src` from a clean slate and return the first reported error line.

  Covers both failure modes on purpose: a syntax error and a runtime error are
  both \"the program did not do what it says\", and a test for one should fail
  loudly if the other happens instead."
  [src]
  (err/reset-errors!)
  (interp/reset-globals!)
  (capture-out #(interpret-program src))
  (first (err/error-texts)))

(defn program-error-message
  "Like `program-error`, but just the message, with no line suffix."
  [src]
  (err/reset-errors!)
  (interp/reset-globals!)
  (capture-out #(interpret-program src))
  (first (err/error-messages)))

(defn program-error-messages
  "Every error message reported while running `src`, from a clean slate.

  Chapter 8 is the first chapter in which one source file can report
  *several* syntax errors, because `parse-declaration` synchronizes and
  carries on."
  [src]
  (err/reset-errors!)
  (interp/reset-globals!)
  (capture-out #(interpret-program src))
  (err/error-messages))

(defn run-program-and-errors
  "Run `src` from a clean slate, returning [printed-lines error-messages]."
  [src]
  (err/reset-errors!)
  (interp/reset-globals!)
  (let [[_ lines] (capture-out #(interpret-program src))]
    [lines (err/error-messages)]))
