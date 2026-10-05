;;;; =============================================================================
;;;; Shared test helpers (all chapters)
;;;;
;;;; Every chapter's tests need the same few things:
;;;;   * a clean, silent error reporter around each test        (quiet-errors)
;;;;   * scan / parse / run helpers that return data, not stdout
;;;;   * a way to capture what a Lox program printed             (run-capturing)
;;;;
;;;; Keeping them here means each chapter's test file is about that chapter.
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

;;; ---------------------------------------------------------------------------
;;; Phase helpers
;;; ---------------------------------------------------------------------------

(defn scan
  "Scan `src` into tokens (errors land in `lox.errors`)."
  [src]
  (scanner/scan-tokens src))

;;; Chapter 6 --------------------------------------------------------------

(defn parse
  "Scan and parse `src`, returning the expression node (or nil on error)."
  [src]
  (parser/parse (scanner/scan-tokens src)))

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
  "Scan, parse and evaluate `src` the way `lox.core/run` does: a runtime error
  is *reported* (into `lox.errors`) rather than thrown, and the result is nil.

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
  "Scan, parse and evaluate `src` through `lox.interpreter/interpret`, which
  *prints* the stringified result the way the book's `interpret` does.

  Pair it with `capture-out` to assert on what the user would have seen."
  [src]
  (let [expr (parse src)]
    (when-not @err/had-error
      (interp/interpret expr))))

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

(defn lines-of
  "Split captured output text into lines."
  [s]
  (if (str/blank? s) [] (str/split-lines s)))
