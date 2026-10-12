;;;; =============================================================================
;;;; Chapter 9 - Control Flow: unit tests
;;;;
;;;; The chapter adds four constructs, and the suite is organised around them
;;;; plus the places where they interact with what came before:
;;;;
;;;;   1. the node family       sections 9.2-9.4 - the three new declarations
;;;;   2. `if`                  section 9.2      - syntax, dangling else, exec
;;;;   3. logical operators     section 9.3      - precedence, short-circuit
;;;;   4. `while`               section 9.4      - syntax and execution
;;;;   5. `for`                 sections 9.5-9.5.1 - parsing and desugaring
;;;;   6. Turing-completeness   section 9.1      - programs chapter 8 could not
;;;;                                               express at all
;;;;   7. errors and recovery                    - every new message, by text
;;;;   8. challenge 9.3         `break`
;;;;   9. interaction with the earlier chapters' challenge switches
;;;;  10. whole-chapter smoke tests
;;;;
;;;; The conventions are chapter 8's, and two of them do most of the work in
;;;; this chapter specifically:
;;;;
;;;; * ASSERT ON PRINTED LINES. Control flow is observable only as "which side
;;;;   effects happened, in what order", so `run-program` returning the printed
;;;;   lines is the natural oracle. `(is (= ["1" "2"] ...))` states both that
;;;;   the right branch ran and that the wrong one did not.
;;;;
;;;; * ASSERT ON THE PRINTED TREE for `for` loops. The desugaring of section
;;;;   9.5.1 is invisible at run time - a correct `for` and a correctly
;;;;   desugared `for` behave identically - so behaviour alone cannot test it.
;;;;   `program-str` is what distinguishes "it loops" from "it loops *because
;;;;   the parser rewrote it into a while*", which is the chapter's actual
;;;;   claim.
;;;;
;;;; A third is new here. Several of this chapter's tests assert that something
;;;; did *not* happen: that a short-circuited operand was never evaluated,
;;;; that a type error inside it therefore never fired, that the untaken branch
;;;; of an `if` had no effect. Those are the tests that distinguish real
;;;; control flow from an eager implementation that merely computes the right
;;;; answer, so each one is written with a *side effect* (a print, or an error
;;;; that would be reported) rather than with a value comparison - a value
;;;; test would pass either way.
;;;; =============================================================================
(ns lox.chapter-09-control-flow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is are use-fixtures]]
            [lox.ast :as ast]
            [lox.ast-printer :as printer]
            [lox.core :as core]
            [lox.errors :as err]
            [lox.environment :as env]
            [lox.interpreter :as interp]
            [lox.parser :as parser]
            [lox.test-util :as u]
            [lox.token :as tok]))

(use-fixtures :each u/quiet-errors u/fresh-globals)

;;; Hand-built pieces, for the tests that must not go through the parser.
(def ^:private or-token  (tok/make-token :or "or" nil 1))
(def ^:private and-token (tok/make-token :and "and" nil 1))
(def ^:private brk-token (tok/make-token :break "break" nil 1))

(defn- num-lit [n] (ast/literal (double n)))

(defn- expansion-failure
  "Macroexpand `form`, returning the `ex-data` of the exception it threw (or
  nil if it expanded cleanly). Clojure wraps a macro's own exception in a
  Compiler$CompilerException, so we walk down to the cause carrying the data.

  The same helper the chapter 5 suite uses, for the same reason: `defvisitor`'s
  completeness check is a *compile-time* guarantee, so the only way to test it
  is to try to expand a bad visitor."
  [form]
  (try
    (macroexpand form)
    nil
    (catch Exception e
      (loop [x e]
        (cond
          (and (instance? clojure.lang.ExceptionInfo x) (seq (ex-data x)))
          (ex-data x)

          (ex-cause x) (recur (ex-cause x))
          :else        nil)))))

(defn- with-break
  "Run `f` with challenge 9.3's `break` keyword switched on."
  [f]
  (binding [tok/*allow-break?* true] (f)))

(defn- break-program
  "Run `src` with `break` enabled, from a clean slate, returning printed lines."
  [src]
  (with-break #(u/run-fresh src)))

(defn- break-error
  "First error message reported while running `src` with `break` enabled."
  [src]
  (with-break #(u/program-error-message src)))

;;; ===========================================================================
;;; 1. The new node types - sections 9.2, 9.3, 9.4
;;; ===========================================================================

(deftest chapter-9-grammar-is-documented-and-matches-the-book
  (testing "the three Expr/Stmt productions the book's GenerateAst gains"
    ;; "Logical : Expr left, Token operator, Expr right"
    ;; "If      : Expr condition, Stmt thenBranch, Stmt elseBranch"
    ;; "While   : Expr condition, Stmt body"
    (is (= '[[:expr left] [:token operator] [:expr right]]
           (:logical ast/expr-types)))
    (is (= '[[:expr condition] [:stmt then-branch] [:stmt-or-nil else-branch]]
           (:if ast/stmt-types)))
    (is (= '[[:expr condition] [:stmt body]]
           (:while ast/stmt-types))))

  (testing "`:logical` has the same fields as `:binary` and is still its own
            production (section 9.3's explicit design decision)"
    (is (= (:binary ast/expr-types) (:logical ast/expr-types)))
    (is (not= :binary :logical))
    (is (contains? ast/expr-node-types :logical)))

  (testing "there is deliberately no :for node - section 9.5.1 desugars it"
    (is (not (contains? ast/stmt-node-types :for)))
    (is (not (contains? ast/expr-node-types :for))))

  (testing "the exact family membership as of chapter 9"
    (is (= #{:binary :grouping :literal :unary :conditional :variable :assign
             :logical}
           ast/expr-node-types))
    (is (= #{:block :expression :print :var :if :while :break}
           ast/stmt-node-types)))

  (testing "the documented grammars cover every rule they name"
    (doseq [rule ["statement" "ifStmt" "whileStmt" "forStmt"]]
      (is (some #(str/starts-with? % rule) parser/statement-grammar)
          (str "no production for " rule)))
    (doseq [rule ["logic_or" "logic_and" "assignment"]]
      (is (some #(str/starts-with? % rule) parser/grammar)
          (str "no production for " rule))))

  (testing "`statement` documents all five of its chapter 9 clauses"
    (let [text (str/join " " parser/statement-grammar)]
      (doseq [clause ["exprStmt" "forStmt" "ifStmt" "printStmt" "whileStmt"
                      "block"]]
        (is (str/includes? text clause)))))

  (testing "every declared field kind is one define-ast can check"
    (doseq [[node-type fields] (merge ast/expr-types ast/stmt-types)
            [kind field] fields]
      (is (contains? ast/field-kinds kind)
          (str node-type "/" field " has unknown kind " kind)))))

(deftest chapter-9-constructors-check-their-fields
  (testing "well-formed nodes"
    (is (= {:node :logical :left (num-lit 1) :operator or-token
            :right (num-lit 2)}
           (ast/logical (num-lit 1) or-token (num-lit 2))))
    (is (= {:node :while :condition (num-lit 1)
            :body (ast/print-stmt (num-lit 2))}
           (ast/while-stmt (num-lit 1) (ast/print-stmt (num-lit 2)))))
    (is (= {:node :if :condition (num-lit 1)
            :then-branch (ast/print-stmt (num-lit 2)) :else-branch nil}
           (ast/if-stmt (num-lit 1) (ast/print-stmt (num-lit 2)) nil))))

  (testing "an absent else branch is allowed; a non-statement is not"
    (is (nil? (:else-branch (ast/if-stmt (num-lit 1)
                                         (ast/print-stmt (num-lit 1)) nil))))
    (is (thrown? IllegalArgumentException
                 (ast/if-stmt (num-lit 1) (ast/print-stmt (num-lit 1))
                              (num-lit 2)))))

  (testing "the book's point about separate families: a condition must be an
            expression and a body must be a statement, and swapping them is
            caught where the node is built"
    (is (thrown? IllegalArgumentException
                 (ast/while-stmt (ast/print-stmt (num-lit 1)) (num-lit 1))))
    (is (thrown? IllegalArgumentException
                 (ast/if-stmt (ast/print-stmt (num-lit 1))
                              (ast/print-stmt (num-lit 1)) nil)))
    (is (thrown? IllegalArgumentException
                 (ast/logical (num-lit 1) or-token (ast/print-stmt (num-lit 1)))))
    (is (thrown? IllegalArgumentException
                 (ast/logical (num-lit 1) (num-lit 1) (num-lit 2)))))

  (testing "challenge 9.3's break node carries its token and nothing else"
    (is (= {:node :break :keyword brk-token} (ast/break-stmt brk-token)))
    (is (thrown? IllegalArgumentException (ast/break-stmt "break")))))

(deftest chapter-9-nodes-participate-in-generic-traversal
  (testing "`children` walks the new nodes without knowing what they are"
    (let [then (ast/print-stmt (num-lit 1))
          els  (ast/print-stmt (num-lit 2))
          cond (num-lit 3)]
      (is (= [cond then els] (ast/children (ast/if-stmt cond then els))))
      ;; an absent else branch contributes nothing, exactly as an absent
      ;; var initializer does in chapter 8
      (is (= [cond then] (ast/children (ast/if-stmt cond then nil))))
      (is (= [cond then] (ast/children (ast/while-stmt cond then))))))

  (testing "`:stmt-or-nil` is traversed as a child field kind"
    (is (contains? ast/child-field-kinds :stmt-or-nil)))

  (testing "a whole desugared for loop is one traversable tree"
    (let [tree (first (u/parse-program "for (var i = 0; i < 1; i = i + 1) print i;"))]
      (is (ast/block-stmt? tree))
      ;; block -> [var, while] ; while -> [condition, block]
      (is (= 2 (count (ast/children tree))))
      (is (ast/while-stmt? (second (ast/children tree)))))))

(deftest chapter-9-visitors-are-complete
  (testing "every real visitor handles every new node type"
    (doseq [[label v] [["interpreter" interp/interpreter]
                       ["ast printer" printer/printer]
                       ["rpn printer" printer/rpn]]]
      (is (contains? v :logical) (str label " must handle :logical")))
    (doseq [[label v] [["executor" interp/executor]
                       ["stmt printer" printer/stmt-printer]]]
      (doseq [nt [:if :while :break]]
        (is (contains? v nt) (str label " must handle " nt)))))

  (testing "defvisitor still refuses an incomplete expression visitor, and
            :logical is among what it demands"
    (let [e (expansion-failure '(lox.ast/defvisitor incomplete-9
                                  (:binary [e] 1) (:grouping [e] 1)
                                  (:literal [e] 1) (:unary [e] 1)
                                  (:conditional [e] 1)
                                  (:variable [e] 1) (:assign [e] 1)))]
      (is (some? e) "expansion should have thrown")
      (is (= [:logical] (:missing e)))))

  (testing "and an incomplete statement visitor"
    (let [e (expansion-failure '(lox.ast/defvisitor incomplete-stmt-9 :stmt
                                  (:block [s] 1) (:expression [s] 1)
                                  (:print [s] 1) (:var [s] 1)))]
      (is (some? e) "expansion should have thrown")
      (is (= #{:if :while :break} (set (:missing e)))))))

;;; ===========================================================================
;;; 2. Section 9.2 - the `if` statement
;;; ===========================================================================

(deftest if-syntax
  (testing "the grammar: a condition in parentheses, then one statement"
    (is (= "(if true (print 1.0))"       (u/program-str "if (true) print 1;")))
    (is (= "(if true (print 1.0) (print 2.0))"
           (u/program-str "if (true) print 1; else print 2;"))))

  (testing "the body is a single statement, and a block is one statement"
    (is (= "(if a (block (print 1.0) (print 2.0)))"
           (u/program-str "if (a) { print 1; print 2; }"))))

  (testing "an absent else is a missing node, not an empty block - so it
            prints with two elements rather than three"
    (let [[no-else with-empty] [(first (u/parse-program "if (a) b;"))
                                (first (u/parse-program "if (a) b; else {}"))]]
      (is (nil? (:else-branch no-else)))
      (is (some? (:else-branch with-empty)))
      (is (= "(if a (; b))"            (printer/print-statement no-else)))
      (is (= "(if a (; b) (block))"    (printer/print-statement with-empty)))))

  (testing "the condition is a full expression, so it picks up every
            precedence level below it"
    (is (= "(if (or (> a 1.0) b) (print 1.0))"
           (u/program-str "if (a > 1 or b) print 1;")))
    (is (= "(if (= a 1.0) (print 1.0))"
           (u/program-str "if (a = 1) print 1;")))))

(deftest if-rejects-a-declaration-as-its-branch
  ;; Chapter 8's declaration/statement split, finally doing something: there
  ;; would be no answer to what scope `b` lives in, so the grammar does not
  ;; allow the question to be asked.
  (testing "`if (a) var b = 1;` does not parse"
    (is (= "Expect expression." (u/program-error-message "if (a) var b = 1;")))
    (is (true? (u/had-error?))))

  (testing "a block makes it legal, because a block *is* a statement"
    (is (= ["1"] (u/run-fresh "if (true) { var b = 1; print b; }")))))

(deftest dangling-else-binds-to-the-nearest-if
  ;; The book's riddle: `if (first) if (second) whenTrue(); else whenFalse();`
  ;; Which `if` owns the `else`? Every C-family language answers "the inner
  ;; one", and this parser does so without a line of code saying as much.
  (testing "the tree shows the else attached to the inner if"
    ;; (The printer renders a string literal as its bare characters, so the
    ;; Lox string "true" prints as `true` here - chapter 5's convention.)
    (is (= "(if first (if second (print true) (print false)))"
           (u/program-str
            "if (first) if (second) print \"true\"; else print \"false\";"))))

  (testing "and the behaviour follows: a falsey outer condition prints nothing"
    ;; If the else belonged to the *outer* if, this would print "false".
    (is (= [] (u/run-fresh
               "var first = false; var second = false;
                if (first) if (second) print \"true\"; else print \"false\";")))
    (is (= ["false"] (u/run-fresh
                      "var first = true; var second = false;
                       if (first) if (second) print \"t\"; else print \"false\";")))
    (is (= ["true"] (u/run-fresh
                     "var first = true; var second = true;
                      if (first) if (second) print \"true\"; else print \"f\";"))))

  (testing "braces are how you attach it to the outer if instead"
    (is (= ["false"] (u/run-fresh
                      "var first = false; var second = false;
                       if (first) { if (second) print \"t\"; } else print \"false\";")))))

(deftest if-execution
  (testing "the truthy branch runs and the other does not"
    (is (= ["then"] (u/run-fresh "if (true) print \"then\"; else print \"else\";")))
    (is (= ["else"] (u/run-fresh "if (false) print \"then\"; else print \"else\";"))))

  (testing "no else clause and a falsey condition means nothing happens"
    (is (= [] (u/run-fresh "if (false) print \"no\";")))
    (is (= ["yes"] (u/run-fresh "if (true) print \"yes\";"))))

  (testing "chapter 7's truthiness rules decide, not Java's: only nil and
            false are falsey, so 0 and \"\" take the then branch"
    (are [src expected] (= expected (u/run-fresh src))
      "if (nil) print 1; else print 2;"     ["2"]
      "if (false) print 1; else print 2;"   ["2"]
      "if (true) print 1; else print 2;"    ["1"]
      "if (0) print 1; else print 2;"       ["1"]
      "if (\"\") print 1; else print 2;"    ["1"]
      "if (\"s\") print 1; else print 2;"   ["1"]
      "if (1 + 1) print 1; else print 2;"   ["1"]))

  (testing "THE UNTAKEN BRANCH IS NOT EVALUATED - the book's point that
            \"the choice not to evaluate it becomes user visible\". A side
            effect in the dead branch must not happen."
    (is (= ["taken"] (u/run-fresh
                      "if (true) print \"taken\"; else print \"dead\";")))
    ;; and a *runtime error* in the dead branch must not fire either
    (is (= ["ok"] (u/run-fresh "if (true) print \"ok\"; else print 1 + nil;")))
    (is (false? (u/had-runtime-error?))))

  (testing "a runtime error in the *taken* branch still fires"
    (is (str/includes? (or (u/program-error-message "if (true) print 1 + nil;") "")
                       "Operands must be")))

  (testing "the branches see the enclosing scope and can assign to it"
    (is (= ["99"] (u/run-fresh "var a = 1; if (true) a = 99; print a;"))))

  (testing "a block branch opens a scope, so a declaration inside it is local"
    (is (= ["1" "nil"]
           (u/run-fresh "var a; if (true) { var a = 1; print a; } print a;")))))

(deftest if-conditions-are-evaluated-once
  ;; Not a book claim, but a real risk: an implementation that evaluated the
  ;; condition twice (once to test, once to branch) would double every side
  ;; effect in it. Assignment is an expression in Lox, so we can detect that.
  (is (= ["1" "1"] (u/run-fresh "var n = 0; if (n = n + 1) print n; print n;"))))

;;; ===========================================================================
;;; 3. Section 9.3 - the logical operators
;;; ===========================================================================

(deftest logical-operator-syntax
  (testing "they build :logical nodes, not :binary ones"
    (let [e (u/parse "a or b")]
      (is (ast/logical? e))
      (is (not (ast/binary? e)))
      (is (= :or (:type (:operator e)))))
    (is (ast/logical? (u/parse "a and b"))))

  (testing "left-associative, like every other binary series"
    (is (= "(or (or a b) c)"   (u/parse-str "a or b or c")))
    (is (= "(and (and a b) c)" (u/parse-str "a and b and c"))))

  (testing "`or` is looser than `and` (the book: \"or lower than and\")"
    (is (= "(or a (and b c))" (u/parse-str "a or b and c")))
    (is (= "(or (and a b) c)" (u/parse-str "a and b or c"))))

  (testing "both are looser than equality, which is looser than comparison"
    (is (= "(or (== a b) (== c d))" (u/parse-str "a == b or c == d")))
    (is (= "(and (< a b) (> c d))"  (u/parse-str "a < b and c > d")))
    (is (= "(or (+ a b) c)"         (u/parse-str "a + b or c"))))

  (testing "and both are tighter than assignment, so `a = b or c` assigns
            the whole disjunction"
    (is (= "(= a (or b c))" (u/parse-str "a = b or c")))
    (is (= "(= a (and b c))" (u/parse-str "a = b and c"))))

  (testing "the declared precedence table agrees with the parser"
    (let [idx (zipmap parser/precedence-order (range))]
      (is (< (idx :assignment) (idx :logic-or)))
      (is (< (idx :logic-or) (idx :logic-and)))
      (is (< (idx :logic-and) (idx :equality)))))

  (testing "grouping overrides it, as always"
    (is (= "(and (group (or a b)) c)" (u/parse-str "(a or b) and c")))))

(deftest logical-operators-return-an-operand-not-a-boolean
  ;; "Instead of promising to literally return true or false, a logic operator
  ;;  merely guarantees it will return a value with appropriate truthiness."
  (testing "the book's two worked examples"
    (is (= ["hi"]  (u/run-fresh "print \"hi\" or 2;")))
    (is (= ["yes"] (u/run-fresh "print nil or \"yes\";"))))

  (testing "`or` yields the left operand when it is truthy, else the right"
    (are [src expected] (= expected (u/eval-str src))
      "1 or 2"         "1"
      "nil or 2"       "2"
      "false or 2"     "2"
      "false or nil"   "nil"
      "nil or false"   "false"
      "0 or 2"         "0"          ; 0 is truthy in Lox
      "\"\" or 2"      ""))         ; so is the empty string

  (testing "`and` yields the left operand when it is falsey, else the right"
    (are [src expected] (= expected (u/eval-str src))
      "1 and 2"        "2"
      "nil and 2"      "nil"
      "false and 2"    "false"
      "1 and nil"      "nil"
      "0 and 2"        "2"
      "\"a\" and \"b\"" "b"))

  (testing "so they are usable as the default-value idiom"
    (is (= ["fallback"] (u/run-fresh "var a; print a or \"fallback\";")))
    (is (= ["set"]      (u/run-fresh "var a = \"set\"; print a or \"fallback\";"))))

  (testing "a chain yields the first truthy (or) / first falsey (and) operand"
    (is (= "3"   (u/eval-str "nil or false or 3 or 4")))
    (is (= "nil" (u/eval-str "1 and 2 and nil and 4")))))

(deftest logical-operators-short-circuit
  ;; The heart of the section, and the reason these two are control flow. Each
  ;; assertion below is written with a *side effect* in the right operand,
  ;; because a value-only test would pass against an eager implementation too.
  (testing "`or` does not evaluate its right operand when the left is truthy"
    ;; `n = n + 1` is an expression, so an evaluated right operand is visible.
    (is (= ["0"] (u/run-fresh "var n = 0; true or (n = n + 1); print n;")))
    (is (= ["1"] (u/run-fresh "var n = 0; false or (n = n + 1); print n;"))))

  (testing "`and` does not evaluate its right operand when the left is falsey"
    (is (= ["0"] (u/run-fresh "var n = 0; false and (n = n + 1); print n;")))
    (is (= ["1"] (u/run-fresh "var n = 0; true and (n = n + 1); print n;"))))

  (testing "a RUNTIME ERROR in a short-circuited operand never fires - the
            strongest available evidence that it was not evaluated"
    (is (= ["false"] (u/run-fresh "print false and 1 < \"two\";")))
    (is (false? (u/had-runtime-error?)))
    (is (= ["true"] (u/run-fresh "print true or 1 + nil;")))
    (is (false? (u/had-runtime-error?))))

  (testing "and it does fire when the operand *is* reached, so the test above
            is not passing for the wrong reason"
    (is (str/includes? (or (u/program-error-message "print true and 1 + nil;") "")
                       "Operands must be"))
    (is (str/includes? (or (u/program-error-message "print false or 1 + nil;") "")
                       "Operands must be")))

  (testing "the book's `false and sideEffect();` example, in Lox-of-chapter-9"
    (is (= [] (u/run-fresh "false and (0 or print_marker);")))
    (is (false? (u/had-runtime-error?))
        "the undefined variable in the dead operand is never looked up"))

  (testing "the left operand is always evaluated, exactly once"
    (is (= ["1" "false"] (u/run-fresh
                          "var n = 0; var r = (n = n + 1) and false;
                           print n; print r;")))
    (is (= ["1" "1"] (u/run-fresh
                      "var n = 0; var r = (n = n + 1) or 99;
                       print n; print r;"))))

  (testing "short-circuiting composes through a chain: nothing after the
            first truthy `or` operand runs"
    (is (= ["0" "0"] (u/run-fresh
                      "var a = 0; var b = 0;
                       true or (a = 1) or (b = 1); print a; print b;")))))

(deftest logical-operators-print-and-round-trip
  (testing "the AST printer uses the operator's own lexeme"
    (is (= "(or a b)"  (u/parse-str "a or b")))
    (is (= "(and a b)" (u/parse-str "a and b"))))

  (testing "the RPN printer treats them as postfix binaries"
    (is (= "a b or"  (printer/print-rpn (u/parse "a or b"))))
    (is (= "a b and" (printer/print-rpn (u/parse "a and b"))))
    (is (= "a b or c and" (printer/print-rpn (u/parse "(a or b) and c")))))

  (testing "the hand-built node evaluates like the parsed one"
    (is (= 1.0 (interp/evaluate (ast/logical (num-lit 1) or-token (num-lit 2)))))
    (is (= 2.0 (interp/evaluate (ast/logical (num-lit 1) and-token (num-lit 2)))))))

(deftest logical-operators-are-not-the-chapter-7-binaries
  ;; A `:binary` node with an `or` token would be a different thing entirely:
  ;; `eval-binary` has no case for it. This pins down that the parser never
  ;; produces one and that the two code paths really are separate.
  (testing "no :binary node is ever built for `or`/`and`"
    (doseq [src ["a or b" "a and b" "a or b and c" "a == b and c"]]
      (let [printed (pr-str (u/parse src))]
        (is (not (str/includes? printed ":operator {:type :or, :node :binary")))))
    (is (ast/logical? (u/parse "a or b"))))

  (testing "`binary-levels` - the table of operators that evaluate both
            operands - deliberately does not list them, which is what keeps
            challenge 6.3's error productions from claiming them"
    (let [ops (set (mapcat :operators parser/binary-levels))]
      (is (not (contains? ops :or)))
      (is (not (contains? ops :and))))
    (is (= [:or :and] (mapv :token parser/logical-levels)))))

;;; ===========================================================================
;;; 4. Section 9.4 - `while`
;;; ===========================================================================

(deftest while-syntax
  (testing "the grammar: a parenthesised condition and one body statement"
    (is (= "(while a (print 1.0))"         (u/program-str "while (a) print 1;")))
    (is (= "(while a (block (print 1.0)))" (u/program-str "while (a) { print 1; }"))))

  (testing "the condition is a full expression"
    (is (= "(while (< i 10.0) (print i))" (u/program-str "while (i < 10) print i;")))
    (is (= "(while (and a b) (print 1.0))" (u/program-str "while (a and b) print 1;"))))

  (testing "a declaration is not a legal body, for the same reason as `if`"
    (is (= "Expect expression." (u/program-error-message "while (a) var b = 1;")))))

(deftest while-execution
  (testing "it loops until the condition goes falsey"
    (is (= ["0" "1" "2"]
           (u/run-fresh "var i = 0; while (i < 3) { print i; i = i + 1; }"))))

  (testing "a condition that is false at the start runs the body zero times"
    (is (= [] (u/run-fresh "while (false) print \"never\";")))
    (is (= [] (u/run-fresh "var i = 5; while (i < 3) print i;"))))

  (testing "truthiness decides, so a nil condition exits immediately"
    (is (= [] (u/run-fresh "var a; while (a) print 1;")))
    (is (= ["1"] (u/run-fresh "var a = 1; while (a) { print a; a = nil; }"))))

  (testing "the condition is re-evaluated every iteration - which is the only
            reason a loop can ever terminate"
    (is (= ["3"] (u/run-fresh
                  "var n = 0; while (n < 3) n = n + 1; print n;"))))

  (testing "a body block gets a fresh scope per iteration, so a declaration
            inside it does not collide with itself"
    (is (= ["0" "0" "0"]
           (u/run-fresh "var i = 0;
                         while (i < 3) { var x = 0; print x; i = i + 1; }"))))

  (testing "a runtime error inside the body stops the loop and is reported"
    (let [[lines errors] (u/run-program-and-errors
                          "var i = 0; while (i < 3) { print i; i = i + nil; }")]
      (is (= ["0"] lines))
      (is (str/includes? (first errors) "Operands must be"))))

  (testing "the loop runs in the enclosing scope, so it can see and write it"
    (is (= ["6"] (u/run-fresh
                  "var sum = 0; var i = 1;
                   while (i <= 3) { sum = sum + i; i = i + 1; }
                   print sum;")))))

(deftest while-is-not-stack-bounded
  ;; `clojure.core/while` is a loop/recur, so iterations do not consume stack.
  ;; A Lox loop is therefore bounded by time, not by the JVM stack - which is
  ;; what makes the Turing-completeness claim of section 9.1 real rather than
  ;; nominal. 100000 iterations would overflow a recursive implementation
  ;; long before finishing.
  (testing "a hundred thousand iterations complete"
    (is (= ["100000"]
           (u/run-fresh "var i = 0; while (i < 100000) i = i + 1; print i;")))))

;;; ===========================================================================
;;; 5. Sections 9.5 / 9.5.1 - `for`, and its desugaring
;;; ===========================================================================

(deftest for-loop-desugars-into-existing-nodes
  ;; The claim of section 9.5.1, and the only way to test it is to look at the
  ;; tree: a correctly desugared `for` and a hypothetical native one would
  ;; behave identically.
  (testing "the book's own example desugars to its own hand-written equivalent"
    ;;   for (var i = 0; i < 10; i = i + 1) print i;
    ;; becomes
    ;;   { var i = 0; while (i < 10) { print i; i = i + 1; } }
    (is (= (u/program-str "{ var i = 0; while (i < 10) { print i; i = i + 1; } }")
           (u/program-str "for (var i = 0; i < 10; i = i + 1) print i;"))))

  (testing "the exact shape, spelled out"
    (is (= (str "(block (var i 0.0) "
                "(while (< i 10.0) (block (print i) (; (= i (+ i 1.0))))))")
           (u/program-str "for (var i = 0; i < 10; i = i + 1) print i;"))))

  (testing "no :for node is produced anywhere in the tree"
    (let [printed (pr-str (u/parse-program
                           "for (var i = 0; i < 2; i = i + 1) print i;"))]
      (is (not (str/includes? printed ":for")))))

  (testing "each clause's wrapping is skipped when the clause is omitted, so
            the tree holds what the source said and nothing more"
    (are [src expected] (= expected (u/program-str src))
      ;; all three omitted: a bare infinite while, no blocks at all
      "for (;;) print 1;"
      "(while true (print 1.0))"

      ;; condition only
      "for (; a ;) print 1;"
      "(while a (print 1.0))"

      ;; initializer only
      "for (var i = 0;;) print 1;"
      "(block (var i 0.0) (while true (print 1.0)))"

      ;; increment only
      "for (;; a = a + 1) print 1;"
      "(while true (block (print 1.0) (; (= a (+ a 1.0)))))"

      ;; an expression initializer is wrapped in an expression statement
      "for (i = 0; i < 1; ) print 1;"
      "(block (; (= i 0.0)) (while (< i 1.0) (print 1.0)))"))

  (testing "an omitted condition becomes the literal `true`"
    (let [while-node (first (u/parse-program "for (;;) print 1;"))]
      (is (ast/while-stmt? while-node))
      (is (= (ast/literal true) (:condition while-node)))))

  (testing "the increment goes *after* the body, in a block with it"
    (let [body (:body (first (u/parse-program "for (;; i = i + 1) print 1;")))]
      (is (ast/block-stmt? body))
      (is (= 2 (count (:statements body))))
      (is (ast/print-stmt? (first (:statements body))))
      (is (ast/expression-stmt? (second (:statements body)))))))

(deftest for-loop-execution
  (testing "the canonical counted loop"
    (is (= ["0" "1" "2"]
           (u/run-fresh "for (var i = 0; i < 3; i = i + 1) print i;"))))

  (testing "a block body"
    (is (= ["0" "a" "1" "a"]
           (u/run-fresh "for (var i = 0; i < 2; i = i + 1) { print i; print \"a\"; }"))))

  (testing "clause execution order: initializer once, then condition before
            every iteration including the first, then body, then increment"
    ;; `print` is a statement in Lox, not an expression, so the clauses cannot
    ;; print directly. Assignment *is* an expression, so each clause appends a
    ;; letter to a log string and the finished log states the order:
    ;;   I nitializer, C ondition, B ody, N increment.
    (is (= ["CBNCBNC"]
           (u/run-fresh
            "var log = \"\";
             for (var i = 0; (log = log + \"C\") and i < 2; log = log + \"N\") {
               log = log + \"B\";
               i = i + 1;
             }
             print log;"))
        "condition before every iteration - so three C's for two B's - and
         the increment after each body, never after the final check")
    (is (= ["I"] (u/run-fresh
                  "var log = \"\";
                   for (var i = (log = log + \"I\"); false; log = log + \"N\")
                     log = log + \"B\";
                   print log;"))
        "a condition false at the start: initializer ran, body and increment did not"))

  (testing "a condition false at the start runs the body zero times, but the
            initializer still runs"
    (is (= ["1"] (u/run-fresh
                  "var seen = 0;
                   for (var i = (seen = 1); false; ) print \"never\";
                   print seen;"))))

  (testing "the loop variable is scoped to the loop - the outer block the
            desugaring adds is what makes it invisible afterwards"
    (is (str/includes?
         (or (u/program-error-message
              "for (var i = 0; i < 1; i = i + 1) print i; print i;") "")
         "Undefined variable 'i'")))

  (testing "but a variable declared outside is shared, and survives"
    (is (= ["0" "1" "2"]
           (u/run-fresh "var i; for (i = 0; i < 2; i = i + 1) print i; print i;"))))

  (testing "an outer `i` is shadowed, not overwritten, by a `for (var i ...)`"
    (is (= ["0" "1" "99"]
           (u/run-fresh "var i = 99;
                         for (var i = 0; i < 2; i = i + 1) print i;
                         print i;"))))

  (testing "nested for loops run the inner one to completion each time round
            the outer one"
    ;; `i * 10 + j` rather than string concatenation, because Lox's `+` does
    ;; not coerce a number to a string unless challenge 7.2 is switched on.
    (is (= ["0" "1" "10" "11"]
           (u/run-fresh
            "for (var i = 0; i < 2; i = i + 1)
               for (var j = 0; j < 2; j = j + 1)
                 print i * 10 + j;")))
    (is (= ["0" "1" "2" "0" "1" "2"]
           (u/run-fresh
            "for (var i = 0; i < 2; i = i + 1)
               for (var j = 0; j < 3; j = j + 1)
                 print j;"))
        "the inner loop variable restarts, because its scope is per-entry"))

  (testing "a runtime error in a clause stops the loop and is reported"
    (let [[lines errors] (u/run-program-and-errors
                          "for (var i = 0; i < 3; i = i + nil) print i;")]
      (is (= ["0"] lines))
      (is (str/includes? (first errors) "Operands must be")))))

(deftest for-loop-does-not-need-interpreter-support
  ;; The structural claim of the desugaring: the back end contains no mention
  ;; of `for` at all. If this ever fails, something has quietly special-cased
  ;; it and the desugaring is no longer load-bearing.
  (testing "the statement visitor has no :for method, and does not need one"
    (is (not (contains? interp/executor :for)))
    (is (= #{:block :expression :print :var :if :while :break}
           (set (keys interp/executor)))))

  (testing "the interpreter source mentions `for` only in prose"
    (is (not (contains? ast/stmt-node-types :for)))))

;;; ===========================================================================
;;; 6. Section 9.1 - what the chapter actually buys: Turing-completeness
;;; ===========================================================================

(deftest programs-chapter-8-could-not-express
  (testing "the chapter's own closing example: the Fibonacci numbers below
            10000, from a program whose length does not grow with its output"
    (is (= ["0" "1" "1" "2" "3" "5" "8" "13" "21" "34" "55" "89" "144" "233"
            "377" "610" "987" "1597" "2584" "4181" "6765"]
           (u/run-fresh
            "var a = 0;
             var temp;
             for (var b = 1; a < 10000; b = temp + b) {
               print a;
               temp = a;
               a = b;
             }"))))

  (testing "work unbounded by source length - 500 iterations from 3 lines"
    (is (= ["125250"]
           (u/run-fresh "var sum = 0;
                         for (var i = 1; i <= 500; i = i + 1) sum = sum + i;
                         print sum;"))))

  (testing "a loop whose trip count is computed at run time"
    (is (= ["10"] (u/run-fresh
                   "var n = 2 * 5; var c = 0;
                    while (c < n) c = c + 1;
                    print c;"))))

  (testing "conditional logic inside a loop - FizzBuzz, the standard proof
            that branching and looping compose"
    ;; Lox has only floating-point division and no modulo, so divisibility is
    ;; tracked with two counters that are reset as they reach 3 and 5. That is
    ;; itself a small demonstration of the chapter's point: with branching and
    ;; looping, a missing operator stops being a missing *capability*.
    (is (= ["1" "2" "Fizz" "4" "Buzz" "Fizz" "7" "8" "Fizz" "Buzz"
            "11" "Fizz" "13" "14" "FizzBuzz"]
           (u/run-fresh
            "var c3 = 0;
             var c5 = 0;
             for (var i = 1; i <= 15; i = i + 1) {
               c3 = c3 + 1;
               c5 = c5 + 1;
               var fizz = c3 == 3;
               var buzz = c5 == 5;
               if (fizz) c3 = 0;
               if (buzz) c5 = 0;
               if (fizz and buzz) print \"FizzBuzz\";
               else if (fizz) print \"Fizz\";
               else if (buzz) print \"Buzz\";
               else print i;
             }"))))

  (testing "`else if` chains work, which is what the dangling-else rule and
            `if` taking a single statement together make possible"
    (are [n expected] (= [expected]
                         (u/run-fresh
                          (str "var n = " n ";"
                               "if (n < 0) print \"neg\";"
                               "else if (n == 0) print \"zero\";"
                               "else print \"pos\";")))
      -5 "neg"
      0  "zero"
      7  "pos"))

  (testing "a nested loop doing real work: a multiplication table row sum"
    (is (= ["36"] (u/run-fresh
                   "var total = 0;
                    for (var i = 1; i <= 3; i = i + 1)
                      for (var j = 1; j <= 3; j = j + 1)
                        total = total + i * j;
                    print total;")))))

;;; ===========================================================================
;;; 7. Errors - every new message, by exact text
;;; ===========================================================================

(deftest chapter-9-syntax-error-messages
  (testing "`if` wants its parentheses"
    (are [src msg] (= msg (u/program-error-message src))
      "if true) print 1;"   "Expect '(' after 'if'."
      "if (true print 1;"   "Expect ')' after if condition."))

  (testing "`while` wants its parentheses - and note the message differs from
            `if`'s, which is exactly the kind of near-duplicate that is easy
            to swap by accident"
    (are [src msg] (= msg (u/program-error-message src))
      "while true) print 1;" "Expect '(' after 'while'."
      "while (true print 1;" "Expect ')' after condition."))

  (testing "`for` wants its parenthesis, both semicolons and its close"
    (are [src msg] (= msg (u/program-error-message src))
      "for var i = 0;;) print 1;"      "Expect '(' after 'for'."
      "for (var i = 0; i < 1) print 1;" "Expect ';' after loop condition."
      "for (;; i = i + 1 print 1;"     "Expect ')' after for clauses."))

  (testing "`for (;)` fails in the condition position, not at the semicolon"
    ;; The first `;` is consumed as the omitted initializer. The parser is then
    ;; at the condition, and `)` cannot begin an expression - so the message is
    ;; the generic one rather than "Expect ';' after loop condition.". Worth
    ;; pinning down, because the latter is the one a reader would guess.
    (is (= "Expect expression." (u/program-error-message "for (;) print 1;")))
    (is (= "Expect ';' after loop condition."
           (u/program-error-message "for (; a) print 1;"))))

  (testing "a missing body is an error at EOF, not a crash"
    (is (some? (u/program-error-message "if (true)")))
    (is (some? (u/program-error-message "while (true)")))
    (is (some? (u/program-error-message "for (;;)"))))

  (testing "a stray `else` with no `if` is an ordinary expression error"
    (is (= "Expect expression." (u/program-error-message "else print 1;"))))

  (testing "nothing runs when a program has a syntax error"
    (is (= [] (u/run-fresh "print \"before\"; if (true print 1;")))))

(deftest chapter-9-errors-synchronize
  ;; Panic mode has been live since chapter 8; this checks the new statement
  ;; keywords are synchronization points too, so one bad `if` costs one
  ;; statement rather than the rest of the file.
  (testing "two unrelated errors in one file are both reported"
    (let [msgs (u/program-error-messages
                "if (true print 1; while (true print 2;")]
      (is (= 2 (count msgs)))
      (is (= ["Expect ')' after if condition."
              "Expect ')' after condition."] msgs))))

  (testing "a good statement after a bad one still parses"
    (let [msgs (u/program-error-messages "if (true print 1; print 2;")]
      (is (= 1 (count msgs)))))

  (testing "`for`, `if` and `while` are in the synchronization keyword set"
    (doseq [kw [:for :if :while :print :var :return :class :fun]]
      (is (contains? parser/synchronization-keywords kw)
          (str kw " should be a synchronization point"))))

  (testing "parsing terminates on pathological control-flow input"
    (doseq [src ["if" "if (" "if (a" "while (" "for (" "for (;" "for (;;"
                 "if (a) else" "while" "for" "if (a) {" "while (a) {"]]
      (is (vector? (u/parse-program src)) (str "did not terminate on: " src)))))

;;; ===========================================================================
;;; 8. Challenge 9.3 - `break`
;;; ===========================================================================

(deftest challenge-9-3-default-is-the-books-lox
  ;; The switch is off by default, and that is load-bearing: reserving a word
  ;; takes a name away from every program that used it.
  (testing "the switch is off"
    (is (false? tok/*allow-break?*)))

  (testing "with it off, `break` is an ordinary identifier"
    (is (= :identifier (:type (first (u/scan "break")))))
    (is (= ["1"] (u/run-fresh "var break = 1; print break;")))
    (is (= "break" (u/parse-str "break"))))

  (testing "and `break;` is therefore just an expression statement naming an
            undefined variable - a runtime error, not a syntax error"
    (is (str/includes? (or (u/program-error-message "break;") "")
                       "Undefined variable 'break'")))

  (testing "with it on, `break` scans as a keyword and is no longer a name"
    (with-break
      #(do (is (= :break (:type (first (u/scan "break")))))
           (is (some? (u/program-error-message "var break = 1;")))))))

(deftest challenge-9-3-break-syntax
  (with-break
    (fn []
      (testing "the production: a keyword and a semicolon"
        (is (= "(while true (block (break)))"
               (u/program-str "while (true) { break; }")))
        (is (= ["breakStmt   -> \"break\" \";\" ;"] parser/break-grammar)))

      (testing "the semicolon is required"
        (is (= "Expect ';' after 'break'."
               (u/program-error-message "while (true) { break }"))))

      (testing "the node carries the `break` token, for the line number"
        (let [node (-> (u/parse-program "while (true) break;") first :body)]
          (is (ast/break-stmt? node))
          (is (= "break" (:lexeme (:keyword node)))))))))

(deftest challenge-9-3-break-outside-a-loop-is-a-syntax-error
  (with-break
    (fn []
      (testing "at the top level"
        (is (= "Must be inside a loop to use 'break'." (break-error "break;"))))

      (testing "inside a bare block"
        (is (= "Must be inside a loop to use 'break'."
               (break-error "{ break; }"))))

      (testing "inside an `if` that is not in a loop"
        (is (= "Must be inside a loop to use 'break'."
               (break-error "if (true) break;"))))

      (testing "AFTER a loop has closed - the depth is restored, not left
                raised, which a mutable counter would be easy to get wrong"
        (is (= "Must be inside a loop to use 'break'."
               (break-error "while (true) break; break;")))
        (is (= "Must be inside a loop to use 'break'."
               (break-error "for (;;) break; break;"))))

      (testing "it is a *syntax* error, so nothing runs at all - not even the
                statements before it"
        (is (= [] (break-program "print \"before\"; break;")))
        (is (true? (u/had-error?))))

      (testing "the error names a line"
        (err/reset-errors!)
        (u/parse-program "print 1;\nprint 2;\nbreak;")
        (is (= "Must be inside a loop to use 'break'."
               (first (u/error-messages))))
        ;; reported against the `break` token's own line
        (binding [tok/*allow-break?* true]
          (err/reset-errors!)
          (u/parse-program "print 1;\nprint 2;\nbreak;")
          (is (str/includes? (or (first (u/errors)) "") "line 3"))))

      (testing "parsing continues after the error, so a later mistake is
                still reported in the same pass"
        (err/reset-errors!)
        (u/parse-program "break; if (true print 1;")
        (is (= 2 (count (u/error-messages))))))))

(deftest challenge-9-3-break-is-legal-wherever-a-loop-encloses-it
  (with-break
    (fn []
      (testing "directly in a while body, and in a for body"
        (is (empty? (u/program-error-messages "while (true) break;")))
        (is (empty? (u/program-error-messages "for (;;) break;"))))

      (testing "nested inside blocks and ifs, which \"also need to be exited\""
        (doseq [src ["while (true) { break; }"
                     "while (true) { { { break; } } }"
                     "while (true) if (true) break;"
                     "while (true) { if (true) { if (true) break; } }"
                     "for (;;) { { if (true) break; } }"]]
          (err/reset-errors!)
          (is (empty? (u/program-error-messages src)) (str "rejected: " src))))

      (testing "in an *outer* loop's body after an inner loop has closed -
                the depth is a count, not a flag"
        (is (empty? (u/program-error-messages
                     "while (true) { while (true) break; break; }"))))

      (testing "but not in a loop's *condition* or a for loop's clauses, since
                those are expressions and `break` is a statement"
        (is (some? (u/program-error-message "while (break) 1;")))))))

(deftest challenge-9-3-break-execution
  (with-break
    (fn []
      (testing "it exits the nearest enclosing loop"
        (is (= ["0" "1" "2"]
               (break-program
                "var i = 0;
                 while (true) { if (i > 2) break; print i; i = i + 1; }"))))

      (testing "an immediate break runs the body exactly once"
        (is (= ["once"] (break-program
                         "while (true) { print \"once\"; break; }"))))

      (testing "statements after the break in the same body do not run"
        (is (= ["before"] (break-program
                           "while (true) { print \"before\"; break;
                                           print \"after\"; }"))))

      (testing "it unwinds out of nested blocks and ifs"
        (is (= ["in"] (break-program
                       "while (true) { { if (true) { print \"in\"; break; } } }"))))

      (testing "and the statement after the loop does run"
        (is (= ["in" "after"]
               (break-program
                "while (true) { print \"in\"; break; } print \"after\";"))))

      (testing "it exits only the INNERMOST loop"
        (is (= ["0" "1"] (break-program
                          "for (var i = 0; i < 2; i = i + 1) {
                             while (true) break;
                             print i;
                           }")))
        (is (= ["a" "a"] (break-program
                          "for (var i = 0; i < 2; i = i + 1) {
                             for (var j = 0; j < 9; j = j + 1) {
                               print \"a\"; break;
                             }
                           }"))))

      (testing "in a `for`, it skips the increment - correct, since the loop
                is over - and the loop variable keeps its pre-break value"
        (is (= ["0" "0"]
               (break-program
                "var seen;
                 for (var i = 0; i < 9; i = i + 1) { print i; seen = i; break; }
                 print seen;"))))

      (testing "a block's scope is still discarded on the way out, so the
                environment is not left in a dead scope"
        (is (= ["1" "9"]
               (break-program
                "var x = 9;
                 while (true) { var x = 1; print x; break; }
                 print x;"))))

      (testing "the loop can be re-entered afterwards with a clean slate"
        (is (= ["1" "1"]
               (break-program
                "for (var n = 0; n < 2; n = n + 1) {
                   while (true) { print 1; break; }
                 }")))))))

(deftest challenge-9-3-break-signal-does-not-swallow-real-errors
  ;; The subtle bug the implementation note warns about: Lox runtime errors are
  ;; `ex-info` too, so a `catch ExceptionInfo` around a loop body that failed
  ;; to check the tag would silently exit the loop instead of reporting them.
  (with-break
    (fn []
      (testing "a runtime error inside a loop body is still reported"
        (let [[lines errors] (u/run-program-and-errors
                              "while (true) { print 1; print 1 + nil; break; }")]
          (is (= ["1"] lines))
          (is (str/includes? (or (first errors) "") "Operands must be"))))

      (testing "an undefined variable inside a loop body is still reported"
        (is (str/includes?
             (or (break-error "while (true) { print nope; break; }") "")
             "Undefined variable")))

      (testing "a runtime error in the loop *condition* is still reported"
        (is (str/includes?
             (or (break-error "while (1 + nil) break;") "")
             "Operands must be")))

      (testing "the signal is recognised by its tag, not by its class"
        (is (true? (interp/break-signal?
                    (ex-info "break" {:type :lox.interpreter/break}))))
        (is (false? (interp/break-signal? (ex-info "nope" {:type :other}))))
        (is (false? (interp/break-signal?
                     (err/runtime-error-ex brk-token "boom"))))))))

(deftest challenge-9-3-break-prints-and-traverses
  (testing "it prints as a bare atom"
    (is (= "(break)" (printer/print-statement (ast/break-stmt brk-token)))))

  (testing "it has no child nodes"
    (is (= [] (ast/children (ast/break-stmt brk-token)))))

  (testing "it belongs to the statement family"
    (is (= :stmt (ast/node-family (ast/break-stmt brk-token))))
    (is (ast/stmt? (ast/break-stmt brk-token)))
    (is (not (ast/expr? (ast/break-stmt brk-token))))))

;;; ===========================================================================
;;; 9. Interaction with the earlier chapters' challenge switches
;;; ===========================================================================

(deftest chapter-9-and-the-chapter-6-challenge-levels
  (testing "with challenge 6.2 on, `?:` is looser than `or` - C's order"
    (binding [parser/*allow-conditional?* true]
      (is (= "(?: (or a b) c d)" (u/parse-str "a or b ? c : d")))
      (is (= "(?: a (or b c) d)" (u/parse-str "a ? b or c : d")))
      ;; and still tighter than assignment
      (is (= "(= x (?: a b c))"  (u/parse-str "x = a ? b : c")))))

  (testing "with challenge 6.1 on, the comma operator stays loosest of all"
    (binding [parser/*allow-comma?* true]
      (is (= "(, (or a b) c)" (u/parse-str "a or b, c")))))

  (testing "both switches off - the default - and the chain is the book's"
    (is (false? parser/*allow-comma?*))
    (is (false? parser/*allow-conditional?*))
    (is (= ["expression -> assignment ;"
            "assignment -> IDENTIFIER \"=\" assignment | logic_or ;"
            "logic_or   -> logic_and ( \"or\" logic_and )* ;"
            "logic_and  -> equality ( \"and\" equality )* ;"]
           (vec (take 4 parser/grammar)))))

  (testing "challenge 6.3's error productions do not claim `or`/`and`, which
            would otherwise make a leading `or` report the wrong thing"
    (is (= "Expect expression." (u/program-error-message "or 1;")))
    (is (= "Expect expression." (u/program-error-message "and 1;"))))

  (testing "challenge 6.3 fires at a statement boundary, including before a
            control-flow keyword - but not *inside* a condition"
    ;; 6.3's error production is checked once per statement, in
    ;; `parse-declaration`, which is where a stray leading operator is a
    ;; recoverable mistake. Inside a condition the parser is mid-expression,
    ;; so the generic "Expect expression." is the right report and the
    ;; recovery is the enclosing statement's. Both halves are asserted so
    ;; that neither can drift into the other.
    (is (str/includes? (or (u/program-error-message "* 3;") "")
                       "requires a left-hand operand"))
    (is (= "Expect expression." (u/program-error-message "if (* 3) print 1;")))
    (is (= "Expect expression."
           (u/program-error-message "while (* 3) print 1;")))))

(deftest chapter-9-and-the-chapter-7-challenge-switches
  (testing "7.2's string-coercing plus works inside a loop"
    (binding [interp/*string-coercing-plus?* true]
      (is (= ["n1" "n2"] (u/run-fresh
                          "for (var i = 1; i < 3; i = i + 1) print \"n\" + i;")))))

  (testing "7.3's divide-by-zero rule, when on, applies inside a condition"
    ;; Off by default, so the default build happily compares Infinity.
    (is (nil? (u/program-error-message "if (1 / 0 > 0) print 1;")))
    (binding [interp/*error-on-division-by-zero?* true]
      (is (some? (u/program-error-message "if (1 / 0 > 0) print 1;")))
      (is (some? (u/program-error-message "while (1 / 0) print 1;"))))))

(deftest chapter-9-and-the-chapter-8-challenge-switches
  (testing "challenge 8.2 - an uninitialized variable read in a condition"
    (binding [env/*error-on-uninitialized?* true]
      (is (= "Variable 'a' is not initialized."
             (u/program-error-message "var a; if (a) print 1;")))
      (is (= "Variable 'a' is not initialized."
             (u/program-error-message "var a; while (a) print 1;")))))

  (testing "challenge 8.1 - the REPL classifies control flow as a statement,
            not an expression, so it is executed rather than echoed"
    (let [[_ lines] (u/capture-out
                     #(do (err/reset-errors!)
                          (interp/reset-globals!)
                          (core/run-repl-line "var i = 0;")
                          (core/run-repl-line "while (i < 2) { print i; i = i + 1; }")
                          (core/run-repl-line "i > 1 and \"done\"")))]
      ;; the loop printed 0 and 1; the bare expression was echoed
      (is (= ["0" "1" "done"] lines)))))

;;; ===========================================================================
;;; 10. Whole-chapter smoke tests - the pipeline end to end
;;; ===========================================================================

(deftest the-full-pipeline
  (testing "scan -> parse -> desugar -> execute, on one program using all four
            of the chapter's constructs"
    (is (= ["3" "2" "1" "liftoff"]
           (u/run-fresh
            "var n = 3;
             while (n > 0 and true) { print n; n = n - 1; }
             if (n == 0) print \"liftoff\"; else print \"stuck\";"))))

  (testing "tokens, tree and output for the same source all agree"
    (let [src "if (a or b) while (c) print 1;"]
      (is (= :if (:type (first (u/scan src)))))
      (is (= "(if (or a b) (while c (print 1.0)))" (u/program-str src)))
      (is (= 1 (count (u/parse-program src))))))

  (testing "a `for` loop's printed tree is the desugaring, and running it
            gives the behaviour the sugar promised"
    (let [src "for (var i = 0; i < 3; i = i + 1) print i;"]
      (is (str/starts-with? (u/program-str src) "(block (var i 0.0) (while"))
      (is (= ["0" "1" "2"] (u/run-fresh src))))))

(deftest earlier-chapters-stay-runnable
  (testing "chapter 4's token dump still works on chapter 9 source"
    (let [tokens (u/scan "if (a) while (b) break;")]
      (is (= [:if :left-paren :identifier :right-paren :while :left-paren
              :identifier :right-paren :identifier :semicolon :eof]
             (mapv :type tokens)))))

  (testing "chapter 6's single-expression entry point still parses one
            expression and stops"
    (is (= "(or a b)" (u/parse-str "a or b"))))

  (testing "chapter 7's expression evaluation is unchanged"
    (is (= "7" (u/eval-str "1 + 2 * 3"))))

  (testing "chapter 8's statements and scope are unchanged"
    (is (= ["espresso"]
           (u/run-fresh "var beverage = \"espresso\"; print beverage;")))
    (is (= ["inner" "outer"]
           (u/run-fresh "var a = \"outer\"; { var a = \"inner\"; print a; }
                         print a;")))))

(deftest the-example-script-runs
  (testing "examples/ch09_control_flow.lox parses and runs without error"
    (let [path "examples/ch09_control_flow.lox"
          src  (slurp path)]
      (err/reset-errors!)
      (interp/reset-globals!)
      (let [[_ lines] (u/capture-out #(u/interpret-program src))]
        (is (false? (u/had-error?)) "the example must be syntactically valid")
        (is (false? (u/had-runtime-error?)) "and must run clean")
        (is (seq lines) "and must print something")))))

(deftest exit-codes-are-unchanged
  (testing "a chapter 9 syntax error is still a static error (exit 65)"
    (err/reset-errors!)
    (u/parse-program "if (true print 1;")
    (is (true? (u/had-error?)))
    (is (false? (u/had-runtime-error?))))

  (testing "a chapter 9 runtime error is still a runtime error (exit 70)"
    (err/reset-errors!)
    (interp/reset-globals!)
    (u/capture-out #(u/interpret-program "while (1 + nil) print 1;"))
    (is (true? (u/had-runtime-error?)))))
