;;;; =============================================================================
;;;; Chapter 4 - Scanning  (error reporting infrastructure)
;;;;
;;;; The book puts `error()` / `report()` and the `hadError` flag on the `Lox`
;;;; class, deliberately separating *generating* an error from *reporting* it.
;;;; We do the same in a dedicated namespace so that the scanner, parser,
;;;; resolver and interpreter can all report without depending on the CLI.
;;;;
;;;; Extended in:
;;;;   Chapter 6  - `error` overload that takes a token (points at a lexeme)
;;;;   Chapter 7  - runtime errors and the `hadRuntimeError` flag
;;;;
;;;; Beyond the book we also *record* every reported error in an atom. That
;;;; costs nothing at runtime and makes error reporting directly unit-testable
;;;; instead of forcing tests to scrape stderr.
;;;; =============================================================================
(ns lox.errors)

(def had-error
  "Book: `Lox.hadError`. Set when a static (scan/parse/resolve) error occurs."
  (atom false))

(def had-runtime-error
  "Book: `Lox.hadRuntimeError` (chapter 7)."
  (atom false))

(def reported
  "Vector of every error reported since the last `reset-errors!`.
  Each entry is {:line :where :message :text :kind}. Test-only convenience."
  (atom []))

(def ^:dynamic *print-errors?*
  "Bind to false to silence stderr output (used by the unit tests)."
  true)

(defn reset-errors!
  "Clear all error state. Called before each run/REPL line and by tests."
  []
  (reset! had-error false)
  (reset! had-runtime-error false)
  (reset! reported [])
  nil)

(defn- emit!
  [entry]
  (swap! reported conj entry)
  (when *print-errors?*
    (binding [*out* *err*]
      (println (:text entry))
      (flush)))
  nil)

(defn report
  "Book: `Lox.report`. Prints \"[line N] Error<where>: <message>\"."
  [line where message]
  (emit! {:kind :static
          :line line
          :where where
          :message message
          :text (str "[line " line "] Error" where ": " message)})
  (reset! had-error true)
  nil)

(defn error
  "Book: `Lox.error`, overloaded on line-number (ch. 4) and token (ch. 6).

  With a token we point at the offending lexeme; at EOF we say so explicitly
  because there is no lexeme text to show."
  [line-or-token message]
  (if (map? line-or-token)
    (let [{:keys [type lexeme line]} line-or-token]
      (if (= type :eof)
        (report line " at end" message)
        (report line (str " at '" lexeme "'") message)))
    (report line-or-token "" message)))

;;; ---------------------------------------------------------------------------
;;; Chapter 7 - runtime errors
;;; ---------------------------------------------------------------------------

(defn runtime-error-ex
  "Build (but do not throw) a Lox runtime error. Book: `RuntimeError`, which
  carries the token whose evaluation failed so we can report its line."
  [token message]
  (ex-info message {:lox/runtime-error true :token token :message message}))

(defn throw-runtime-error!
  "Throw a Lox runtime error. Used pervasively by the interpreter."
  [token message]
  (throw (runtime-error-ex token message)))

(defn runtime-error?
  "True if `e` is a Lox runtime error (as opposed to a host exception)."
  [e]
  (boolean (and (instance? clojure.lang.ExceptionInfo e)
                (:lox/runtime-error (ex-data e)))))

(defn report-runtime-error
  "Book: `Lox.runtimeError`. Prints the message then the line, and sets the
  runtime-error flag so `runFile` can exit with status 70."
  [e]
  (let [{:keys [token message]} (ex-data e)]
    (emit! {:kind :runtime
            :line (:line token)
            :where ""
            :message message
            :text (str message "\n[line " (:line token) "]")}))
  (reset! had-runtime-error true)
  nil)

(defn error-messages
  "All error message strings reported so far - handy in tests."
  []
  (mapv :message @reported))

(defn error-texts
  "All fully-formatted error lines reported so far - handy in tests."
  []
  (mapv :text @reported))
