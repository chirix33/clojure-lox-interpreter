;;;; =============================================================================
;;;; Chapter 7 - Evaluating Expressions : unit tests
;;;;
;;;; The chapter makes five claims, and the sections below test them in order:
;;;;
;;;;   1. a Lox value is one of exactly four host types, and the scanner and
;;;;      parser already produce them (section 7.1)
;;;;   2. every expression node evaluates to the right value, by a post-order
;;;;      left-to-right traversal (section 7.2)
;;;;   3. truthiness, equality and `stringify` implement *Lox's* rules, which
;;;;      are not always the host language's (7.2.4, 7.2.5, 7.4)
;;;;   4. a type error is reported as a Lox runtime error against the right
;;;;      token, unwinds the whole expression, and does not kill the
;;;;      interpreter (section 7.3)
;;;;   5. the three end-of-chapter challenges work, and are inert while their
;;;;      switch is off
;;;;
;;;; Most assertions go through `u/eval-str`, which evaluates and then
;;;; `stringify`s, so a test reads as the user's own experience of the
;;;; language: `(is (= "7" (u/eval-str "1 + 2 * 3")))`. Where the *host*
;;;; representation is the point - that a Lox number is a Double and never a
;;;; Long - `u/evaluate` is used instead and the raw value is checked.
;;;;
;;;; Two things in this chapter are hard to observe from the outside, and get
;;;; purpose-built harnesses here rather than being asserted by eye:
;;;;
;;;;   * evaluation *order*. Lox has no functions until chapter 10, so the
;;;;     book's `say("left") - say("right")` demonstration cannot be written
;;;;     as Lox source yet. `eval-trace` instead records every node as it
;;;;     finishes evaluating, which shows post-order and left-to-right
;;;;     directly. A second, independent check uses runtime errors on
;;;;     different lines as observable side effects.
;;;;   * exit codes. `exit-status-of` writes a temp file and runs the real
;;;;     `lox.core/run-file` over it, so 0 / 65 / 70 are tested through the
;;;;     actual entry point rather than by re-reading the flags.
;;;; =============================================================================
(ns lox.chapter-07-evaluating-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [lox.ast :as ast]
            [lox.core :as core]
            [lox.errors :as err]
            [lox.interpreter :as interp]
            [lox.parser :as parser]
            [lox.scanner :as scanner]
            [lox.token :as tok]
            [lox.test-util :as u]))

(use-fixtures :each u/quiet-errors)

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn lit
  "A literal node holding `v` - the parser's output for a literal token."
  [v]
  (ast/literal v))

(defn op
  "A operator token of `type`, spelled `lexeme`, on `line` (default 1)."
  ([type lexeme] (op type lexeme 1))
  ([type lexeme line] (tok/make-token type lexeme nil line)))

(defn eval-trace
  "Evaluate `src`, returning `[value trace]`, where `trace` names every node
  in the order it *finished* evaluating.

  Implemented by redefining `lox.interpreter/evaluate` around the real one.
  That works - and is worth a note - because the visitor is a map of plain
  functions whose bodies call the `evaluate` *var*: the recursive calls inside
  `:binary` and friends go back through the var, so the wrapper sees the whole
  traversal and not just its root. The book's Java interpreter is wrapped the
  same way in spirit, by subclassing and overriding `evaluate`.

  A literal is traced as its value, so a trace reads as the order operands
  were computed; every other node is traced as its node type."
  [src]
  (let [trace    (atom [])
        original interp/evaluate
        expr     (u/parse src)]
    (with-redefs [interp/evaluate
                  (fn [e]
                    (let [v (original e)]
                      (swap! trace conj (if (ast/literal? e)
                                          (:value e)
                                          (ast/node-type e)))
                      v))]
      [(interp/evaluate expr) @trace])))

(defn exit-status-of
  "Write `src` to a temp file, run it through the real `lox.core/run-file`,
  and return `[exit-status printed-lines]`.

  This exercises the chapter's exit-code contract (0 / 65 / 70) through the
  actual entry point. `run-file` resets the error state itself, so no fixture
  interference."
  [src]
  (let [file (doto (java.io.File/createTempFile "lox-ch07-" ".lox")
               (.deleteOnExit))]
    (spit file src)
    (try
      (let [[status out] (u/capture-out #(core/run-file (.getPath file)))]
        [status out])
      (finally (.delete file)))))

(defn repl-session
  "Feed `lines` to `lox.core/run-prompt` as if typed, and return everything it
  printed, with the `> ` prompts stripped.

  The REPL is the reason a runtime error must not be fatal, so it needs a test
  of its own rather than a reading of the code."
  [lines]
  (let [input (str (str/join "\n" lines) "\n")
        [_ out] (u/capture-out
                 #(with-in-str input (core/run-prompt)))]
    (->> out
         (map #(str/replace % #"^(> )+" ""))
         (remove str/blank?)
         vec)))

;;; ===========================================================================
;;; 1. Section 7.1 - Representing Values
;;; ===========================================================================

(deftest lox-value-types
  (testing "the four types of section 7.1's table are recognised"
    (are [v expected] (= expected (interp/type-name v))
      nil      "nil"
      true     "Boolean"
      false    "Boolean"
      1.0      "number"
      -0.5     "number"
      ##Inf    "number"
      ##NaN    "number"
      ""       "string"
      "muffin" "string"))

  (testing "and only those four are Lox values"
    (are [v] (interp/lox-value? v)
      nil true false 0.0 1.0 ##NaN "" "a")
    (are [v] (not (interp/lox-value? v))
      1 1N 1M 1/2 :kw 'sym [] {} (float 1.0))))

(deftest lox-number-is-double-only
  (testing "a Lox number is a java.lang.Double, as the book's table says"
    (is (interp/lox-number? 1.0))
    (is (instance? Double (u/evaluate "1")))
    (is (instance? Double (u/evaluate "1 + 1"))))

  (testing "and a Clojure integer is NOT one - it is a Long"
    (is (not (interp/lox-number? 1)))
    (is (not (interp/lox-number? (int 1))))
    (is (not (interp/lox-number? (float 1.0))))
    (is (not (interp/lox-number? 1M)))))

(deftest scanner-and-parser-already-produce-lox-values
  (testing "the value is produced during scanning and carried through, so a
            literal needs no conversion at evaluation time (section 7.2.1)"
    (are [src value] (= value (:literal (first (scanner/scan-tokens src))))
      "123"   123.0
      "1.5"   1.5
      "\"hi\"" "hi")
    (testing "evaluating a literal yields the very object the scanner made:
              same value, same host class, no conversion step in between"
      (are [src] (let [scanned (:literal (first (scanner/scan-tokens src)))
                       evaluated (u/evaluate src)]
                   (and (= scanned evaluated)
                        (= (class scanned) (class evaluated))))
        "\"hi\"" "123" "1.5")
      (testing "and within one tree it is literally the same object"
        (let [token (first (scanner/scan-tokens "\"hi\""))]
          (is (identical? (:literal token)
                          (interp/evaluate (ast/literal (:literal token))))))))))

;;; ===========================================================================
;;; 2. Section 7.2 - Evaluating Expressions, node by node
;;; ===========================================================================

;;; 7.2.1 Literals -----------------------------------------------------------

(deftest evaluating-literals
  (testing "a literal evaluates to the value the parser stored in it"
    (are [src expected] (= expected (u/evaluate src))
      "1"        1.0
      "123.456"  123.456
      "\"str\""  "str"
      "\"\""     ""
      "true"     true
      "false"    false
      "nil"      nil))

  (testing "directly, with no scanner or parser involved"
    (are [v] (= v (interp/evaluate (lit v)))
      nil true false 0.0 1.0 "x")))

;;; 7.2.2 Grouping -----------------------------------------------------------

(deftest evaluating-grouping
  (testing "a grouping evaluates to its inner expression"
    (are [src expected] (= expected (u/eval-str src))
      "(1)"          "1"
      "((((1))))"    "1"
      "(\"s\")"      "s"
      "(nil)"        "nil"))

  (testing "and parentheses change the result by changing the tree"
    (is (= "9"  (u/eval-str "(1 + 2) * 3")))
    (is (= "7"  (u/eval-str "1 + 2 * 3")))
    (is (= "-1" (u/eval-str "1 - (3 - 1)")))
    (is (= "-1" (u/eval-str "(1 - 3) + 1")))))

;;; 7.2.3 Unary --------------------------------------------------------------

(deftest evaluating-unary-minus
  (are [src expected] (= expected (u/eval-str src))
    "-1"      "-1"
    "-1.5"    "-1.5"
    "--1"     "1"
    "---1"    "-1"
    "-0"      "-0"
    "-(2+3)"  "-5")

  (testing "negation yields a number, not a string or a Long"
    (is (instance? Double (u/evaluate "-1")))))

(deftest evaluating-unary-not
  (testing "! negates truthiness, and always yields a Boolean"
    (are [src expected] (= expected (u/eval-str src))
      "!true"   "false"
      "!false"  "true"
      "!nil"    "true"
      "!!nil"   "false"
      "!0"      "false"
      "!1"      "false"
      "!\"\""   "false"
      "!!!true" "false")
    (is (instance? Boolean (u/evaluate "!1")))))

;;; 7.2.4 Truthiness ---------------------------------------------------------

(deftest truthiness-follows-ruby
  (testing "only false and nil are falsey; EVERYTHING else is truthy"
    (are [v] (true? (interp/truthy? v))
      true 0.0 -0.0 1.0 ##NaN ##Inf "" "0" "false" "nil")
    (are [v] (false? (interp/truthy? v))
      false nil))

  (testing "which is NOT the rule in several well-known languages"
    (testing "0 is falsey in C and Python, truthy in Lox"
      (is (= "false" (u/eval-str "!0"))))
    (testing "the empty string is falsey in JS and Python, truthy in Lox"
      (is (= "false" (u/eval-str "!\"\""))))
    (testing "the string \"0\" is falsey in PHP, truthy in Lox"
      (is (= "false" (u/eval-str "!\"0\"")))))

  (testing "falsey? is exactly the complement"
    (are [v] (= (interp/falsey? v) (not (interp/truthy? v)))
      nil true false 0.0 1.0 "" "x" ##NaN)))

;;; 7.2.5 Arithmetic ---------------------------------------------------------

(deftest evaluating-arithmetic
  (are [src expected] (= expected (u/eval-str src))
    "1 + 2"          "3"
    "5 - 3"          "2"
    "3 - 5"          "-2"
    "4 * 5"          "20"
    "10 / 4"         "2.5"
    "10 / 5"         "2"
    "2 * 3 + 4"      "10"
    "2 + 3 * 4"      "14"
    "1 + 2 + 3 + 4"  "10"
    "20 / 2 / 5"     "2"
    "10 - 5 - 2"     "3"
    "-2 * -3"        "6")

  (testing "numbers are doubles, so division is not integer division"
    (is (= "0.5" (u/eval-str "1 / 2")))
    (is (= "3.3333333333333335" (u/eval-str "10 / 3"))))

  (testing "and arithmetic carries IEEE 754 rounding, warts and all"
    (is (= "0.30000000000000004" (u/eval-str "0.1 + 0.2")))))

(deftest evaluating-string-concatenation
  (testing "+ is overloaded for two strings (section 7.2.5)"
    (are [src expected] (= expected (u/eval-str src))
      "\"a\" + \"b\""           "ab"
      "\"foo\" + \"\""          "foo"
      "\"a\" + \"b\" + \"c\""   "abc"
      "\"1\" + \"2\""           "12"))

  (testing "concatenation of numeric-looking strings does not add them"
    (is (= "12" (u/eval-str "\"1\" + \"2\"")))
    (is (instance? String (u/evaluate "\"1\" + \"2\"")))))

(deftest division-by-zero-is-ieee-by-default
  (testing "Lox numbers are doubles, so a zero divisor gives an infinity or
            a NaN rather than an error (the book's behaviour; challenge 7.3
            changes it, and is tested in section 5)"
    (are [src expected] (= expected (u/eval-str src))
      "1 / 0"    "Infinity"
      "-1 / 0"   "-Infinity"
      "1 / -0"   "-Infinity"
      "0 / 0"    "NaN")
    (is (not (u/had-runtime-error?))))

  (testing "this is the Clojure-specific trap the interpreter casts around:
            dividing two *boxed* Doubles throws ArithmeticException, so the
            interpreter must cast to primitive doubles to match the book"
    (is (thrown? ArithmeticException (/ (Double/valueOf 1.0) (Double/valueOf 0.0))))
    (is (= Double/POSITIVE_INFINITY (u/evaluate "1 / 0")))))

;;; 7.2.5 Comparison ---------------------------------------------------------

(deftest evaluating-comparisons
  (are [src expected] (= expected (u/eval-str src))
    "1 < 2"    "true"
    "2 < 1"    "false"
    "1 < 1"    "false"
    "1 <= 1"   "true"
    "2 <= 1"   "false"
    "2 > 1"    "true"
    "1 > 2"    "false"
    "1 >= 1"   "true"
    "1 >= 2"   "false"
    "-1 < 0"   "true")

  (testing "a comparison always yields a Boolean, unlike arithmetic whose
            result type follows its operands"
    (is (instance? Boolean (u/evaluate "1 < 2")))
    (is (instance? Double  (u/evaluate "1 + 2")))))

(deftest comparisons-use-ieee-ordering-for-nan
  (testing "every comparison involving NaN is false - including NaN <= NaN -
            because the book compares primitive doubles. Clojure's `compare`
            would order NaN above everything and get `NaN > 1` wrong."
    (are [src] (= "false" (u/eval-str src))
      "(0/0) < 1"      "(0/0) > 1"
      "(0/0) <= 1"     "(0/0) >= 1"
      "1 < (0/0)"      "1 > (0/0)"
      "(0/0) < (0/0)"  "(0/0) > (0/0)"
      "(0/0) <= (0/0)" "(0/0) >= (0/0)"))

  (testing "and -0.0 compares equal to 0.0, as primitive doubles do"
    (is (= "false" (u/eval-str "-0 < 0")))
    (is (= "true"  (u/eval-str "-0 <= 0")))
    (is (= "true"  (u/eval-str "-0 >= 0")))))

;;; 7.2.5 Equality -----------------------------------------------------------

(deftest evaluating-equality
  (testing "like types"
    (are [src expected] (= expected (u/eval-str src))
      "1 == 1"              "true"
      "1 == 2"              "false"
      "1 != 2"              "true"
      "1 != 1"              "false"
      "\"a\" == \"a\""      "true"
      "\"a\" == \"b\""      "false"
      "true == true"        "true"
      "true == false"       "false"
      "nil == nil"          "true"
      "nil != nil"          "false"))

  (testing "mixed types are never equal - Lox does no implicit conversion,
            and unlike the comparisons this is not an error (section 7.2.5)"
    (are [src expected] (= expected (u/eval-str src))
      "1 == \"1\""   "false"
      "1 != \"1\""   "true"
      "true == 1"    "false"
      "false == 0"   "false"
      "nil == false" "false"
      "nil == 0"     "false"
      "nil == \"\""  "false"
      "\"true\" == true" "false")
    (is (not (u/had-runtime-error?))))

  (testing "equality accepts operands of ANY pair of types without error,
            which is what distinguishes it from < and friends"
    (doseq [src ["1 == nil" "\"a\" == true" "nil == 1" "true == \"x\""]]
      (err/reset-errors!)
      (u/run src)
      (is (not (u/had-runtime-error?)) src))))

(deftest equality-uses-equals-not-numeric-equivalence
  (testing "the book's `isEqual` calls .equals, so Lox inherits
            Double.equals - which disagrees with IEEE 754 in two places. The
            book flags this itself: \"Lox uses the latter, so doesn't follow
            IEEE.\" We match the book deliberately."
    (testing "NaN equals itself, though IEEE says it should not"
      (is (= "true" (u/eval-str "(0/0) == (0/0)")))
      (is (true? (interp/lox-equal? ##NaN ##NaN))))

    (testing "and -0.0 does NOT equal 0.0, though IEEE says it should"
      (is (= "false" (u/eval-str "-0 == 0")))
      (is (false? (interp/lox-equal? -0.0 0.0)))))

  (testing "which is the opposite of Clojure's own = on both counts, so the
            implementation cannot simply delegate to ="
    (is (not= (interp/lox-equal? ##NaN ##NaN) (= ##NaN ##NaN)))
    (is (not= (interp/lox-equal? -0.0 0.0)    (= -0.0 0.0)))))

(deftest lox-equal-nil-handling
  (testing "two nils are equal; nil equals nothing else; and no
            NullPointerException escapes either way"
    (is (true? (interp/lox-equal? nil nil)))
    (are [a b] (false? (interp/lox-equal? a b))
      nil 1.0,  1.0 nil,  nil "",  "" nil,  nil false,  false nil))

  (testing "equality is symmetric and reflexive over every Lox value"
    (let [values [nil true false 0.0 1.0 -0.0 ##Inf "" "a"]]
      (doseq [a values]
        (is (true? (interp/lox-equal? a a)) (pr-str a))
        (doseq [b values]
          (is (= (interp/lox-equal? a b) (interp/lox-equal? b a))
              (pr-str [a b])))))))

;;; Evaluation order ---------------------------------------------------------

(deftest evaluation-is-post-order-and-left-to-right
  (testing "each node evaluates its children before doing its own work"
    (let [[value trace] (eval-trace "1 + 2")]
      (is (= 3.0 value))
      (is (= [1.0 2.0 :binary] trace))))

  (testing "left to right, at every level"
    (is (= [1.0 2.0 :binary 3.0 :binary]
           (second (eval-trace "1 + 2 + 3"))))
    (is (= [1.0 2.0 3.0 :binary :binary]
           (second (eval-trace "1 + 2 * 3"))))
    (is (= [1.0 2.0 :binary :grouping 3.0 :binary]
           (second (eval-trace "(1 + 2) * 3")))))

  (testing "a unary evaluates its operand first"
    (is (= [1.0 :unary] (second (eval-trace "-1")))))

  (testing "the book's choice is user-visible, so it is a language decision
            and not an implementation detail: a runtime error in the LEFT
            operand is the one reported, even though the right would also
            fail. The two errors are put on different lines so the report
            distinguishes them."
    (is (= "Operand must be a number.\n[line 1]"
           (u/runtime-error-of "-\"a\"\n+\n-\"b\"")))
    (is (= 1 (count (u/errors)))
        "evaluation stops at the first runtime error")))

(deftest both-operands-evaluate-before-either-is-checked
  (testing "the book: \"We evaluate both operands before checking the type of
            either.\" So the inner error surfaces, not the outer one."
    (is (= "Operand must be a number."
           (u/runtime-error-message-of "1 - -\"muffin\"")))
    (is (= "Operands must be two numbers or two strings."
           (u/runtime-error-message-of "1 - (nil + nil)"))))

  (testing "and with both operands well-typed-but-wrong, the OUTER operator
            is the one that reports"
    (is (= "Operands must be numbers."
           (u/runtime-error-message-of "\"a\" - \"b\"")))))

;;; ===========================================================================
;;; 3. Section 7.4 - stringify
;;; ===========================================================================

(deftest stringify-values
  (testing "nil prints as Lox's `nil`, not Java's `null` nor Clojure's \"\""
    (is (= "nil" (interp/stringify nil))))

  (testing "integer-valued doubles print with no trailing .0 (section 7.4) -
            the hack the book keeps so that jlox and clox agree"
    (are [v expected] (= expected (interp/stringify v))
      0.0     "0"
      1.0     "1"
      -1.0    "-1"
      123.0   "123"
      -0.0    "-0"
      1.0E10  "1.0E10"))

  (testing "non-integer doubles keep their fraction"
    (are [v expected] (= expected (interp/stringify v))
      1.5     "1.5"
      -0.25   "-0.25"
      0.1     "0.1"))

  (testing "and the IEEE specials print as Java names them"
    (are [v expected] (= expected (interp/stringify v))
      ##Inf  "Infinity"
      ##-Inf "-Infinity"
      ##NaN  "NaN"))

  (testing "booleans and strings print as themselves, with no quotes added"
    (are [v expected] (= expected (interp/stringify v))
      true   "true"
      false  "false"
      "a"    "a"
      ""     ""
      "1"    "1"))

  (testing "so a string and a number can print identically while being
            distinct, non-equal Lox values"
    (is (= (u/eval-str "1") (u/eval-str "\"1\"")))
    (is (= "false" (u/eval-str "1 == \"1\"")))))

(deftest stringify-differs-from-the-ast-printers-literal-rendering
  (testing "chapter 5's printer shows the tree (1.0), chapter 7's stringify
            shows the value (1) - deliberately different jobs"
    (is (= "1.0" (u/parse-str "1")))
    (is (= "1"   (u/eval-str "1")))
    (is (= "(+ 1.0 2.0)" (u/parse-str "1 + 2")))
    (is (= "3"           (u/eval-str "1 + 2")))))

;;; ===========================================================================
;;; 4. Section 7.3 - Runtime Errors
;;; ===========================================================================

(deftest runtime-error-messages-match-the-book
  (testing "unary - on a non-number"
    (are [src] (= "Operand must be a number." (u/runtime-error-message-of src))
      "-\"muffin\"" "-nil" "-true" "-false" "-\"\""))

  (testing "binary arithmetic and comparison on non-numbers"
    (are [src] (= "Operands must be numbers." (u/runtime-error-message-of src))
      "1 - \"a\""  "\"a\" - 1"   "nil - nil"   "true * 2"
      "1 / nil"    "\"a\" * \"b\""
      "1 < \"a\""  "\"a\" > 1"   "nil <= 1"    "true >= false"
      "\"a\" < \"b\""))

  (testing "+ gets its own message, since it has two valid type pairs"
    (are [src] (= "Operands must be two numbers or two strings."
                  (u/runtime-error-message-of src))
      "1 + \"a\""  "\"a\" + 1"  "1 + nil"  "nil + nil"
      "true + 1"   "\"a\" + nil")))

(deftest runtime-errors-report-the-operator-token-and-line
  (testing "the error names the line of the operator that failed, which is
            why RuntimeError carries a token at all"
    (is (= "Operand must be a number.\n[line 1]"
           (u/runtime-error-of "-\"a\"")))
    (is (= "Operand must be a number.\n[line 3]"
           (u/runtime-error-of "1 +\n2 +\n-\"a\"")))
    (is (= "Operands must be numbers.\n[line 2]"
           (u/runtime-error-of "1\n- \"a\""))))

  (testing "the recorded error is tagged :runtime, not :static - these are a
            different kind of failure from a scan or parse error"
    (err/reset-errors!)
    (u/run "-\"a\"")
    (is (= [:runtime] (mapv :kind @err/reported)))
    (is (u/had-runtime-error?))
    (is (not (u/had-error?))))

  (testing "while a parse error is :static and never reaches the interpreter"
    (err/reset-errors!)
    (u/run "1 +")
    (is (= [:static] (mapv :kind @err/reported)))
    (is (u/had-error?))
    (is (not (u/had-runtime-error?)))))

(deftest runtime-errors-unwind-the-whole-expression
  (testing "the book's example: you cannot negate a muffin, so the inner -
            fails, and the / and the * it is nested in must not run"
    (is (= "Operand must be a number."
           (u/runtime-error-message-of "2 * (3 / -\"muffin\")")))
    (is (= 1 (count (u/errors))) "exactly one error, from the innermost failure")
    (is (nil? (u/run "2 * (3 / -\"muffin\")"))))

  (testing "however deeply nested the failure is"
    (let [src (str (apply str (repeat 40 "(1 + ")) "-nil" (apply str (repeat 40 ")")))]
      (is (= "Operand must be a number." (u/runtime-error-message-of src)))))

  (testing "a runtime error is a thrown, catchable, Lox-tagged exception"
    (let [e (try (u/evaluate "-nil") nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (err/runtime-error? e))
      (is (= "Operand must be a number." (ex-message e)))
      (is (= :minus (:type (:token (ex-data e)))))
      (is (= 1 (:line (:token (ex-data e)))))))

  (testing "and it is a Lox error, not a leaked host exception: no
            ClassCastException or NullPointerException escapes"
    (doseq [src ["-nil" "1 + nil" "nil < nil" "\"a\" * 2" "-true"]]
      (err/reset-errors!)
      (is (nil? (u/run src)) src)
      (is (u/had-runtime-error?) src))))

;;; ===========================================================================
;;; 5. Section 7.4 - Hooking Up the Interpreter
;;; ===========================================================================

(deftest interpret-prints-the-stringified-value
  (are [src expected] (= [expected] (second (u/capture-out #(u/run-printing src))))
    "1 + 2"           "3"
    "\"a\" + \"b\""   "ab"
    "nil"             "nil"
    "1 == 1"          "true"
    "10 / 4"          "2.5")

  (testing "and prints nothing when the expression fails at runtime"
    (let [[_ out] (u/capture-out #(u/run-printing "-nil"))]
      (is (= [] out))
      (is (u/had-runtime-error?)))))

(deftest core-run-evaluates-rather-than-printing-the-tree
  (testing "chapter 7 replaces chapter 6's tree printing in `lox.core/run`"
    (is (= ["3"] (second (u/capture-out #(core/run "1 + 2"))))))

  (testing "chapter 6's behaviour is still reachable, for its own submission"
    (is (= ["(+ 1.0 2.0)"] (second (u/capture-out #(core/print-ast "1 + 2"))))))

  (testing "as is chapter 4's"
    (let [[_ out] (u/capture-out #(core/print-tokens "1"))]
      (is (= ["NUMBER 1 1.0" "EOF  null"] out))))

  (testing "a static error stops us before evaluation - nothing is printed"
    (err/reset-errors!)
    (let [[_ out] (u/capture-out #(core/run "1 +"))]
      (is (= [] out))
      (is (u/had-error?))
      (is (not (u/had-runtime-error?))))))

(deftest exit-codes
  (testing "0 when the script runs cleanly"
    (is (= [0 ["3"]] (exit-status-of "1 + 2"))))

  (testing "65 (EX_DATAERR) for a static error - the code never ran"
    (is (= 65 (first (exit-status-of "1 +"))))
    (is (= 65 (first (exit-status-of "(1"))))
    (is (= [] (second (exit-status-of "1 +")))))

  (testing "70 (EX_SOFTWARE) for a runtime error - it ran and failed partway"
    (is (= 70 (first (exit-status-of "-\"muffin\""))))
    (is (= 70 (first (exit-status-of "2 * (3 / -\"muffin\")")))))

  (testing "a static error wins over a runtime one, since nothing ran"
    (is (= 65 (first (exit-status-of "1 + + nil"))))))

(deftest repl-survives-a-runtime-error
  (testing "the book: after an error \"we simply loop around and let them
            input new code and keep going\""
    (is (= ["3" "7"] (repl-session ["1 + 2" "-\"muffin\"" "3 + 4"]))))

  (testing "and survives a static error just the same"
    (is (= ["3" "7"] (repl-session ["1 + 2" "1 +" "3 + 4"]))))

  (testing "error state does not leak from one line to the next"
    (is (= ["2" "4" "6"] (repl-session ["1 + 1" "-nil" "2 + 2" "1 +" "3 + 3"])))
    (is (not (u/had-error?)))))

;;; ===========================================================================
;;; 6. The visitor contract
;;; ===========================================================================

(deftest interpreter-handles-every-node-type
  (testing "`defvisitor` refuses to compile an incomplete visitor, so this
            holds by construction - asserting it guards against a node type
            being added to the AST with a stub handler to quiet the macro"
    (is (= ast/expr-node-types (set (keys interp/interpreter))))
    (is (every? fn? (vals interp/interpreter))))

  (testing "and every node type actually evaluates to something sensible"
    (are [node expected] (= expected (interp/evaluate node))
      (lit 1.0)                                       1.0
      (ast/grouping (lit 1.0))                        1.0
      (ast/unary (op :minus "-") (lit 1.0))           -1.0
      (ast/binary (lit 1.0) (op :plus "+") (lit 2.0)) 3.0
      (ast/conditional (lit true) (lit 1.0) (lit 2.0)) 1.0)))

(deftest evaluate-rejects-non-nodes
  (testing "`accept` guards the boundary, so a malformed tree fails where it
            is walked rather than producing a nonsense value"
    (are [x] (thrown? IllegalArgumentException (interp/evaluate x))
      nil 1 "x" {} {:node :nonesuch} [])))

(deftest unknown-operators-are-reported-not-silently-ignored
  (testing "the book's `switch` returns null for an unhandled operator, with
            an `// Unreachable.` comment. We throw a Lox runtime error, so a
            parser bug surfaces instead of becoming a stray nil."
    (is (= "Unsupported binary operator ':'."
           (u/runtime-error-message-of-node
            (ast/binary (lit 1.0) (op :colon ":") (lit 2.0)))))
    (is (= "Unsupported unary operator '!='."
           (u/runtime-error-message-of-node
            (ast/unary (op :bang-equal "!=") (lit 1.0)))))))

(deftest deeply-nested-expressions-do-not-blow-up
  (testing "evaluation recurses once per node, so a deep tree is the natural
            stress case for a tree-walking interpreter"
    (is (= "true" (u/eval-str (str (apply str (repeat 101 "!")) "nil"))))
    (is (= "1"    (u/eval-str (str (apply str (repeat 100 "("))
                                   "1"
                                   (apply str (repeat 100 ")"))))))
    (is (= "100"  (u/eval-str (str "0" (apply str (repeat 100 " + 1"))))))))

;;; ===========================================================================
;;; 7. Challenges
;;; ===========================================================================

;;; Challenge 7.1 - comparing other types -------------------------------------

(deftest challenge-7-1-string-comparison
  (testing "off by default: comparing two strings is a runtime error, exactly
            as in the book"
    (is (= "Operands must be numbers." (u/runtime-error-message-of "\"a\" < \"b\"")))
    (is (= "Operands must be numbers." (u/runtime-error-message-of "\"a\" >= \"b\""))))

  (binding [interp/*compare-strings?* true]
    (testing "on: two strings order lexicographically, by String.compareTo"
      (are [src expected] (= expected (u/eval-str src))
        "\"a\" < \"b\""      "true"
        "\"b\" < \"a\""      "false"
        "\"a\" < \"a\""      "false"
        "\"a\" <= \"a\""     "true"
        "\"b\" > \"a\""      "true"
        "\"a\" >= \"a\""     "true"
        "\"\" < \"a\""       "true"
        "\"abc\" < \"abd\""  "true"
        "\"ab\" < \"abc\""   "true"
        "\"Z\" < \"a\""      "true"))

    (testing "numbers are unaffected"
      (is (= "true"  (u/eval-str "1 < 2")))
      (is (= "false" (u/eval-str "2 < 1"))))

    (testing "MIXED types are still an error - that is the design decision,
              and the message tracks the widened rule rather than lying"
      (are [src] (= "Operands must be two numbers or two strings."
                    (u/runtime-error-message-of src))
        "1 < \"a\""  "\"a\" < 1"  "nil < 1"  "true < false"  "\"a\" < nil"))

    (testing "and the ordering is consistent with equality: comparing equal
              strings gives <= and >= but not < or >"
      (is (= "false" (u/eval-str "\"a\" < \"a\"")))
      (is (= "false" (u/eval-str "\"a\" > \"a\"")))
      (is (= "true"  (u/eval-str "\"a\" <= \"a\"")))
      (is (= "true"  (u/eval-str "\"a\" >= \"a\"")))
      (is (= "true"  (u/eval-str "\"a\" == \"a\""))))))

;;; Challenge 7.2 - string-coercing + ----------------------------------------

(deftest challenge-7-2-string-coercing-plus
  (testing "off by default: mixing a string and a non-string in + is an error"
    (is (= "Operands must be two numbers or two strings."
           (u/runtime-error-message-of "\"scone\" + 4"))))

  (binding [interp/*string-coercing-plus?* true]
    (testing "on: if EITHER operand is a string, the other is converted"
      (are [src expected] (= expected (u/eval-str src))
        "\"scone\" + 4"      "scone4"
        "4 + \"scone\""      "4scone"
        "\"n = \" + 1.5"     "n = 1.5"
        "\"\" + 1"           "1"
        "\"x\" + true"       "xtrue"
        "false + \"x\""      "falsex"
        "\"x\" + nil"        "xnil"
        "nil + \"x\""        "nilx"))

    (testing "the number is converted the way the user's own print shows it -
              `stringify`, so no stray .0 appears"
      (is (= "scone4" (u/eval-str "\"scone\" + 4")))
      (is (= "a1"     (u/eval-str "\"a\" + 1")))
      (is (= "a1.5"   (u/eval-str "\"a\" + 1.5"))))

    (testing "the two book cases are untouched: numbers still ADD and two
              strings still concatenate"
      (is (= "3"  (u/eval-str "1 + 2")))
      (is (= "ab" (u/eval-str "\"a\" + \"b\"")))
      (is (instance? Double (u/evaluate "1 + 2"))))

    (testing "and a pair with no string at all is still an error"
      (are [src] (= "Operands must be two numbers or two strings."
                    (u/runtime-error-message-of src))
        "1 + nil"  "nil + nil"  "true + 1"  "nil + true"))

    (testing "concatenation is left-associative, so it chains correctly"
      (is (= "a12" (u/eval-str "\"a\" + 1 + 2")))
      (is (= "a3"  (u/eval-str "\"a\" + (1 + 2)"))))))

;;; Challenge 7.3 - division by zero -----------------------------------------

(deftest challenge-7-3-division-by-zero
  (testing "off by default: IEEE 754 behaviour, as tested in section 2"
    (is (= "Infinity" (u/eval-str "1 / 0")))
    (is (not (u/had-runtime-error?))))

  (binding [interp/*error-on-division-by-zero?* true]
    (testing "on: a zero divisor is a runtime error"
      (are [src] (= "Division by zero." (u/runtime-error-message-of src))
        "1 / 0"  "0 / 0"  "-1 / 0"  "1 / -0"  "1 / 0.0"  "1 / (1 - 1)"))

    (testing "reported against the / token, so the line is right"
      (is (= "Division by zero.\n[line 2]" (u/runtime-error-of "1\n/ 0"))))

    (testing "non-zero divisors are unaffected"
      (are [src expected] (= expected (u/eval-str src))
        "1 / 2"      "0.5"
        "10 / 5"     "2"
        "1 / 0.5"    "2"
        "0 / 1"      "0"
        "-1 / -1"    "1"))

    (testing "and the other operators are unaffected by the switch"
      (is (= "0" (u/eval-str "1 * 0")))
      (is (= "1" (u/eval-str "1 + 0")))
      (is (= "0" (u/eval-str "0 * 1"))))))

;;; ===========================================================================
;;; 8. Chapter 6's challenge nodes, now that they can be evaluated
;;; ===========================================================================

(deftest conditional-evaluates-exactly-one-branch
  (binding [parser/*allow-conditional?* true]
    (testing "the ternary picks a branch by truthiness"
      (are [src expected] (= expected (u/eval-str src))
        "true ? 1 : 2"       "1"
        "false ? 1 : 2"      "2"
        "nil ? 1 : 2"        "2"
        "0 ? 1 : 2"          "1"
        "\"\" ? 1 : 2"       "1"
        "1 < 2 ? \"y\" : \"n\"" "y"
        "2 < 1 ? \"y\" : \"n\"" "n"))

    (testing "it is right-associative, so a chain reads as nested else-ifs"
      (is (= "2" (u/eval-str "false ? 1 : true ? 2 : 3")))
      (is (= "3" (u/eval-str "false ? 1 : false ? 2 : 3"))))

    (testing "the untaken branch is NOT evaluated - which is the whole reason
              :conditional is its own node rather than a pair of :binary ones"
      (is (= "safe" (u/eval-str "true ? \"safe\" : -nil")))
      (is (= "safe" (u/eval-str "false ? -nil : \"safe\"")))
      (is (not (u/had-runtime-error?))))

    (testing "and only the condition plus one branch appear in the trace"
      (is (= [true 1.0 :conditional] (second (eval-trace "true ? 1 : 2"))))
      (is (= [false 2.0 :conditional] (second (eval-trace "false ? 1 : 2")))))

    (testing "a runtime error in the TAKEN branch still propagates"
      (is (= "Operand must be a number."
             (u/runtime-error-message-of "true ? -nil : 1"))))))

(deftest comma-operator-discards-its-left-operand
  (binding [parser/*allow-comma?* true]
    (testing "C's comma operator evaluates both and yields the right"
      (are [src expected] (= expected (u/eval-str src))
        "1, 2"        "2"
        "1, 2, 3"     "3"
        "1 + 1, 2 + 2" "4"
        "\"a\", 1"    "1"))

    (testing "but it really does evaluate the left operand, side effects and
              all - a runtime error there is still reported"
      (is (= "Operand must be a number."
             (u/runtime-error-message-of "-nil, 1"))))

    (testing "and the trace shows both operands evaluated, left first"
      (is (= [1.0 2.0 :binary] (second (eval-trace "1, 2")))))))
