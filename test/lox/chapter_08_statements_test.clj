;;;; =============================================================================
;;;; Chapter 8 - Statements and State: unit tests
;;;;
;;;; The chapter adds four things, and the suite is organised around them:
;;;;
;;;;   1. statements            section 8.1  - exprStmt, printStmt, program
;;;;   2. global variables      section 8.2  - varDecl, IDENTIFIER
;;;;   3. environments          section 8.3  - the storage, on its own
;;;;   4. assignment            section 8.4  - the l-value trick
;;;;   5. scope                 section 8.5  - blocks, nesting, shadowing
;;;;   6. error recovery                     - panic mode, finally in use
;;;;   7. challenges 8.1 - 8.3
;;;;   8. whole-chapter smoke tests
;;;;
;;;; Three conventions carried over from the earlier chapters:
;;;;
;;;; * assertions are on *data*, not on stdout, except where the thing under
;;;;   test genuinely is what the user sees - and from this chapter that is
;;;;   most of it, because a statement's whole purpose is its side effect. So
;;;;   `run-program` returns the printed lines and is used heavily.
;;;;
;;;; * error *text* is asserted, not just the fact of an error. A wrong message
;;;;   is its own bug, and chapter 8 introduces several near-identical ones
;;;;   ("Expect ';' after value." vs "... after expression.") that would be
;;;;   easy to swap by accident.
;;;;
;;;; * both positions of every challenge switch are exercised, so the default
;;;;   (book) behaviour is pinned down as firmly as the extension.
;;;; =============================================================================
(ns lox.chapter-08-statements-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is are use-fixtures]]
            [lox.ast :as ast]
            [lox.ast-printer :as printer]
            [lox.core :as core]
            [lox.environment :as env]
            [lox.errors :as err]
            [lox.interpreter :as interp]
            [lox.parser :as parser]
            [lox.scanner :as scanner]
            [lox.test-util :as u]
            [lox.token :as tok]))

(use-fixtures :each u/quiet-errors u/fresh-globals)

;;; Hand-built tokens, for the tests that must not go through the parser.
(def ^:private a-token  (tok/make-token :identifier "a" nil 1))
(def ^:private b-token  (tok/make-token :identifier "b" nil 1))
(def ^:private a-token2 (tok/make-token :identifier "a" nil 7))
(def ^:private plus     (tok/make-token :plus "+" nil 1))

(defn- num-lit [n] (ast/literal (double n)))

;;; ===========================================================================
;;; 1. Section 8.1 - statements
;;; ===========================================================================

(deftest statement-grammar-is-documented-and-matches-the-book
  (testing "the four Stmt productions of sections 8.1.1, 8.2.1 and 8.5.2"
    ;; "Block      : List<Stmt> statements"
    ;; "Expression : Expr expression"
    ;; "Print      : Expr expression"
    ;; "Var        : Token name, Expr initializer"
    (is (= #{:block :expression :print :var} ast/stmt-node-types))
    (is (= '[[:stmts statements]]              (:block ast/stmt-types)))
    (is (= '[[:expr expression]]               (:expression ast/stmt-types)))
    (is (= '[[:expr expression]]               (:print ast/stmt-types)))
    (is (= '[[:token name] [:expr-or-nil initializer]]
           (:var ast/stmt-types))))

  (testing "expressions and statements are disjoint families (section 8.1.1)"
    (is (= :expr (ast/node-family (num-lit 1))))
    (is (= :stmt (ast/node-family (ast/print-stmt (num-lit 1)))))
    (is (empty? (set/intersection ast/expr-node-types
                                  ast/stmt-node-types))))

  (testing "every declared field kind is one define-ast can check"
    (doseq [[node-type fields] ast/stmt-types
            [kind field] fields]
      (is (contains? ast/field-kinds kind)
          (str node-type "/" field " has unknown kind " kind))))

  (testing "the documented statement grammar covers every rule it names"
    (doseq [rule ["program" "declaration" "varDecl" "statement"
                  "exprStmt" "printStmt" "block"]]
      (is (some #(str/starts-with? % rule) parser/statement-grammar)
          (str "no production for " rule)))))

(deftest statement-constructors-check-their-fields
  (testing "well-formed nodes"
    (is (= {:node :print :expression (num-lit 1)}
           (ast/print-stmt (num-lit 1))))
    (is (= {:node :expression :expression (num-lit 1)}
           (ast/expression-stmt (num-lit 1))))
    (is (= {:node :var :name a-token :initializer nil}
           (ast/var-stmt a-token nil)))
    (is (= {:node :block :statements []} (ast/block-stmt []))))

  (testing "a statement may not be used where an expression is required"
    ;; This is the mistake section 8.1.1 says javac catches for the book.
    (is (thrown? IllegalArgumentException
                 (ast/grouping (ast/print-stmt (num-lit 1)))))
    (is (thrown? IllegalArgumentException
                 (ast/print-stmt (ast/print-stmt (num-lit 1))))))

  (testing "an expression may not be used where a statement is required"
    (is (thrown? IllegalArgumentException (ast/block-stmt [(num-lit 1)])))
    (is (thrown? IllegalArgumentException (ast/block-stmt (num-lit 1)))))

  (testing ":expr-or-nil accepts a node or nothing, and nothing else"
    (is (nil? (:initializer (ast/var-stmt a-token nil))))
    (is (= (num-lit 1) (:initializer (ast/var-stmt a-token (num-lit 1)))))
    (is (thrown? IllegalArgumentException (ast/var-stmt a-token 1.0)))
    (is (thrown? IllegalArgumentException
                 (ast/var-stmt a-token (ast/print-stmt (num-lit 1))))))

  (testing "a var statement's name must be a token"
    (is (thrown? IllegalArgumentException (ast/var-stmt "a" nil)))))

(deftest program-is-a-list-of-statements
  (testing "the starting rule is program, so parse returns a sequence"
    (is (= 3 (count (u/parse-program "print 1; print 2; print 3;"))))
    (is (every? ast/stmt? (u/parse-program "print 1; 2; var c;"))))

  (testing "an empty program is a program"
    (is (= [] (u/parse-program "")))
    (is (= [] (u/parse-program "   \n\t  // just a comment\n")))
    (is (not (u/had-error?))))

  (testing "parse never returns nil, unlike chapter 6's single-expression one"
    (is (vector? (u/parse-program "@")))
    (is (nil? (u/parse "@")))))

(deftest print-statement
  (testing "the chapter's first runnable program"
    (is (= ["one" "true" "3"]
           (u/run-program "print \"one\";\nprint true;\nprint 2 + 1;"))))

  (testing "print uses section 7.4's stringify, not the AST printer's"
    (are [src expected] (= [expected] (u/run-fresh src))
      "print 1;"        "1"
      "print 1.0;"      "1"
      "print 1.5;"      "1.5"
      "print nil;"      "nil"
      "print true;"     "true"
      "print false;"    "false"
      "print \"s\";"    "s"
      "print 10 / 4;"   "2.5"
      "print \"a\" + \"b\";" "ab"))

  (testing "the tree is (print <expr>)"
    (is (= "(print (+ 1.0 2.0))" (u/program-str "print 1 + 2;")))
    (is (ast/print-stmt? (first (u/parse-program "print 1;")))))

  (testing "a missing semicolon is reported against the value"
    (is (= "Expect ';' after value." (u/program-error-message "print 1")))
    (is (= "[line 1] Error at end: Expect ';' after value."
           (u/program-error "print 1")))))

(deftest expression-statement
  (testing "it evaluates and discards, so nothing is printed"
    (is (= [] (u/run-program "1 + 2;")))
    (is (= [] (u/run-fresh "true;")))
    (is (= [] (u/run-fresh "nil;"))))

  (testing "but the evaluation really happens - the side effect is visible"
    ;; Assignment is the only expression with a side effect in chapter 8, and
    ;; an expression statement is the only way to use one for its effect.
    (is (= ["2"] (u/run-fresh "var a = 1; a = 2; print a;"))))

  (testing "and a runtime error inside one still stops the program"
    (is (= "Operands must be numbers."
           (u/program-error-message "1 + 2; nil * 3; print \"never\";"))))

  (testing "the tree is (; <expr>), so a discarded value is visible as such"
    (is (= "(; (+ 1.0 2.0))" (u/program-str "1 + 2;")))
    (is (ast/expression-stmt? (first (u/parse-program "1;")))))

  (testing "a missing semicolon is reported against the expression"
    (is (= "Expect ';' after expression." (u/program-error-message "1 + 2")))
    (is (= "Expect ';' after expression." (u/program-error-message "a")))))

(deftest statements-run-in-order-and-stop-at-a-runtime-error
  (testing "in order"
    (is (= ["1" "2" "3"] (u/run-program "print 1; print 2; print 3;"))))

  (testing "a runtime error aborts the program, not just its own statement"
    ;; The book's `try` wraps the whole loop, not each `execute`.
    (let [[lines messages]
          (u/run-program-and-errors
           "print \"one\"; print 1 + nil; print \"three\";")]
      (is (= ["one"] lines))
      (is (= ["Operands must be two numbers or two strings."] messages))
      (is (u/had-runtime-error?))))

  (testing "interpret reports the outcome"
    (is (true? (first (u/capture-out #(u/interpret-program "print 1;")))))
    (err/reset-errors!)
    (is (false? (first (u/capture-out #(u/interpret-program "1 + nil;")))))))

(deftest statement-visitor-is-complete
  (testing "the executor handles every statement node type"
    ;; Book: `javac` refuses `implements Stmt.Visitor<Void>` with a method
    ;; missing. `defvisitor` refuses to compile; this asserts the result.
    (is (= ast/stmt-node-types (set (keys interp/executor)))))

  (testing "the expression visitor handles chapter 8's two new nodes"
    (is (= ast/expr-node-types (set (keys interp/interpreter))))
    (is (contains? interp/interpreter :variable))
    (is (contains? interp/interpreter :assign)))

  (testing "accept works on both families, and rejects non-nodes"
    (is (nil? (ast/accept (ast/print-stmt (num-lit 1))
                          {:print (constantly nil)})))
    (doseq [bad [nil 1 "x" {} [] {:node :nope} a-token]]
      (is (thrown? IllegalArgumentException
                   (ast/accept bad {:print identity}))
          (str "accept should reject " (pr-str bad)))))

  (testing "a visitor may not claim to handle another family's node types"
    ;; `defvisitor` throws during *macroexpansion*, which the Clojure compiler
    ;; wraps - so the assertion is on Exception, and the message is what pins
    ;; down that it was our check and not something incidental.
    (letfn [(expansion-error [form]
              (try (eval form) nil
                   (catch Exception e
                     (or (ex-message (or (ex-cause e) e)) (ex-message e)))))]
      (is (str/includes?
           (str (expansion-error '(lox.ast/defvisitor bad-visitor :stmt
                                    (:block [_] nil) (:expression [_] nil)
                                    (:print [_] nil) (:var [_] nil)
                                    (:binary [_] nil))))
           ":binary"))
      (is (str/includes?
           (str (expansion-error '(lox.ast/defvisitor missing-one :stmt
                                    (:block [_] nil) (:expression [_] nil)
                                    (:print [_] nil))))
           ":var"))
      (is (str/includes?
           (str (expansion-error '(lox.ast/defvisitor bad-family :nonesuch
                                    (:block [_] nil))))
           "nonesuch")))))

;;; ===========================================================================
;;; 2. Section 8.2 - global variables
;;; ===========================================================================

(deftest var-declaration-syntax
  (testing "with and without an initializer"
    (is (= "(var a 1.0)" (u/program-str "var a = 1;")))
    (is (= "(var a)"     (u/program-str "var a;")))
    (is (= "(var a nil)" (u/program-str "var a = nil;"))))

  (testing "an absent initializer is a missing node, not a node holding nil"
    ;; This is the distinction challenge 8.2 turns into different behaviour.
    (is (nil?            (:initializer (first (u/parse-program "var a;")))))
    (is (= (ast/literal nil)
           (:initializer (first (u/parse-program "var a = nil;"))))))

  (testing "the name token is kept whole, for its line number"
    (let [name (:name (first (u/parse-program "\n\nvar beverage;")))]
      (is (= :identifier (:type name)))
      (is (= "beverage"  (:lexeme name)))
      (is (= 3           (:line name)))))

  (testing "the initializer may be any expression"
    (is (= "(var a (+ 1.0 (* 2.0 3.0)))" (u/program-str "var a = 1 + 2 * 3;"))))

  (testing "errors"
    (are [src message] (= message (u/program-error-message src))
      "var;"        "Expect variable name."
      "var 1 = 2;"  "Expect variable name."
      "var \"a\";"  "Expect variable name."
      "var a = 1"   "Expect ';' after variable declaration."
      "var a"       "Expect ';' after variable declaration."
      "var a = ;"   "Expect expression.")))

(deftest variable-expression-syntax
  (testing "primary gained an IDENTIFIER clause (section 8.2.1)"
    (is (= "(; a)"       (u/program-str "a;")))
    (is (= "(; (+ a b))" (u/program-str "a + b;"))))

  (testing "a variable node wraps the whole token"
    (let [node (:expression (first (u/parse-program "beverage;")))]
      (is (ast/variable? node))
      (is (= "beverage" (:lexeme (:name node))))))

  (testing "a keyword is not an identifier, so it cannot be a variable"
    (is (= "Expect expression." (u/program-error-message "print class;")))))

(deftest declaring-and-reading-globals
  (testing "the chapter's first stateful program"
    (is (= ["3"] (u/run-program "var a = 1; var b = 2; print a + b;"))))

  (testing "a variable declared without an initializer is nil (section 8.3.1)"
    (is (= ["nil"] (u/run-fresh "var a; print a;"))))

  (testing "strings, booleans and nil all round-trip"
    (is (= ["espresso"] (u/run-fresh "var beverage = \"espresso\"; print beverage;")))
    (is (= ["true"]     (u/run-fresh "var t = true; print t;")))
    (is (= ["nil"]      (u/run-fresh "var n = nil; print n;"))))

  (testing "redefinition is allowed, and the book's example works verbatim"
    ;; "A variable statement doesn't just define a new variable, it can also
    ;;  be used to redefine an existing variable." Kept legal so that scripts
    ;;  and the REPL obey one rule; the book cites Scheme.
    (is (= ["before" "after"]
           (u/run-fresh (str "var a = \"before\"; print a;"
                             "var a = \"after\";  print a;")))))

  (testing "a variable may be used in a later declaration's initializer"
    (is (= ["2"] (u/run-fresh "var a = 1; var b = a + 1; print b;"))))

  (testing "globals persist across separate runs, as they must for the REPL"
    (err/reset-errors!)
    (interp/reset-globals!)
    (is (= [] (u/run-program "var sticky = 1;")))
    (err/reset-errors!)
    (is (= ["1"] (u/run-program "print sticky;")))))

(deftest reading-an-undefined-variable-is-a-runtime-error
  (testing "the message names the variable"
    (is (= "Undefined variable 'a'." (u/program-error-message "print a;"))))

  (testing "and the report names the line, from the token"
    (is (= "Undefined variable 'a'.\n[line 3]"
           (u/program-error "print 1;\nprint 2;\nprint a;"))))

  (testing "it is a runtime error, not a static one - section 8.3's choice"
    ;; "Since making it a static error makes recursive declarations too
    ;;  difficult, we'll defer the error to runtime."
    (let [[lines messages] (u/run-program-and-errors "print a;")]
      (is (= [] lines))
      (is (= ["Undefined variable 'a'."] messages))
      (is (u/had-runtime-error?))
      (is (not (u/had-error?))
          "the program parsed fine; it only failed when it ran")))

  (testing "so the book's 'too late' example runs and then fails"
    (let [[lines messages]
          (u/run-program-and-errors "print a;\nvar a = \"too late!\";")]
      (is (= [] lines))
      (is (= ["Undefined variable 'a'."] messages))))

  (testing "an unevaluated reference is fine - only reaching it is an error"
    ;; The property that makes mutual recursion possible in chapter 10. There
    ;; is no `if` until chapter 9, so the demonstration available here is that
    ;; the statement *containing* the bad reference is what fails: everything
    ;; before it ran, and the name was never a static error.
    (let [[lines messages]
          (u/run-program-and-errors "print \"before\"; print nope;")]
      (is (= ["before"] lines))
      (is (= ["Undefined variable 'nope'."] messages))
      (is (not (u/had-error?))))))

;;; ===========================================================================
;;; 3. Section 8.3 - the environment, on its own
;;; ===========================================================================

(deftest environment-construction
  (testing "the two constructors"
    (let [g (env/make-environment)
          l (env/make-environment g)]
      (is (nil? (env/enclosing g)))
      (is (= g (env/enclosing l)))
      (is (= 0 (env/depth g)))
      (is (= 1 (env/depth l)))
      (is (= 2 (env/depth (env/make-environment l))))
      (is (= g (env/globals l)))
      (is (= g (env/globals (env/make-environment l))))
      (is (= g (env/globals g)))))

  (testing "a fresh environment is empty"
    (is (= {} (env/binding-map (env/make-environment)))))

  (testing "environment?"
    (is (env/environment? (env/make-environment)))
    (are [x] (not (env/environment? x))
      nil 1 "x" {} {:values {} :enclosing nil})))

(deftest environment-define-and-get
  (let [e (env/make-environment)]
    (testing "define then get"
      (env/define! e "a" 1.0)
      (is (= 1.0 (env/get-var e a-token)))
      (is (= {"a" 1.0} (env/binding-map e))))

    (testing "define overwrites without complaint"
      (env/define! e "a" 2.0)
      (is (= 2.0 (env/get-var e a-token))))

    (testing "nil is a perfectly good bound value, not an absence"
      (env/define! e "n" nil)
      (is (nil? (env/get-var e (tok/make-token :identifier "n" nil 1))))
      (is (env/defined-locally? e "n")))

    (testing "keys are bare lexemes, so two tokens with one name agree"
      ;; "all identifier tokens with the same name should refer to the same
      ;;  variable [...] Using the raw string ensures all of those tokens
      ;;  refer to the same map key."
      (is (not= a-token a-token2) "different lines, so different tokens")
      (is (= (env/get-var e a-token) (env/get-var e a-token2))))

    (testing "an unknown name is a runtime error carrying the token"
      (let [e2 (env/make-environment)
            ex (try (env/get-var e2 a-token2) nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
        (is (err/runtime-error? ex))
        (is (= "Undefined variable 'a'." (ex-message ex)))
        (is (= a-token2 (:token (ex-data ex))))
        (is (= 7 (:line (:token (ex-data ex)))))))))

(deftest environment-assign
  (testing "assignment writes an existing binding and yields the value"
    (let [e (env/make-environment)]
      (env/define! e "a" 1.0)
      (is (= 2.0 (env/assign! e a-token 2.0)))
      (is (= 2.0 (env/get-var e a-token)))))

  (testing "assignment may not create a binding (section 8.4.2)"
    (let [e  (env/make-environment)
          ex (try (env/assign! e a-token 1.0) nil
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (err/runtime-error? ex))
      (is (= "Undefined variable 'a'." (ex-message ex)))
      (is (= {} (env/binding-map e))
          "nothing was implicitly declared"))))

(deftest environment-chaining
  (let [g (env/make-environment)
        m (env/make-environment g)
        l (env/make-environment m)]
    (env/define! g "global" "g")
    (env/define! m "middle" "m")
    (env/define! l "local"  "l")

    (testing "look-up walks outwards through the whole chain (section 8.5.1)"
      (are [name expected]
           (= expected (env/get-var l (tok/make-token :identifier name nil 1)))
        "local"  "l"
        "middle" "m"
        "global" "g"))

    (testing "but not inwards"
      (is (thrown? clojure.lang.ExceptionInfo
                   (env/get-var g (tok/make-token :identifier "local" nil 1)))))

    (testing "the innermost binding wins - this is shadowing"
      (env/define! m "global" "shadowed")
      (is (= "shadowed" (env/get-var l (tok/make-token :identifier "global" nil 1))))
      (is (= "g"        (env/get-var g (tok/make-token :identifier "global" nil 1)))
          "the outer binding is untouched"))

    (testing "resolve-environment names which scope a binding lives in"
      (is (= l (env/resolve-environment l "local")))
      (is (= m (env/resolve-environment l "middle")))
      (is (= m (env/resolve-environment l "global")) "the shadowing one")
      (is (= g (env/resolve-environment g "global")))
      (is (nil? (env/resolve-environment l "nope"))))

    (testing "defined-locally? ignores enclosing scopes"
      (is (env/defined-locally? l "local"))
      (is (not (env/defined-locally? l "middle"))))

    (testing "assignment writes the scope look-up would have read"
      (env/assign! l (tok/make-token :identifier "middle" nil 1) "m2")
      (is (= "m2" (env/get-var m (tok/make-token :identifier "middle" nil 1))))
      (is (not (env/defined-locally? l "middle"))
          "it did not create a local copy"))

    (testing "clear! empties one scope and leaves the chain alone"
      (env/clear! l)
      (is (= {} (env/binding-map l)))
      (is (= m (env/enclosing l)))
      (is (= "m2" (env/get-var l (tok/make-token :identifier "middle" nil 1)))))))

(deftest the-interpreter-exposes-its-environment
  (testing "the current scope is global at the top level"
    (is (= (interp/global-environment) (interp/current-environment)))
    (is (= 0 (env/depth (interp/current-environment)))))

  (testing "reset-globals! keeps the environment's identity"
    (let [g (interp/global-environment)]
      (u/run-program "var a = 1;")
      (is (env/defined-locally? g "a"))
      (interp/reset-globals!)
      (is (= g (interp/global-environment)) "same object")
      (is (= {} (env/binding-map g))))))

;;; ===========================================================================
;;; 4. Section 8.4 - assignment
;;; ===========================================================================

(deftest assignment-syntax
  (testing "the tree, and that the target is a token rather than a subtree"
    (is (= "(; (= a 1.0))" (u/program-str "a = 1;")))
    (let [node (:expression (first (u/parse-program "a = 1;")))]
      (is (ast/assign? node))
      (is (tok/token? (:name node)))
      (is (= "a" (:lexeme (:name node))))))

  (testing "assignment is right-associative, so it recurses rather than loops"
    (is (= "(; (= a (= b 1.0)))" (u/program-str "a = b = 1;"))))

  (testing "it is the lowest-precedence expression form"
    (is (= "(; (= a (+ 1.0 2.0)))" (u/program-str "a = 1 + 2;")))
    (is (= "(; (= a (== 1.0 2.0)))" (u/program-str "a = 1 == 2;"))))

  (testing "the assign node refuses an expression as its target"
    (is (thrown? IllegalArgumentException (ast/assign (num-lit 1) (num-lit 2))))
    (is (thrown? IllegalArgumentException (ast/assign "a" (num-lit 2))))))

(deftest assignment-semantics
  (testing "the book's examples"
    (is (= ["value"] (u/run-fresh "var a = \"before\"; a = \"value\"; print a;")))
    (is (= ["2"]     (u/run-fresh "var a = 1; print a = 2;"))
        "assignment is an expression, so it has a value"))

  (testing "chained assignment assigns the same value to both"
    (is (= ["3" "3"] (u/run-fresh "var a; var b; a = b = 3; print a; print b;"))))

  (testing "assigning to an undefined variable is a runtime error"
    (is (= "Undefined variable 'a'." (u/program-error-message "a = 1;")))
    (is (= "Undefined variable 'a'."
           (u/program-error-message "var b; a = 1;"))))

  (testing "assignment does not declare, so the name stays undefined"
    (let [[_ messages] (u/run-program-and-errors "a = 1; print a;")]
      (is (= ["Undefined variable 'a'."] messages))
      (is (not (env/defined-locally? (interp/global-environment) "a"))))))

(deftest invalid-assignment-targets
  (testing "a + b = c is a syntax error (section 8.4.1)"
    (is (= "Invalid assignment target." (u/program-error-message "a + b = c;")))
    (is (= "[line 1] Error at '=': Invalid assignment target."
           (u/program-error "a + b = c;"))))

  (testing "this is why chapter 5 kept a node for parentheses"
    ;; "We need to be able to distinguish these cases:
    ;;    a = 3;   // OK.
    ;;    (a) = 3; // Error."
    (is (nil? (u/program-error "var a; a = 3;")))
    (is (= "Invalid assignment target." (u/program-error-message "(a) = 3;"))))

  (testing "other non-targets"
    (are [src] (= "Invalid assignment target." (u/program-error-message src))
      "1 = 2;"
      "\"a\" = 2;"
      "true = 2;"
      "nil = 2;"
      "-a = 2;"
      "(a) = 2;"
      "a + b = c;"))

  (testing "it is reported, not thrown: no panic mode, so parsing continues"
    ;; "we don't throw it because the parser isn't in a confused state where we
    ;;  need to go into panic mode and synchronize."
    (let [messages (u/program-error-messages "1 = 2; 3 = 4;")]
      (is (= ["Invalid assignment target." "Invalid assignment target."]
             messages)
          "both statements were parsed, so both errors were found")))

  (testing "and nothing is executed, because it is still a static error"
    (let [[lines _] (u/run-program-and-errors "1 = 2; print \"never\";")]
      (is (= [] lines))
      (is (u/had-error?)))))

;;; ===========================================================================
;;; 5. Section 8.5 - scope
;;; ===========================================================================

(deftest block-syntax
  (testing "a block is a statement containing declarations"
    (is (= "(block (var a 1.0) (print a))"
           (u/program-str "{ var a = 1; print a; }")))
    (is (ast/block-stmt? (first (u/parse-program "{}")))))

  (testing "an empty block is legal"
    (is (= "(block)" (u/program-str "{}")))
    (is (= [] (u/run-fresh "{}"))))

  (testing "blocks nest"
    (is (= "(block (block (block (print 1.0))))"
           (u/program-str "{{{ print 1; }}}"))))

  (testing "a block may appear anywhere a statement may"
    (is (= ["1" "2"] (u/run-fresh "{ print 1; } { print 2; }"))))

  (testing "an unclosed block is reported and does not hang"
    ;; "We have to be careful to avoid infinite loops, even when parsing
    ;;  invalid code. If the user forgets a closing }, the parser needs to not
    ;;  get stuck."
    (is (= "Expect '}' after block." (u/program-error-message "{ print 1;")))
    (is (= "Expect '}' after block." (u/program-error-message "{")))
    (is (= "Expect '}' after block."
           (u/program-error-message "{ { print 1; }"))))

  (testing "a stray closing brace is an ordinary 'Expect expression.'"
    (is (= "Expect expression." (u/program-error-message "}")))))

(deftest block-scope
  (testing "a variable declared in a block does not escape it"
    ;; { var a = "in block"; }  print a;  // Error! No more "a".
    (let [[lines messages]
          (u/run-program-and-errors "{ var a = \"in block\"; } print a;")]
      (is (= [] lines))
      (is (= ["Undefined variable 'a'."] messages))))

  (testing "the two-blocks example of section 8.5"
    (is (= ["first" "second"]
           (u/run-fresh (str "{ var a = \"first\";  print a; }"
                             "{ var a = \"second\"; print a; }")))))

  (testing "an enclosing variable is visible inside a block"
    (is (= ["outsideinside"]
           (u/run-fresh (str "var global = \"outside\";"
                             "{ var local = \"inside\";"
                             "  print global + local; }")))))

  (testing "shadowing leaves the outer variable intact - the volume example"
    ;; The bug a delete-the-names implementation would have had.
    (is (= ["60" "0"]
           (u/run-fresh (str "var volume = 11;"
                             "volume = 0;"
                             "{ var volume = 3 * 4 * 5; print volume; }"
                             "print volume;")))))

  (testing "assignment inside a block writes the enclosing variable"
    (is (= ["2"] (u/run-fresh "var a = 1; { a = 2; } print a;"))))

  (testing "but a declaration inside a block does not"
    (is (= ["1"] (u/run-fresh "var a = 1; { var a = 2; } print a;"))))

  (testing "the scope is discarded, so the same block can run twice cleanly"
    (is (= ["1" "1"]
           (u/run-fresh (str "{ var a = 1; print a; }"
                             "{ var a = 1; print a; }"))))))

(deftest the-chapters-closing-example
  ;; The program section 8.5.2 ends with, and its expected output.
  (testing "nesting and shadowing, three scopes deep"
    (is (= ["inner a" "outer b" "global c"
            "outer a" "outer b" "global c"
            "global a" "global b" "global c"]
           (u/run-program
            (str "var a = \"global a\";\n"
                 "var b = \"global b\";\n"
                 "var c = \"global c\";\n"
                 "{\n"
                 "  var a = \"outer a\";\n"
                 "  var b = \"outer b\";\n"
                 "  {\n"
                 "    var a = \"inner a\";\n"
                 "    print a;\n"
                 "    print b;\n"
                 "    print c;\n"
                 "  }\n"
                 "  print a;\n"
                 "  print b;\n"
                 "  print c;\n"
                 "}\n"
                 "print a;\n"
                 "print b;\n"
                 "print c;\n")))))

  (testing "the global scope is current again afterwards"
    (is (= 0 (env/depth (interp/current-environment))))))

(deftest execute-block-restores-the-previous-scope
  (testing "even when a runtime error unwinds out of the block"
    ;; The book is explicit that this is why it uses `finally`; `binding`
    ;; gives it to us, and this test is what proves the claim.
    (let [before (interp/current-environment)]
      (is (= "Operands must be numbers."
             (u/program-error-message "{ nil * 1; }")))
      (is (= before (interp/current-environment)))
      (is (= 0 (env/depth (interp/current-environment))))))

  (testing "execute-block runs statements in the environment it is given"
    (let [scope (env/make-environment (interp/global-environment))
          [_ lines] (u/capture-out
                     #(interp/execute-block
                       (u/parse-program "var a = 1; print a;") scope))]
      (is (= ["1"] lines))
      (is (= {"a" 1.0} (env/binding-map scope)))
      (is (not (env/defined-locally? (interp/global-environment) "a"))))))

;;; ===========================================================================
;;; 6. Error recovery - panic mode, finally in use
;;; ===========================================================================

(deftest synchronization-is-live-from-this-chapter
  (testing "a bad declaration is reported once and the next one still parses"
    (let [statements (u/parse-program "var a = ; print \"ok\";")]
      (is (= 1 (count statements)))
      (is (ast/print-stmt? (first statements)))
      (is (= ["Expect expression."] (u/error-messages)))))

  (testing "several unrelated errors are all reported"
    (is (= ["Expect expression." "Expect expression."]
           (u/program-error-messages "var a = ; var b = ;")))
    (is (= ["Expect variable name." "Expect ';' after value."]
           (u/program-error-messages "var 1; print 2"))))

  (testing "but nothing runs, however much of it parsed"
    (let [[lines _] (u/run-program-and-errors "var a = ; print \"ok\";")]
      (is (= [] lines))
      (is (u/had-error?))))

  (testing "a failed statement contributes no nil to the statement list"
    ;; The book appends the null; an :stmts field here is checked at
    ;; construction, so a nil inside a block would fail at the wrong place.
    (is (every? some? (u/parse-program "var a = ; { var b = ; } print 1;")))
    (is (every? ast/stmt? (u/parse-program "var a = ; { var b = ; } print 1;"))))

  (testing "synchronization resumes at a statement keyword"
    (is (= ["Expect expression."]
           (u/program-error-messages "1 + ; var ok = 1;")))))

(deftest parsing-terminates-on-pathological-input
  ;; Every one of these used to be, or could plausibly be, an infinite loop:
  ;; the parse loop must consume at least one token per iteration.
  (testing "nothing hangs, and everything reports at least one error"
    (doseq [src [")" "}" "{" "{{{{" "}}}}" ";" ";;;;" "=" "= = =" "var" "var ;"
                 "print" "print ;" "*" "* 1" "(" "(((" "a =" "a = = 1;"
                 "{ var" "{ print" "1 2 3" ", , ," "? :" "nil nil"]]
      (let [messages (u/program-error-messages src)]
        (is (seq messages) (str "no error reported for " (pr-str src))))))

  (testing "and a program made only of separators parses to nothing runnable"
    (is (u/had-error?))))

;;; ===========================================================================
;;; 7. Challenges
;;; ===========================================================================

;;; --- Challenge 8.1: a REPL that takes statements *or* expressions ---------

(deftest challenge-8-1-repl-classifies-its-input
  (testing "a complete expression is recognised as one"
    (are [src] (= :expression (first (parser/parse-repl src)))
      "1 + 2"
      "\"a\" + \"b\""
      "(1 + 2) * 3"
      "!true"
      "nil"
      "a"
      "a = 1"
      "a = b = 1"))

  (testing "anything else is a series of statements"
    (are [src] (= :statements (first (parser/parse-repl src)))
      "print 1;"
      "var a = 1;"
      "1 + 2;"
      "{ var a = 1; }"
      ""
      "print 1; print 2;"))

  (testing "the expression branch returns an expression node"
    (let [[_ node] (parser/parse-repl "1 + 2")]
      (is (ast/expr? node))
      (is (= "(+ 1.0 2.0)" (printer/print-ast node)))))

  (testing "the statement branch returns statement nodes"
    (let [[_ nodes] (parser/parse-repl "print 1;")]
      (is (every? ast/stmt? nodes))
      (is (= "(print 1.0)" (printer/print-program nodes))))))

(deftest challenge-8-1-speculative-parse-leaves-no-trace
  (testing "a statement does not report the abandoned expression attempt"
    (err/reset-errors!)
    (parser/parse-repl "print 1;")
    (is (= [] (u/error-messages)))
    (is (not (u/had-error?))))

  (testing "nor does a var declaration, or a block"
    (doseq [src ["var a = 1;" "{ print 1; }" "a = 1; a = 2;"]]
      (err/reset-errors!)
      (parser/parse-repl src)
      (is (= [] (u/error-messages)) src)))

  (testing "a genuinely broken line still reports, once"
    (err/reset-errors!)
    (parser/parse-repl "print 1")
    (is (= ["Expect ';' after value."] (u/error-messages))))

  (testing "a scan error is reported once and survives the rollback"
    ;; The snapshot is taken *after* scanning, precisely so that this holds:
    ;; the scan error is reported once, is not rolled back, and is not
    ;; reported a second time by the statement parse that follows.
    (err/reset-errors!)
    (parser/parse-repl "#")
    (is (= ["Unexpected character."] (u/error-messages))
        "the line scanned to nothing but EOF, so there is no parse error")

    (err/reset-errors!)
    (parser/parse-repl "print \"unterminated")
    (is (= ["Unterminated string." "Expect expression."]
           (u/error-messages))
        "one scan error, then the one parse error the statement really has")

    (testing "and the scan error is not duplicated by the second attempt"
      (err/reset-errors!)
      (parser/parse-repl "print \"unterminated")
      (is (= 1 (count (filter #{"Unterminated string."} (u/error-messages))))))))

(deftest challenge-8-1-speculative-parse-prints-nothing
  ;; Rolling back the recorded error state is not enough: an error that was
  ;; printed has already been read, and the REPL would show "Expect
  ;; expression." above the output of every statement the user typed. These
  ;; assertions are on stderr, not on the error log, because that is the thing
  ;; that was broken.
  (testing "a statement line produces no error output at all"
    (doseq [src ["var a = 1;" "print 1;" "{ var b = 2; }" "1 + 2;"
                 "var c = 1; c = 2;" "{}" "print \"s\"; print nil;"]]
      (err/reset-errors!)
      (interp/reset-globals!)
      (let [[_ errs] (u/capture-err
                      #(u/capture-out (fn [] (core/run-repl-line src))))]
        (is (= [] errs) src))))

  (testing "an expression line produces no error output either"
    (doseq [src ["1 + 2" "nil" "(1 + 2) * 3" "!true"]]
      (err/reset-errors!)
      (interp/reset-globals!)
      (let [[_ errs] (u/capture-err
                      #(u/capture-out (fn [] (core/run-repl-line src))))]
        (is (= [] errs) src))))

  (testing "a genuinely broken line prints exactly its own error, once"
    (err/reset-errors!)
    (let [[_ errs] (u/capture-err #(core/run-repl-line "print 1"))]
      (is (= ["[line 1] Error at end: Expect ';' after value."] errs))))

  (testing "a runtime error at the prompt is printed, once"
    (err/reset-errors!)
    (interp/reset-globals!)
    (let [[_ errs] (u/capture-err #(core/run-repl-line "undefined_name"))]
      (is (= ["Undefined variable 'undefined_name'." "[line 1]"] errs)))))

(deftest challenge-8-1-repl-echoes-expressions-but-not-statements
  (let [repl (fn [src]
               (err/reset-errors!)
               (second (u/capture-out #(core/run-repl-line src))))]
    (testing "an expression is evaluated and displayed"
      (interp/reset-globals!)
      (is (= ["3"] (repl "1 + 2")))
      (is (= ["7"] (repl "1 + 2 * 3")))
      (is (= ["nil"] (repl "nil"))))

    (testing "a statement is executed, and says only what it says"
      (interp/reset-globals!)
      (is (= [] (repl "var a = 1;")))
      (is (= [] (repl "a = 2;")))
      (is (= ["2"] (repl "print a;"))))

    (testing "the semicolon is the difference, and it is not noise"
      (interp/reset-globals!)
      (is (= ["3"] (repl "1 + 2"))  "an expression: displayed")
      (is (= []    (repl "1 + 2;")) "an expression statement: discarded"))

    (testing "state set by one line is visible to the next"
      (interp/reset-globals!)
      (is (= [] (repl "var counter = 0;")))
      (is (= ["1"] (repl "counter = counter + 1")))
      (is (= ["1"] (repl "print counter;"))))

    (testing "with the challenge off, a bare expression is a syntax error"
      (interp/reset-globals!)
      (binding [core/*repl-echoes-expressions?* false]
        (err/reset-errors!)
        (is (= [] (second (u/capture-out #(core/run-repl-line "1 + 2")))))
        (is (= ["Expect ';' after expression."] (u/error-messages)))))

    (testing "a runtime error at the prompt is reported and does not kill it"
      (interp/reset-globals!)
      (err/reset-errors!)
      (is (= [] (second (u/capture-out #(core/run-repl-line "1 + nil")))))
      (is (u/had-runtime-error?))
      (err/reset-errors!)
      (is (= ["3"] (repl "1 + 2"))))))

;;; --- Challenge 8.2: uninitialized variables ------------------------------

(deftest challenge-8-2-default-is-the-books-nil
  (testing "off (the default): an uninitialized variable reads as nil"
    (is (false? env/*error-on-uninitialized?*))
    (is (= ["nil"] (u/run-fresh "var a; print a;")))
    (is (= ["true"] (u/run-fresh "var a; print a == nil;")))
    (is (nil? (env/initial-value)))))

(deftest challenge-8-2-error-on-uninitialized
  (binding [env/*error-on-uninitialized?* true]
    (testing "the challenge's own example"
      ;; var a; var b;  a = "assigned";  print a;  // OK
      ;;                                 print b;  // Error!
      (let [[lines messages]
            (u/run-program-and-errors
             (str "var a; var b;"
                  "a = \"assigned\";"
                  "print a;"
                  "print b;"))]
        (is (= ["assigned"] lines))
        (is (= ["Variable 'b' is not initialized."] messages))))

    (testing "the message distinguishes uninitialized from undefined"
      (is (= "Variable 'a' is not initialized."
             (u/program-error-message "var a; print a;")))
      (is (= "Undefined variable 'a'."
             (u/program-error-message "print a;"))))

    (testing "an explicit nil initializer is initialized"
      (is (= ["nil"] (u/run-fresh "var a = nil; print a;"))))

    (testing "assignment initializes, and may be read afterwards"
      (is (= ["1"] (u/run-fresh "var a; a = 1; print a;"))))

    (testing "a redeclaration can un-initialize a variable again"
      (is (= "Variable 'a' is not initialized."
             (u/program-error-message "var a = 1; var a; print a;"))))

    (testing "it works through a scope chain"
      (is (= "Variable 'a' is not initialized."
             (u/program-error-message "var a; { print a; }")))
      (is (= ["1"] (u/run-fresh "var a; { a = 1; } print a;"))))

    (testing "the sentinel is not a Lox value and cannot leak into a program"
      (is (not (interp/lox-value? env/uninitialized)))
      (is (env/uninitialized? (env/initial-value)))
      (is (not (env/uninitialized? nil))))))

;;; --- Challenge 8.3: var a = a + 2 inside a block -------------------------

(deftest challenge-8-3-what-this-implementation-does
  ;; "What does the following program do? [...] What did you expect it to do?"
  ;;
  ;;   var a = 1;
  ;;   {
  ;;     var a = a + 2;
  ;;     print a;
  ;;   }
  ;;
  ;; It prints 3. The `:var` clause evaluates the initializer *before* calling
  ;; `define!`, so at the moment `a + 2` is evaluated the inner `a` does not
  ;; exist yet and look-up walks out to the global one. See the challenge doc
  ;; for the argument that this is the wrong answer and for what chapter 11
  ;; does about it.
  (testing "the initializer sees the enclosing binding, so it prints 3"
    (is (= ["3"] (u/run-program "var a = 1;\n{\n  var a = a + 2;\n  print a;\n}"))))

  (testing "the outer binding is unchanged"
    (is (= ["3" "1"]
           (u/run-fresh (str "var a = 1;"
                             "{ var a = a + 2; print a; }"
                             "print a;")))))

  (testing "at global scope the same shape is a redefinition, and also 3"
    (is (= ["3"] (u/run-fresh "var a = 1; var a = a + 2; print a;"))))

  (testing "with no enclosing binding it is a runtime error, not nil"
    (is (= "Undefined variable 'a'."
           (u/program-error-message "{ var a = a + 2; print a; }"))))

  (testing "under challenge 8.2 the uninitialized read is caught instead"
    (binding [env/*error-on-uninitialized?* true]
      (is (= "Variable 'a' is not initialized."
             (u/program-error-message "var a; { var b = a + 2; }"))))))

;;; ===========================================================================
;;; 8. Whole-chapter smoke tests - the pipeline end to end
;;; ===========================================================================

(deftest the-full-pipeline
  (testing "source -> tokens -> statements -> output, through lox.core/run"
    (let [[_ lines] (u/capture-out
                     #(core/run (str "var greeting = \"Hello\";"
                                     "var who = \"world\";"
                                     "{ var sep = \", \";"
                                     "  print greeting + sep + who + \"!\"; }")))]
      (is (= ["Hello, world!"] lines))))

  (testing "run prints nothing of its own accord"
    (let [[_ lines] (u/capture-out #(core/run "1 + 2;"))]
      (is (= [] lines))))

  (testing "a static error means nothing runs at all"
    (err/reset-errors!)
    (let [[_ lines] (u/capture-out #(core/run "print \"a\"; print \"b\""))]
      (is (= [] lines) "not even the statement that parsed")
      (is (u/had-error?)))))

(deftest exit-codes
  (let [write (fn [src]
                (let [f (java.io.File/createTempFile "lox" ".lox")]
                  (.deleteOnExit f)
                  (spit f src)
                  (.getPath f)))]
    (testing "0 when the program runs"
      (let [[status lines] (u/capture-out
                            #(core/run-file (write "print 1;")))]
        (is (= 0 status))
        (is (= ["1"] lines))))

    (testing "65 (EX_DATAERR) for a static error - the code never ran"
      (let [[status lines] (u/capture-out #(core/run-file (write "print 1")))]
        (is (= 65 status))
        (is (= [] lines))))

    (testing "70 (EX_SOFTWARE) for a runtime error - it ran and failed"
      (let [[status lines]
            (u/capture-out #(core/run-file (write "print \"a\"; print b;")))]
        (is (= 70 status))
        (is (= ["a"] lines) "the statements before the failure did run")))))

(deftest earlier-chapters-stay-runnable
  (testing "--ast prints a whole program, one statement per line"
    (let [[_ lines] (u/capture-out #(core/print-ast "var a = 1; print a + 2;"))]
      (is (= ["(var a 1.0)" "(print (+ a 2.0))"] lines))))

  (testing "--ast-expression is still exactly chapter 6"
    (let [[_ lines] (u/capture-out
                     #(core/print-ast-expression "-123 * (45.67)"))]
      (is (= ["(* (- 123.0) (group 45.67))"] lines))))

  (testing "--expression is still exactly chapter 7"
    (let [[_ lines] (u/capture-out #(core/run-expression "1 + 2 * 3"))]
      (is (= ["7"] lines))))

  (testing "--tokens is still exactly chapter 4"
    (let [[_ lines] (u/capture-out #(core/print-tokens "var a = 1;"))]
      (is (= ["VAR var null" "IDENTIFIER a null" "EQUAL = null"
              "NUMBER 1 1.0" "SEMICOLON ; null" "EOF  null"]
             lines))))

  (testing "every stage flag resolves to something callable"
    (doseq [[flag f] core/stage-flags]
      (is (fn? f) flag))))

(deftest the-example-script-runs
  (testing "examples/ch08_statements.lox produces its documented output"
    (let [path "examples/ch08_statements.lox"]
      (is (.exists (java.io.File. path)))
      (interp/reset-globals!)
      (err/reset-errors!)
      (let [[_ lines] (u/capture-out #(core/run (slurp path)))]
        (is (not (u/had-error?)))
        (is (not (u/had-runtime-error?)))
        (is (= ["one" "true" "3"
                "espresso"
                "before" "after"
                "nil"
                "3" "2"
                "inner a" "outer b" "global c"
                "outer a" "outer b" "global c"
                "global a" "global b" "global c"
                "60" "0"]
               lines))))))

;;; ===========================================================================
;;; 9. Interaction with the earlier chapters' challenge switches
;;; ===========================================================================

(deftest assignment-slots-into-the-challenge-precedence-chain
  (testing "with the ternary on, assignment binds looser than ?: "
    ;; `assignment -> IDENTIFIER "=" assignment | conditional`, so an
    ;; assignment may *contain* a ternary but the ternary's own operands are
    ;; at tighter levels - except the middle one, fenced in by `?` and `:`,
    ;; which chapter 6 deliberately parses as a full `expression` so that
    ;; chapter 8's assignment would slot into it. Both facts are asserted.
    (binding [parser/*allow-conditional?* true]
      (is (= "(; (= a (?: b 1.0 2.0)))" (u/program-str "a = b ? 1 : 2;")))
      (is (= "(; (?: a (= b 1.0) c))"   (u/program-str "a ? b = 1 : c;")))
      (is (= ["1"] (u/run-fresh
                    "var b; var c; true ? b = 1 : c; print b;")))))

  (testing "with the comma operator on, assignment binds tighter than it"
    (binding [parser/*allow-comma?* true]
      (is (= "(; (, (= a 1.0) (= b 2.0)))"
             (u/program-str "a = 1, b = 2;")))
      (is (= ["1" "2"]
             (u/run-fresh "var a; var b; a = 1, b = 2; print a; print b;")))))

  (testing "with both off - the default - the chain is exactly the book's"
    (is (false? parser/*allow-comma?*))
    (is (false? parser/*allow-conditional?*))
    (is (= "assignment -> IDENTIFIER \"=\" assignment | equality ;"
           (second parser/grammar)))))

(deftest chapter-7-challenges-still-work-through-statements
  (testing "7.2 string-coercing plus, in a print statement"
    (binding [interp/*string-coercing-plus?* true]
      (is (= ["scone4"] (u/run-fresh "var n = 4; print \"scone\" + n;")))))

  (testing "7.3 division by zero, in a variable initializer"
    (binding [interp/*error-on-division-by-zero?* true]
      (is (= "Division by zero." (u/program-error-message "var a = 1 / 0;")))))

  (testing "7.1 string comparison, in a block"
    (binding [interp/*compare-strings?* true]
      (is (= ["true"] (u/run-fresh "{ print \"a\" < \"b\"; }"))))))
