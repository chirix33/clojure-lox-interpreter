;;;; =============================================================================
;;;; Chapter 6 - Parsing Expressions : unit tests
;;;;
;;;; The chapter makes four claims, and the sections below test them in order:
;;;;
;;;;   1. the parser primitives (peek/previous/check/advance/match/consume)
;;;;      behave like the book's, including at the :eof boundary
;;;;   2. every grammar rule of section 6.1 builds the right tree - which means
;;;;      correct *precedence* (which operator ends up on top) and correct
;;;;      *associativity* (which way a run of equal-precedence operators leans)
;;;;   3. syntax errors are detected, reported against the right token, and do
;;;;      not crash or hang the parser (section 6.3)
;;;;   4. the three end-of-chapter challenges work
;;;;
;;;; Most shape assertions go through `u/parse-str`, which parses and then runs
;;;; chapter 5's AST printer over the result. `"(+ 1.0 (* 2.0 3.0)"` states the
;;;; precedence claim far more legibly than a nested map literal would, and if
;;;; the tree is wrong the failure message shows both trees side by side.
;;;; Section 2 does compare raw maps in a few places, to pin down that the
;;;; printer is not hiding a difference.
;;;; =============================================================================
(ns lox.chapter-06-parsing-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [lox.ast :as ast]
            [lox.ast-printer :as printer]
            [lox.errors :as err]
            [lox.parser :as parser]
            [lox.scanner :as scanner]
            [lox.token :as tok]
            [lox.test-util :as u]))

(use-fixtures :each u/quiet-errors)

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn state
  "A fresh parser state positioned at the start of `src`."
  [src]
  (parser/make-parser (scanner/scan-tokens src)))

(defn types-of
  "The token types of `src`, :eof included - handy for sanity-checking a test's
  own premises before asserting on what the parser did with them."
  [src]
  (mapv :type (scanner/scan-tokens src)))

(defn parse-error-of
  "Parse `src` and return the first reported error line, or nil if it parsed.

  Resets the error log first: the `quiet-errors` fixture runs once per
  `deftest`, so without this an `are` block of several sources would keep
  reporting the *first* source's error for all of them."
  [src]
  (err/reset-errors!)
  (u/parse src)
  (u/first-error))

(defn parse-errors-of
  "Every error line reported while parsing `src`, from a clean slate."
  [src]
  (err/reset-errors!)
  (u/parse src)
  (u/errors))

(defn parses?
  "True if `src` parses without any error being reported."
  [src]
  (err/reset-errors!)
  (let [expr (u/parse src)]
    (and (some? expr) (not (u/had-error?)))))

(defmacro with-challenges
  "Evaluate `body` with challenge 6.1 and 6.2 grammar extensions enabled."
  [& body]
  `(binding [parser/*allow-comma?* true
             parser/*allow-conditional?* true]
     ~@body))

;;; ===========================================================================
;;; 1. Parser primitives - section 6.2.1
;;; ===========================================================================

(deftest parser-state-starts-at-the-first-token
  (let [s (state "1 + 2")]
    (is (= 0 (:current s)))
    (is (= :number (:type (parser/peek-token s))))
    (is (= 1.0 (:literal (parser/peek-token s))))))

(deftest make-parser-accepts-any-sequence
  (testing "a lazy seq of tokens is vectorised, so nth stays O(1)"
    (let [s (parser/make-parser (map identity (scanner/scan-tokens "1")))]
      (is (vector? (:tokens s)))
      (is (= :number (:type (parser/peek-token s)))))))

(deftest peek-and-previous
  (let [s0 (state "1 + 2")
        s1 (parser/advance s0)]
    (testing "peek does not consume"
      (is (= (parser/peek-token s0) (parser/peek-token s0)))
      (is (= 0 (:current s0))))
    (testing "previous is the token just consumed"
      (is (= :number (:type (parser/previous-token s1))))
      (is (= :plus   (:type (parser/peek-token s1)))))))

(deftest at-end-is-the-eof-token
  (let [s (state "1")]
    (is (not (parser/at-end? s)))
    (is (parser/at-end? (parser/advance s))))

  (testing "the empty source is immediately at end"
    (is (parser/at-end? (state "")))))

(deftest advance-stops-at-eof
  (testing "advancing past :eof is a no-op, so no rule can run off the end"
    (let [s (reduce (fn [s _] (parser/advance s)) (state "1") (range 50))]
      (is (parser/at-end? s))
      (is (= 1 (:current s))))))

(deftest check-never-matches-eof
  (testing "check is false at :eof even when asked for :eof itself"
    (let [s (state "")]
      (is (parser/at-end? s))
      (is (not (parser/check? s :eof)))
      (is (not (parser/check? s :number))))))

(deftest match-returns-state-or-nil
  (let [s (state "+ -")]
    (testing "a match consumes and returns the new state"
      (let [s' (parser/match s :plus)]
        (is (some? s'))
        (is (= 1 (:current s')))
        (is (= :plus (:type (parser/previous-token s'))))))

    (testing "a non-match returns nil and consumes nothing"
      (is (nil? (parser/match s :star)))
      (is (= 0 (:current s))))

    (testing "several types: the first that matches wins"
      (is (some? (parser/match s :star :plus)))
      (is (nil? (parser/match s :star :slash))))))

(deftest consume-advances-or-throws
  (testing "the expected token is consumed"
    (let [s (parser/consume (state ")") :right-paren "Expect ')'.")]
      (is (parser/at-end? s))
      (is (= :right-paren (:type (parser/previous-token s))))))

  (testing "an unexpected token throws a parse error and reports it"
    (let [thrown (try (parser/consume (state "1") :right-paren "Expect ')'.")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (parser/parse-error? thrown))
      (is (= ["[line 1] Error at '1': Expect ')'."] (u/errors))))))

(deftest parse-error-predicate-rejects-other-exceptions
  (is (not (parser/parse-error? (ex-info "nope" {}))))
  (is (not (parser/parse-error? (RuntimeException. "nope"))))
  (is (not (parser/parse-error? nil))))

;;; ===========================================================================
;;; 2. The grammar - section 6.1
;;; ===========================================================================

;;; --- primary ---------------------------------------------------------------

(deftest primary-literals
  (testing "number and string literals carry the scanner's converted value"
    (is (= "1.0"     (u/parse-str "1")))
    (is (= "1.5"     (u/parse-str "1.5")))
    (is (= "hi"      (u/parse-str "\"hi\"")))
    (is (= ""        (u/parse-str "\"\""))))

  (testing "the three keyword literals"
    (is (= "true"  (u/parse-str "true")))
    (is (= "false" (u/parse-str "false")))
    (is (= "nil"   (u/parse-str "nil"))))

  (testing "keyword literals produce real Lox values, not the keyword names"
    (is (= (ast/literal true)  (u/parse "true")))
    (is (= (ast/literal false) (u/parse "false")))
    (is (= (ast/literal nil)   (u/parse "nil")))
    (is (ast/literal? (u/parse "nil")))))

(deftest primary-number-literal-is-a-double
  (testing "book: the scanner stores NUMBER literals as Double"
    (is (instance? Double (:value (u/parse "42"))))
    (is (= 42.0 (:value (u/parse "42"))))))

(deftest primary-grouping
  (is (= "(group 1.0)"          (u/parse-str "(1)")))
  (is (= "(group (group 1.0))"  (u/parse-str "((1))")))
  (is (= "(group (+ 1.0 2.0))"  (u/parse-str "(1 + 2)")))

  (testing "a grouping node really wraps its contents"
    (let [e (u/parse "(1)")]
      (is (ast/grouping? e))
      (is (ast/literal? (:expression e))))))

;;; --- unary -----------------------------------------------------------------

(deftest unary-operators
  (is (= "(- 1.0)"  (u/parse-str "-1")))
  (is (= "(! true)" (u/parse-str "!true"))))

(deftest unary-is-right-associative-and-nests
  (testing "book: !!true is valid if weird"
    (is (= "(! (! true))"      (u/parse-str "!!true")))
    (is (= "(- (- (- 1.0)))"   (u/parse-str "---1")))
    (is (= "(! (- 1.0))"       (u/parse-str "!-1"))))

  (testing "the innermost operator sits deepest in the tree"
    (let [e (u/parse "!!true")]
      (is (ast/unary? e))
      (is (ast/unary? (:right e)))
      (is (ast/literal? (:right (:right e)))))))

(deftest unary-binds-tighter-than-any-binary-operator
  (is (= "(* (- 1.0) 2.0)"  (u/parse-str "-1 * 2")))
  (is (= "(+ (- 1.0) 2.0)"  (u/parse-str "-1 + 2")))
  (is (= "(- (- 1.0) 2.0)"  (u/parse-str "-1 - 2")))
  ;; Identifiers are not primary expressions until chapter 8, so the operands
  ;; here are keyword literals.
  (is (= "(== (! true) false)" (u/parse-str "!true == false"))))

(deftest unary-minus-applies-to-the-operand-not-the-whole-expression
  (testing "-a * b is (-a) * b, not -(a * b)"
    (let [e (u/parse "-2 * 3")]
      (is (ast/binary? e))
      (is (= :star (-> e :operator :type)))
      (is (ast/unary? (:left e))))))

;;; --- the four binary levels -------------------------------------------------

(deftest factor-level
  (is (= "(* 1.0 2.0)" (u/parse-str "1 * 2")))
  (is (= "(/ 1.0 2.0)" (u/parse-str "1 / 2"))))

(deftest term-level
  (is (= "(+ 1.0 2.0)" (u/parse-str "1 + 2")))
  (is (= "(- 1.0 2.0)" (u/parse-str "1 - 2"))))

(deftest comparison-level
  (are [src expected] (= expected (u/parse-str src))
    "1 > 2"  "(> 1.0 2.0)"
    "1 >= 2" "(>= 1.0 2.0)"
    "1 < 2"  "(< 1.0 2.0)"
    "1 <= 2" "(<= 1.0 2.0)"))

(deftest equality-level
  (are [src expected] (= expected (u/parse-str src))
    "1 == 2" "(== 1.0 2.0)"
    "1 != 2" "(!= 1.0 2.0)"))

(deftest every-binary-operator-in-the-table-parses
  (testing "all 10 binary operators of section 6.1, none missing"
    (doseq [{:keys [operators]} parser/binary-levels
            op operators]
      (let [lexeme (:lexeme (first (filter #(= op (:type %))
                                           (scanner/scan-tokens
                                            (case op
                                              :bang-equal "!=" :equal-equal "=="
                                              :greater ">" :greater-equal ">="
                                              :less "<" :less-equal "<="
                                              :minus "-" :plus "+"
                                              :slash "/" :star "*")))))
            src    (str "1 " lexeme " 2")
            e      (u/parse src)]
        (is (ast/binary? e) src)
        (is (= op (-> e :operator :type)) src)))))

;;; --- associativity ----------------------------------------------------------

(deftest binary-operators-are-left-associative
  (testing "book: 5 - 3 - 1 is (5 - 3) - 1"
    (is (= "(- (- 5.0 3.0) 1.0)" (u/parse-str "5 - 3 - 1"))))

  (testing "every level leans left"
    (are [src expected] (= expected (u/parse-str src))
      "1 + 2 + 3"    "(+ (+ 1.0 2.0) 3.0)"
      "1 - 2 - 3"    "(- (- 1.0 2.0) 3.0)"
      "1 * 2 * 3"    "(* (* 1.0 2.0) 3.0)"
      "1 / 2 / 3"    "(/ (/ 1.0 2.0) 3.0)"
      "1 < 2 < 3"    "(< (< 1.0 2.0) 3.0)"
      "1 == 2 == 3"  "(== (== 1.0 2.0) 3.0)")))

(deftest left-associativity-over-a-long-run
  (testing "book's diagram: a == b == c == d == e nests leftwards"
    (is (= "(== (== (== (== 1.0 2.0) 3.0) 4.0) 5.0)"
           (u/parse-str "1 == 2 == 3 == 4 == 5"))))

  (testing "the depth of the tree matches the number of operators"
    (let [e (u/parse "1 - 2 - 3 - 4")]
      (is (= 3 (loop [e e n 0]
                 (if (ast/binary? e) (recur (:left e) (inc n)) n)))))))

(deftest mixed-operators-at-one-level-still-lean-left
  (is (= "(- (+ 1.0 2.0) 3.0)" (u/parse-str "1 + 2 - 3")))
  (is (= "(/ (* 1.0 2.0) 3.0)" (u/parse-str "1 * 2 / 3"))))

;;; --- precedence -------------------------------------------------------------

(deftest precedence-table-is-honoured
  (testing "book's own example: 6 / 3 - 1 must be (6 / 3) - 1"
    (is (= "(- (/ 6.0 3.0) 1.0)" (u/parse-str "6 / 3 - 1"))))

  (testing "factor binds tighter than term"
    (is (= "(+ 1.0 (* 2.0 3.0))" (u/parse-str "1 + 2 * 3")))
    (is (= "(+ (* 1.0 2.0) 3.0)" (u/parse-str "1 * 2 + 3")))
    (is (= "(- 1.0 (/ 2.0 3.0))" (u/parse-str "1 - 2 / 3"))))

  (testing "term binds tighter than comparison"
    (is (= "(< (+ 1.0 2.0) 3.0)" (u/parse-str "1 + 2 < 3")))
    (is (= "(> 1.0 (- 2.0 3.0))" (u/parse-str "1 > 2 - 3"))))

  (testing "comparison binds tighter than equality"
    (is (= "(== (< 1.0 2.0) 3.0)" (u/parse-str "1 < 2 == 3")))
    (is (= "(!= 1.0 (>= 2.0 3.0))" (u/parse-str "1 != 2 >= 3")))))

(deftest full-precedence-chain-in-one-expression
  (testing "one expression touching every level at once"
    (is (= "(== (< 1.0 (+ 2.0 (* 3.0 (- 4.0)))) false)"
           (u/parse-str "1 < 2 + 3 * -4 == false")))))

(deftest grouping-overrides-precedence
  (is (= "(* (group (+ 1.0 2.0)) 3.0)" (u/parse-str "(1 + 2) * 3")))
  (is (= "(* 1.0 (group (+ 2.0 3.0)))" (u/parse-str "1 * (2 + 3)")))

  (testing "book's associativity note: grouping changes the tree shape"
    (is (not= (u/parse-str "0.1 * 0.2 * 0.3")
              (u/parse-str "0.1 * (0.2 * 0.3)")))))

(deftest precedence-order-is-declared-loosest-first
  (testing "the table as data matches section 6.1's table as prose"
    (is (= [:expression :equality :comparison :term :factor :unary :primary]
           parser/precedence-order)))

  (testing "each binary level lists exactly the book's operators"
    (is (= [[:equality   [:bang-equal :equal-equal]]
            [:comparison [:greater :greater-equal :less :less-equal]]
            [:term       [:minus :plus]]
            [:factor     [:slash :star]]]
           (mapv (juxt :rule :operators) parser/binary-levels)))))

(deftest ambiguity-of-chapter-5-is-gone
  (testing "the string the book uses to demonstrate ambiguity now has one tree"
    (let [trees (repeatedly 5 #(u/parse-str "6 / 3 - 1"))]
      (is (= 1 (count (set trees))))
      (is (= "(- (/ 6.0 3.0) 1.0)" (first trees))))))

;;; --- deep / stress ----------------------------------------------------------

(deftest deeply-nested-grouping
  (testing "recursive descent handles nesting without special support"
    (let [depth 100
          src   (str (str/join (repeat depth "(")) "1" (str/join (repeat depth ")")))
          e     (u/parse src)]
      (is (some? e))
      (is (not (u/had-error?)))
      (is (= depth (loop [e e n 0]
                     (if (ast/grouping? e) (recur (:expression e) (inc n)) n)))))))

(deftest long-flat-expression
  (testing "a 200-operator chain neither overflows nor mis-associates"
    (let [src (str/join " + " (repeat 200 "1"))
          e   (u/parse src)]
      (is (some? e))
      (is (not (u/had-error?)))
      (is (= 199 (loop [e e n 0]
                   (if (ast/binary? e) (recur (:left e) (inc n)) n)))))))

(deftest parsing-is-a-pure-function-of-its-input
  (testing "the same tokens parse to the same tree every time"
    (let [tokens (scanner/scan-tokens "1 + 2 * 3")]
      (is (= (parser/parse tokens) (parser/parse tokens)))))

  (testing "parsing does not mutate the token vector"
    (let [tokens (scanner/scan-tokens "1 + 2")]
      (parser/parse tokens)
      (is (= [:number :plus :number :eof] (mapv :type tokens))))))

;;; --- tokens in, trees out ---------------------------------------------------

(deftest operator-tokens-are-carried-into-the-tree
  (testing "a node keeps the actual token, so chapter 7 can report its line"
    (let [e (u/parse "1\n+\n2")]
      (is (= :plus (-> e :operator :type)))
      (is (= "+"   (-> e :operator :lexeme)))
      (is (= 2     (-> e :operator :line)))))

  (testing "the token is the scanner's, unmodified"
    (let [tokens (scanner/scan-tokens "1 * 2")
          star   (first (filter #(= :star (:type %)) tokens))]
      (is (= star (:operator (parser/parse tokens)))))))

(deftest every-node-produced-is-a-valid-ast-node
  (testing "chapter 5's field checking never fires, i.e. the parser builds
            well-formed nodes for a broad sample of inputs"
    (doseq [src ["1" "\"s\"" "true" "nil" "-1" "!true" "(1)"
                 "1+2" "1*2/3" "1<2==3" "(1+2)*(3-4)" "!!(1 == 1)"]]
      (let [e (u/parse src)]
        (is (ast/expr? e) src)
        ;; Walking the whole tree proves every *interior* node is valid too.
        (is (every? ast/expr? (tree-seq ast/expr? ast/children e)) src)))))

;;; ===========================================================================
;;; 3. Syntax errors - section 6.3
;;; ===========================================================================

(deftest missing-closing-paren
  (testing "book: Expect ')' after expression."
    (is (= "[line 1] Error at end: Expect ')' after expression."
           (parse-error-of "(1 + 2")))))

(deftest missing-closing-paren-points-at-the-offending-token
  (is (= "[line 1] Error at '2': Expect ')' after expression."
         (parse-error-of "(1 2)"))))

(deftest token-that-cannot-start-an-expression
  (testing "book: Expect expression."
    (are [src expected] (= expected (parse-error-of src))
      ")"      "[line 1] Error at ')': Expect expression."
      ""       "[line 1] Error at end: Expect expression."
      "1 + "   "[line 1] Error at end: Expect expression."
      "(1 + )" "[line 1] Error at ')': Expect expression.")))

(deftest error-token-reports-the-right-line
  (testing "the reported line is the offending token's, not the last line"
    (is (= "[line 3] Error at end: Expect expression."
           (parse-error-of "1 +\n2 +\n")))))

(deftest eof-errors-say-at-end
  (testing "book: EOF has no lexeme to show, so we say ' at end'"
    (is (str/includes? (parse-error-of "1 +") " at end:"))
    (is (not (str/includes? (parse-error-of "1 +") "at ''")))))

(deftest parse-returns-nil-and-sets-the-flag-on-error
  (testing "book: 'When a syntax error does occur, this method returns null.'"
    (is (nil? (u/parse "(1")))
    (is (u/had-error?)))

  (testing "a clean parse leaves the flag alone"
    (err/reset-errors!)
    (is (some? (u/parse "1 + 1")))
    (is (not (u/had-error?)))))

(deftest parser-never-crashes-or-hangs
  (testing "book's hard requirement: robust in the face of any input"
    (doseq [src ["" "(" ")" "((((" "))))" "+" "*" "1 +" "+ 1" "1 ** 2"
                 "(((1)" "1)" "* * *" "!" "- - -" "1 1 1" "\"unterminated"
                 "== == ==" "( ) ( )" "1 + + 2" "@" "1 @ 2" ";" "}" "var"
                 "if" "for while" "print" "1.2.3" "..." ".5"]]
      (is (nil? (try (u/parse src)
                     nil
                     (catch Throwable t
                       (str "threw on " (pr-str src) ": " t))))
          (str "should not throw on " (pr-str src))))))

(deftest a-parse-error-does-not-escape-the-parser
  (testing "book: 'we don't want the ParseError exception to escape'"
    (is (nil? (u/parse "(")))
    (is (u/had-error?))))

(deftest non-parse-exceptions-are-not-swallowed
  (testing "only the parser's own unwind signal is caught"
    (is (thrown? IllegalArgumentException
                 (with-redefs [ast/literal (fn [_] (throw (IllegalArgumentException. "boom")))]
                   (u/parse "1"))))))

(deftest scan-errors-and-parse-errors-share-one-flag
  (testing "an unscannable character is reported and leaves had-error set"
    (is (some #(str/includes? % "Unexpected character.")
              (parse-errors-of "1 @ 2")))
    (is (u/had-error?))))

;;; --- synchronization - section 6.3.3 ---------------------------------------

(deftest synchronize-stops-after-a-semicolon
  (testing "book: 'After a semicolon, we're probably finished with a statement'"
    (let [s (state "1 + 2 ; 3")
          s (parser/synchronize s)]
      (is (= :semicolon (:type (parser/previous-token s))))
      (is (= :number    (:type (parser/peek-token s))))
      (is (= 3.0        (:literal (parser/peek-token s)))))))

(deftest synchronize-stops-before-a-statement-keyword
  (are [src expected] (= expected
                         (:type (parser/peek-token (parser/synchronize (state src)))))
    "x class"  :class
    "x fun"    :fun
    "x var"    :var
    "x for"    :for
    "x if"     :if
    "x while"  :while
    "x print"  :print
    "x return" :return))

(deftest synchronize-consumes-at-least-one-token
  (testing "book: synchronize() starts with advance(), so it always makes
            progress - otherwise a caller could loop forever on a bad token"
    (let [s (state "var x")]
      (is (= :var (:type (parser/peek-token s))))
      ;; Even though we are *already* sitting on a sync keyword, it is
      ;; discarded rather than matched. That is what guarantees termination:
      ;; a caller that synchronizes in a loop always makes progress.
      (is (pos? (:current (parser/synchronize s)))))

    (testing "and it stops at the *next* keyword, not the one it started on"
      (let [s (parser/synchronize (state "var if x"))]
        (is (= :if (:type (parser/peek-token s))))))))

(deftest synchronize-runs-to-eof-when-nothing-matches
  (let [s (parser/synchronize (state "1 2 3 4 5"))]
    (is (parser/at-end? s))))

(deftest synchronize-keyword-set-matches-the-book
  (is (= #{:class :fun :var :for :if :while :print :return}
         parser/synchronization-keywords)))

(deftest synchronize-terminates-on-every-input
  (testing "no input makes it loop - the eof guard plus the leading advance"
    (doseq [src ["" ";" "; ;" "var" "1" "1 ; var x" "( ( ( ("]]
      (is (map? (parser/synchronize (state src))) src))))

;;; ===========================================================================
;;; 4. Challenges
;;; ===========================================================================

;;; --- 6.1 the comma operator -------------------------------------------------

(deftest comma-is-off-by-default
  (testing "the default grammar is exactly the book's, so `,` cannot appear"
    (is (nil? (u/parse "1, 2")))
    (is (u/had-error?))))

(deftest comma-operator-parses-when-enabled
  (binding [parser/*allow-comma?* true]
    (is (= "(, 1.0 2.0)" (u/parse-str "1, 2")))))

(deftest comma-is-left-associative
  (binding [parser/*allow-comma?* true]
    (testing "C's comma is left-associative: 1,2,3 is (1,2),3"
      (is (= "(, (, 1.0 2.0) 3.0)" (u/parse-str "1, 2, 3"))))))

(deftest comma-has-the-lowest-precedence
  (binding [parser/*allow-comma?* true]
    (testing "every other operator binds tighter"
      (is (= "(, (+ 1.0 2.0) (* 3.0 4.0))" (u/parse-str "1 + 2, 3 * 4")))
      (is (= "(, (== 1.0 2.0) 3.0)"        (u/parse-str "1 == 2, 3"))))

    (testing "grouping still fences it in"
      (is (= "(* (group (, 1.0 2.0)) 3.0)" (u/parse-str "(1, 2) * 3"))))))

(deftest comma-uses-the-binary-node-with-the-comma-token
  (binding [parser/*allow-comma?* true]
    (let [e (u/parse "1, 2")]
      (is (ast/binary? e))
      (is (= :comma (-> e :operator :type)))
      (is (= ","    (-> e :operator :lexeme))))))

(deftest comma-still-requires-a-right-operand
  (binding [parser/*allow-comma?* true]
    (is (= "[line 1] Error at end: Expect expression." (parse-error-of "1,")))))

;;; --- 6.2 the ternary conditional -------------------------------------------

(deftest question-and-colon-now-scan
  (testing "the scanner learned two new lexemes for this challenge"
    (is (= [:question :colon :eof] (types-of "?:")))
    (is (contains? tok/token-types :question))
    (is (contains? tok/token-types :colon)))

  (testing "they scan cleanly - no 'Unexpected character.'"
    (u/scan "? :")
    (is (not (u/had-error?)))))

(deftest conditional-is-off-by-default
  (is (nil? (u/parse "true ? 1 : 2")))
  (is (u/had-error?)))

(deftest conditional-parses-when-enabled
  (binding [parser/*allow-conditional?* true]
    (is (= "(?: true 1.0 2.0)" (u/parse-str "true ? 1 : 2")))))

(deftest conditional-is-right-associative
  (binding [parser/*allow-conditional?* true]
    (testing "a ? b : c ? d : e groups as a ? b : (c ? d : e) - else-if chains"
      (is (= "(?: true 1.0 (?: false 2.0 3.0))"
             (u/parse-str "true ? 1 : false ? 2 : 3"))))

    (testing "the recursion is on the else branch"
      (let [e (u/parse "true ? 1 : false ? 2 : 3")]
        (is (ast/conditional? e))
        (is (ast/literal? (:then-branch e)))
        (is (ast/conditional? (:else-branch e)))))))

(deftest conditional-precedence
  (binding [parser/*allow-conditional?* true]
    (testing "binds looser than equality, so the condition can be a comparison"
      (is (= "(?: (< 1.0 2.0) 3.0 4.0)" (u/parse-str "1 < 2 ? 3 : 4")))
      (is (= "(?: (== 1.0 2.0) 3.0 4.0)" (u/parse-str "1 == 2 ? 3 : 4"))))

    (testing "the branches are parsed at the right level too"
      (is (= "(?: true (+ 1.0 2.0) (* 3.0 4.0))"
             (u/parse-str "true ? 1 + 2 : 3 * 4"))))))

(deftest conditional-middle-operand-is-a-full-expression
  (binding [parser/*allow-comma?* true parser/*allow-conditional?* true]
    (testing "in C the middle operand is unfenced - even a comma fits"
      (is (= "(?: true (, 1.0 2.0) 3.0)"
             (u/parse-str "true ? 1, 2 : 3"))))))

(deftest conditional-with-comma-at-the-top-level
  (with-challenges
    (testing "`,` is looser than `?:`, so the ternary is an operand of the comma"
      (is (= "(, (?: true 1.0 2.0) 3.0)"
             (u/parse-str "true ? 1 : 2, 3"))))))

(deftest conditional-error-cases
  (binding [parser/*allow-conditional?* true]
    (testing "a missing colon is reported precisely"
      (is (= "[line 1] Error at end: Expect ':' after then branch of conditional expression."
             (parse-error-of "true ? 1"))))

    (testing "a missing else branch"
      (is (= "[line 1] Error at end: Expect expression."
             (parse-error-of "true ? 1 :"))))

    (testing "a missing then branch"
      (is (= "[line 1] Error at ':': Expect expression."
             (parse-error-of "true ? : 2"))))))

(deftest conditional-node-round-trips-through-both-visitors
  (binding [parser/*allow-conditional?* true]
    (let [e (u/parse "true ? 1 : 2")]
      (is (= "(?: true 1.0 2.0)" (printer/print-ast e)))
      (is (= "true 1.0 2.0 ?:"   (printer/print-rpn e))))))

(deftest conditional-node-is-part-of-the-ast-family
  (testing "chapter 5's machinery accepted the new production unchanged"
    (is (contains? ast/expr-node-types :conditional))
    (is (= '[[:expr condition] [:expr then-branch] [:expr else-branch]]
           (:conditional ast/expr-types)))
    (is (= 3 (count (ast/children
                     (ast/conditional (ast/literal true)
                                      (ast/literal 1.0)
                                      (ast/literal 2.0))))))))

;;; --- 6.3 error productions for a missing left operand ----------------------

(deftest binary-operator-with-no-left-operand-is-reported
  (are [src lexeme] (= (str "[line 1] Error at '" lexeme "': Binary operator '"
                            lexeme "' requires a left-hand operand.")
                       (parse-error-of src))
    "* 3"  "*"
    "/ 3"  "/"
    "+ 3"  "+"
    "== 3" "=="
    "!= 3" "!="
    "< 3"  "<"
    "<= 3" "<="
    "> 3"  ">"
    ">= 3" ">="))

(deftest minus-is-not-an-error-production
  (testing "a leading `-` is a legal unary operator, not a missing operand"
    (is (parses? "-3"))
    (is (= "(- 3.0)" (u/parse-str "-3")))))

(deftest error-production-discards-the-right-operand
  (testing "book's challenge: 'parse and discard a right-hand operand with the
            appropriate precedence' - so exactly one error, not a cascade"
    (let [errors (parse-errors-of "* 1 + 2")]
      (is (= 1 (count errors)))
      (is (str/includes? (first errors) "requires a left-hand operand")))))

(deftest error-production-parses-operand-at-the-right-precedence
  (testing "the discarded operand is parsed at the level tighter than the
            operator, so its own contents are still checked for errors"
    (is (some #(str/includes? % "Expect expression.")
              (parse-errors-of "* (1 + ")))))

(deftest error-productions-can-be-switched-off
  (binding [parser/*binary-error-productions?* false]
    (testing "without them we fall back to the book's generic message"
      (is (= "[line 1] Error at '*': Expect expression."
             (parse-error-of "* 3"))))))

(deftest error-productions-report-the-challenge-operators-too
  (with-challenges
    (is (str/includes? (parse-error-of ", 1") "requires a left-hand operand"))
    (is (str/includes? (parse-error-of "? 1 : 2") "requires a left-hand operand"))))

(deftest error-production-table-covers-every-binary-operator
  (testing "derived from binary-levels, so the two cannot drift apart"
    (is (= (set (mapcat :operators parser/binary-levels))
           (set (keys parser/binary-operators-by-level))))
    (is (= 10 (count parser/binary-operators-by-level)))))

(deftest error-production-still-returns-nil
  (testing "there is no usable tree, and the flag is set"
    (is (nil? (u/parse "* 3")))
    (is (u/had-error?))))

;;; ===========================================================================
;;; 5. Wiring - section 6.4
;;; ===========================================================================

(deftest parse-source-scans-and-parses
  (is (= "(+ 1.0 2.0)" (printer/print-ast (parser/parse-source "1 + 2"))))
  (is (nil? (parser/parse-source "1 +"))))

(deftest run-prints-the-syntax-tree
  (testing "book: replace the token dump with the AST printer's output"
    (let [[_ out] (u/capture-out #(require 'lox.core))]
      (let [core-run (resolve 'lox.core/run)
            [_ lines] (u/capture-out #(core-run "1 + 2 * 3"))]
        (is (= ["(+ 1.0 (* 2.0 3.0))"] lines))))))

(deftest run-prints-nothing-when-there-was-a-syntax-error
  (testing "book: 'Stop if there was a syntax error.'"
    (require 'lox.core)
    (let [core-run  (resolve 'lox.core/run)
          [_ lines] (u/capture-out #(core-run "1 +"))]
      (is (= [] lines))
      (is (u/had-error?)))))

(deftest run-prints-nothing-when-the-scanner-failed
  (testing "a scan error must also stop us before the printer sees nil"
    (require 'lox.core)
    (let [core-run  (resolve 'lox.core/run)
          [_ lines] (u/capture-out #(core-run "@"))]
      (is (= [] lines))
      (is (u/had-error?)))))

(deftest chapter-4-token-dump-is-still-available
  (testing "--tokens keeps the previous chapter's submission runnable"
    (require 'lox.core)
    (let [print-tokens (resolve 'lox.core/print-tokens)
          [_ lines]    (u/capture-out #(print-tokens "1 + 2"))]
      (is (= ["NUMBER 1 1.0" "PLUS + null" "NUMBER 2 2.0" "EOF  null"] lines)))))

(deftest documented-grammar-matches-the-implementation
  (testing "every rule named in the precedence table has a production"
    (doseq [rule parser/precedence-order
            :let [head (name rule)]]
      (is (some #(str/starts-with? % head) parser/grammar)
          (str "no production for " head))))

  (testing "each binary level's production quotes exactly its own operators"
    (doseq [{:keys [rule operators]} parser/binary-levels
            :let [line    (first (filter #(str/starts-with? % (name rule))
                                         parser/grammar))
                  ;; the quoted terminals on that line, e.g. ("!=" "==")
                  quoted  (set (map second (re-seq #"\"([^\"]+)\"" line)))
                  lexemes (set (map #(:lexeme (first (scanner/scan-tokens
                                                      (case %
                                                        :bang-equal "!=" :equal-equal "=="
                                                        :greater ">" :greater-equal ">="
                                                        :less "<" :less-equal "<="
                                                        :minus "-" :plus "+"
                                                        :slash "/" :star "*"))))
                                    operators))]]
      (is (some? line) (name rule))
      (is (= lexemes quoted)
          (str (name rule) " production should quote exactly " lexemes))))

  (testing "the unary production quotes both prefix operators"
    (let [line (first (filter #(str/starts-with? % "unary") parser/grammar))]
      (is (str/includes? line "\"!\""))
      (is (str/includes? line "\"-\"")))))

;;; ===========================================================================
;;; 6. Whole-chapter smoke test
;;; ===========================================================================

(deftest the-chapters-closing-example
  (testing "'Fire up the interpreter and type in some expressions. See how it
            handles precedence and associativity correctly?'"
    (are [src expected] (= expected (u/parse-str src))
      "1 + 2 * 3 - 4 / 5"
      "(- (+ 1.0 (* 2.0 3.0)) (/ 4.0 5.0))"

      "(1 + 2) * (3 - 4)"
      "(* (group (+ 1.0 2.0)) (group (- 3.0 4.0)))"

      "-123 * (45.67)"
      "(* (- 123.0) (group 45.67))"

      "!(1 == 2) != (3 <= 4)"
      "(!= (! (group (== 1.0 2.0))) (group (<= 3.0 4.0)))")))

(deftest chapter-5s-demo-tree-is-what-the-parser-now-produces
  (testing "the tree chapter 5 had to hand-build, chapter 6 derives from text"
    (let [hand-built (printer/demo-expression)
          parsed     (u/parse "-123 * (45.67)")]
      (testing "identical shape, operator for operator"
        (is (= (ast/node-type hand-built) (ast/node-type parsed)))
        (is (= (-> hand-built :operator :type) (-> parsed :operator :type)))
        (is (= (-> hand-built :left :operator :type)
               (-> parsed :left :operator :type)))
        (is (ast/grouping? (:right parsed))))

      (testing "and the same numbers - chapter 5 hand-wrote the literal 123 as
                an integer, where the scanner always produces a Double, so the
                values compare numerically rather than by printed form"
        (is (== 123 (-> hand-built :left :right :value)
                    (-> parsed :left :right :value)))
        (is (== 45.67 (-> hand-built :right :expression :value)
                      (-> parsed :right :expression :value))))

      (testing "printed form differs only in that trailing .0"
        (is (= "(* (- 123) (group 45.67))"   (printer/print-ast hand-built)))
        (is (= "(* (- 123.0) (group 45.67))" (printer/print-ast parsed)))))))

(deftest valid-programs-report-no-errors
  (doseq [src ["1" "1 + 1" "(1)" "!true" "-(-1)" "1 == 1"
               "1 < 2 == 3 > 4" "\"a\" == \"a\"" "nil != false"]]
    (is (parses? src) src))

  (testing "and the challenge grammars are clean too"
    (with-challenges
      (doseq [src ["1, 2" "true ? 1 : 2" "1 < 2 ? 3, 4 : 5"]]
        (is (parses? src) src)))))
