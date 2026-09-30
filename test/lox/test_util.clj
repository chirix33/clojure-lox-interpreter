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
