;;;; =============================================================================
;;;; Chapter 5 - Representing Code : unit tests
;;;;
;;;; What chapter 5 actually delivers is three things, and each gets its own
;;;; section below:
;;;;
;;;;   1. a data representation for the expression grammar of section 5.1.3
;;;;      (the four node types, their fields, and their field order)
;;;;   2. the machinery that produces it - `define-ast`, the constructors, the
;;;;      predicates, and the field-kind checking that stands in for javac
;;;;   3. the visitor (section 5.3) and the two operations written with it:
;;;;      the AST printer (5.4) and the RPN printer (challenge 5.3)
;;;;
;;;; There is no parser yet, so every tree here is hand-built exactly the way
;;;; the book hand-builds its demo tree at the end of the chapter.
;;;; =============================================================================
(ns lox.chapter-05-ast-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [lox.ast :as ast]
            [lox.ast-printer :as printer]
            [lox.scanner :as scanner]
            [lox.token :as tok]
            [lox.test-util :as u]))

(use-fixtures :each u/quiet-errors)

;;; ---------------------------------------------------------------------------
;;; Fixtures: tokens and small trees
;;; ---------------------------------------------------------------------------

(defn op
  "A operator token, defaulting to line 1."
  ([type lexeme] (op type lexeme 1))
  ([type lexeme line] (tok/make-token type lexeme nil line)))

(def minus (op :minus "-"))
(def plus  (op :plus "+"))
(def star  (op :star "*"))
(def slash (op :slash "/"))
(def bang  (op :bang "!"))

(defn num-lit
  "A literal node holding the double `n`, as the scanner would produce."
  [n]
  (ast/literal (double n)))

(defn expansion-failure
  "Macroexpand `form`, returning the ExceptionInfo it threw (or nil if it
  expanded cleanly).

  Clojure wraps a macro's own exception in a `Compiler$CompilerException`
  during the macro-syntax-check phase, so we walk down to the cause that
  carries the `ex-data` the macro attached."
  [form]
  (try
    (macroexpand form)
    nil
    (catch Throwable t
      (loop [e t]
        (cond
          (instance? clojure.lang.ExceptionInfo e) e
          (some? (ex-cause e)) (recur (ex-cause e))
          :else nil)))))

;;; ===========================================================================
;;; 1. The grammar, as data
;;; ===========================================================================

(deftest grammar-table-matches-the-book
  (testing "exactly the four productions of section 5.1.3 are defined"
    (is (= #{:binary :grouping :literal :unary} ast/expr-node-types))
    (is (= #{:binary :grouping :literal :unary} (set (keys ast/expr-types)))))

  (testing "each node's fields match GenerateAst's type descriptions"
    ;; "Binary   : Expr left, Token operator, Expr right"
    (is (= '[[:expr left] [:token operator] [:expr right]]
           (:binary ast/expr-types)))
    ;; "Grouping : Expr expression"
    (is (= '[[:expr expression]] (:grouping ast/expr-types)))
    ;; "Literal  : Object value"
    (is (= '[[:object value]] (:literal ast/expr-types)))
    ;; "Unary    : Token operator, Expr right"
    (is (= '[[:token operator] [:expr right]] (:unary ast/expr-types))))

  (testing "every declared field kind is one define-ast knows how to check"
    (doseq [[node-type fields] ast/expr-types
            [kind field] fields]
      (is (contains? ast/field-kinds kind)
          (str node-type "/" field " has unknown kind " kind)))))

;;; ===========================================================================
;;; 2. Constructors, predicates and field checking
;;; ===========================================================================

(deftest constructors-build-the-expected-maps
  (testing "literal"
    (is (= {:node :literal :value 1.0} (ast/literal 1.0))))

  (testing "unary"
    (is (= {:node :unary :operator minus :right (num-lit 1)}
           (ast/unary minus (num-lit 1)))))

  (testing "grouping"
    (is (= {:node :grouping :expression (num-lit 1)}
           (ast/grouping (num-lit 1)))))

  (testing "binary"
    (is (= {:node :binary
            :left (num-lit 1) :operator plus :right (num-lit 2)}
           (ast/binary (num-lit 1) plus (num-lit 2))))))

(deftest constructor-argument-order-is-source-order
  (testing "the operator sits between its operands, as in the source text"
    (let [e (ast/binary (num-lit 1) minus (num-lit 2))]
      (is (= 1.0 (-> e :left :value)))
      (is (= minus (:operator e)))
      (is (= 2.0 (-> e :right :value)))))

  (testing "field order in the map matches the declaration order"
    (is (= [:node :left :operator :right]
           (keys (ast/binary (num-lit 1) plus (num-lit 2)))))
    (is (= [:node :operator :right]
           (keys (ast/unary minus (num-lit 1)))))))

(deftest nodes-are-tagged-with-their-production
  (is (= :literal  (ast/node-type (ast/literal 1.0))))
  (is (= :unary    (ast/node-type (ast/unary minus (num-lit 1)))))
  (is (= :grouping (ast/node-type (ast/grouping (num-lit 1)))))
  (is (= :binary   (ast/node-type (ast/binary (num-lit 1) plus (num-lit 2))))))

(deftest per-node-predicates
  (let [lit   (num-lit 1)
        un    (ast/unary minus lit)
        grp   (ast/grouping lit)
        bin   (ast/binary lit plus lit)
        nodes {:literal lit :unary un :grouping grp :binary bin}
        preds {:literal ast/literal? :unary ast/unary?
               :grouping ast/grouping? :binary ast/binary?}]
    (testing "each predicate accepts its own node type and no other"
      (doseq [[pred-type pred] preds
              [node-type node] nodes]
        (is (= (= pred-type node-type) (boolean (pred node)))
            (str pred-type "? on a " node-type " node"))))))

(deftest expr-predicate
  (testing "accepts every node type"
    (is (ast/expr? (num-lit 1)))
    (is (ast/expr? (ast/unary minus (num-lit 1))))
    (is (ast/expr? (ast/grouping (num-lit 1))))
    (is (ast/expr? (ast/binary (num-lit 1) plus (num-lit 2)))))

  (testing "rejects non-nodes"
    (is (not (ast/expr? nil)))
    (is (not (ast/expr? 1)))
    (is (not (ast/expr? "binary")))
    (is (not (ast/expr? :binary)))
    (is (not (ast/expr? [])))
    (is (not (ast/expr? {})))
    (is (not (ast/expr? {:node :nonsense}))))

  (testing "a token is never mistaken for a node, and vice versa"
    ;; This is why the tag key is :node and not :type. Token types and node
    ;; type names collide from chapter 8 onward (:print, :var, :while, ...).
    (is (not (ast/expr? plus)))
    (is (not (ast/expr? (tok/make-token :number "12" 12.0 1))))
    (is (not (tok/token? (num-lit 1))))
    (is (not (tok/token? (ast/binary (num-lit 1) plus (num-lit 2)))))))

(deftest field-kinds-are-checked-at-construction
  (testing ":expr fields reject anything that is not a node"
    (is (thrown? IllegalArgumentException (ast/grouping 1.0)))
    (is (thrown? IllegalArgumentException (ast/grouping nil)))
    (is (thrown? IllegalArgumentException (ast/grouping plus)))
    (is (thrown? IllegalArgumentException (ast/unary minus "not an expr")))
    (is (thrown? IllegalArgumentException (ast/binary "x" plus (num-lit 1))))
    (is (thrown? IllegalArgumentException (ast/binary (num-lit 1) plus nil))))

  (testing ":token fields reject anything that is not a token"
    (is (thrown? IllegalArgumentException (ast/unary :minus (num-lit 1))))
    (is (thrown? IllegalArgumentException (ast/unary "-" (num-lit 1))))
    (is (thrown? IllegalArgumentException (ast/unary nil (num-lit 1))))
    ;; An expression node is not a token either.
    (is (thrown? IllegalArgumentException
                 (ast/binary (num-lit 1) (num-lit 2) (num-lit 3)))))

  (testing ":object fields (literal values) accept anything, including nil"
    (is (= nil    (:value (ast/literal nil))))
    (is (= true   (:value (ast/literal true))))
    (is (= false  (:value (ast/literal false))))
    (is (= 0.0    (:value (ast/literal 0.0))))
    (is (= "text" (:value (ast/literal "text")))))

  (testing "the error message names the node, the field and what was wanted"
    (let [msg (try (ast/grouping 42) nil
                   (catch IllegalArgumentException e (.getMessage e)))]
      (is (some? msg))
      (is (str/includes? msg ":grouping"))
      (is (str/includes? msg ":expression"))
      (is (str/includes? msg "an expression node"))
      (is (str/includes? msg "42")))))

(deftest nodes-are-values
  (testing "structurally equal trees are equal, however they were built"
    (is (= (ast/binary (num-lit 1) plus (num-lit 2))
           (ast/binary (ast/literal 1.0) (op :plus "+") (ast/literal 2.0)))))

  (testing "differing operands, operators or shapes compare unequal"
    (is (not= (ast/binary (num-lit 1) plus (num-lit 2))
              (ast/binary (num-lit 1) plus (num-lit 3))))
    (is (not= (ast/binary (num-lit 1) plus (num-lit 2))
              (ast/binary (num-lit 1) minus (num-lit 2))))
    (is (not= (num-lit 1) (ast/grouping (num-lit 1)))))

  (testing "operator line numbers are part of the node"
    (is (not= (ast/unary (op :minus "-" 1) (num-lit 1))
              (ast/unary (op :minus "-" 2) (num-lit 1)))))

  (testing "nodes are usable as map/set keys, i.e. they hash by value"
    (is (= 1 (count (into #{} [(num-lit 1) (ast/literal 1.0)]))))
    (is (= :found (get {(num-lit 1) :found} (ast/literal 1.0)))))

  (testing "nodes are immutable - 'updating' one leaves the original alone"
    (let [e  (ast/binary (num-lit 1) plus (num-lit 2))
          e' (assoc e :right (num-lit 9))]
      (is (= 2.0 (-> e :right :value)))
      (is (= 9.0 (-> e' :right :value))))))

;;; ===========================================================================
;;; 3. Generic access: node-type / fields-of / children
;;; ===========================================================================

(deftest children-returns-sub-expressions-in-source-order
  (let [one (num-lit 1) two (num-lit 2)]
    (is (= [] (ast/children one))
        "a literal is a leaf")
    (is (= [one] (ast/children (ast/grouping one))))
    (is (= [one] (ast/children (ast/unary minus one)))
        "the operator token is not a child")
    (is (= [one two] (ast/children (ast/binary one plus two)))
        "left before right")))

(deftest fields-of-describes-a-nodes-shape
  (is (= '[[:expr left] [:token operator] [:expr right]]
         (ast/fields-of (ast/binary (num-lit 1) plus (num-lit 2)))))
  (is (= '[[:object value]] (ast/fields-of (num-lit 1)))))

(deftest children-supports-generic-traversal
  ;; The payoff of exposing shape as data: these two functions work for every
  ;; node type that exists now, and for every one chapters 8-13 will add,
  ;; without a single per-node-type case.
  (letfn [(node-count [e] (inc (reduce + 0 (map node-count (ast/children e)))))
          (depth [e] (inc (reduce max 0 (map depth (ast/children e)))))]
    (let [tree (ast/binary (ast/unary minus (num-lit 123))
                           star
                           (ast/grouping (num-lit 45.67)))]
      ;; binary + unary + literal + grouping + literal
      (is (= 5 (node-count tree)))
      (is (= 3 (depth tree)))
      (is (= 1 (node-count (num-lit 1))))
      (is (= 1 (depth (num-lit 1)))))))

;;; ===========================================================================
;;; 4. The visitor (section 5.3)
;;; ===========================================================================

(deftest accept-dispatches-on-node-type
  (let [spy (fn [tag] (fn [node] [tag (ast/node-type node)]))
        visitor {:binary   (spy :b)
                 :grouping (spy :g)
                 :literal  (spy :l)
                 :unary    (spy :u)}]
    (is (= [:l :literal]  (ast/accept (num-lit 1) visitor)))
    (is (= [:u :unary]    (ast/accept (ast/unary minus (num-lit 1)) visitor)))
    (is (= [:g :grouping] (ast/accept (ast/grouping (num-lit 1)) visitor)))
    (is (= [:b :binary]
           (ast/accept (ast/binary (num-lit 1) plus (num-lit 2)) visitor)))))

(deftest accept-passes-the-whole-node-to-the-handler
  (let [e (ast/binary (num-lit 1) plus (num-lit 2))]
    (is (= e (ast/accept e {:binary identity})))))

(deftest accept-rejects-non-nodes
  (doseq [bad [nil 1 "x" :binary [] {} plus {:node :nope}]]
    (is (thrown? IllegalArgumentException (ast/accept bad {:binary identity}))
        (str "accept should reject " (pr-str bad)))))

(deftest accept-throws-when-a-visit-method-is-missing
  ;; The book gets this from the Java compiler; we get it at the call site,
  ;; loudly, instead of silently returning nil.
  (let [only-literals {:literal :value}]
    (is (= 1.0 (ast/accept (num-lit 1) only-literals)))
    (let [msg (try (ast/accept (ast/grouping (num-lit 1)) only-literals) nil
                   (catch IllegalArgumentException e (.getMessage e)))]
      (is (some? msg))
      (is (str/includes? msg ":grouping"))
      (is (str/includes? msg ":literal") "it lists what the visitor can do"))))

(deftest visitors-are-independent-operations-over-one-tree
  ;; Section 5.3's point: a new operation is a new column in the table and
  ;; touches no node definition. Here are three columns over the same tree.
  (let [tree (ast/binary (num-lit 1) plus (ast/grouping (num-lit 2)))
        recur-sum (fn recur-sum [e]
                    (ast/accept e {:literal  :value
                                   :grouping #(recur-sum (:expression %))
                                   :unary    #(- (recur-sum (:right %)))
                                   :binary   #(+ (recur-sum (:left %))
                                                 (recur-sum (:right %)))}))]
    (is (= 3.0 (recur-sum tree)))
    (is (= "(+ 1.0 (group 2.0))" (printer/print-ast tree)))
    (is (= "1.0 2.0 +" (printer/print-rpn tree)))))

(deftest defvisitor-requires-every-node-type
  (testing "a visitor missing a node type fails at macroexpansion time"
    (let [e (expansion-failure '(lox.ast/defvisitor incomplete
                                  (:binary [e] 1)
                                  (:literal [e] 2)))]
      (is (some? e) "expansion should have thrown")
      (is (= #{:grouping :unary} (set (:missing (ex-data e)))))))

  (testing "a visitor naming a node type that does not exist is rejected"
    (let [e (expansion-failure '(lox.ast/defvisitor bogus
                                  (:binary [e] 1) (:grouping [e] 1)
                                  (:literal [e] 1) (:unary [e] 1)
                                  (:nonsense [e] 1)))]
      (is (some? e) "expansion should have thrown")
      (is (= [:nonsense] (:unknown (ex-data e))))))

  (testing "^:partial opts out deliberately"
    (is (some? (macroexpand '(lox.ast/defvisitor ^:partial only-lits
                               (:literal [e] (:value e)))))))

  (testing "a complete visitor expands into a def of a map of functions"
    (let [form (macroexpand '(lox.ast/defvisitor complete
                               "doc"
                               (:binary [e] 1) (:grouping [e] 2)
                               (:literal [e] 3) (:unary [e] 4)))
          [head vname doc body] form]
      (is (= 'def head))
      (is (= 'complete vname))
      (is (= "doc" doc))
      (is (map? body))
      (is (= #{:binary :grouping :literal :unary} (set (keys body)))))))

(deftest defvisitor-produces-a-working-visitor
  (let [v (eval '(do (lox.ast/defvisitor built-here
                       (:binary   [e] :b)
                       (:grouping [e] :g)
                       (:literal  [e] :l)
                       (:unary    [e] :u))
                     built-here))]
    (is (= :l (ast/accept (ast/literal 1.0) v)))
    (is (= :b (ast/accept (ast/binary (ast/literal 1.0)
                                      (tok/make-token :plus "+" nil 1)
                                      (ast/literal 2.0))
                          v)))))

;;; ===========================================================================
;;; 5. The AST printer (section 5.4)
;;; ===========================================================================

(deftest the-books-example-tree
  ;; The chapter's own worked example, built exactly as its main() builds it:
  ;;   new Expr.Binary(new Expr.Unary(MINUS, new Expr.Literal(123)),
  ;;                   STAR,
  ;;                   new Expr.Grouping(new Expr.Literal(45.67)))
  (is (= "(* (- 123) (group 45.67))"
         (printer/print-ast (printer/demo-expression)))))

(deftest printing-literals
  (testing "nil prints as Lox's nil, not as an empty string"
    (is (= "nil" (printer/print-ast (ast/literal nil)))))

  (testing "booleans"
    (is (= "true"  (printer/print-ast (ast/literal true))))
    (is (= "false" (printer/print-ast (ast/literal false)))))

  (testing "strings print their contents, with no added quotes (Java toString)"
    (is (= "hello" (printer/print-ast (ast/literal "hello"))))
    (is (= ""      (printer/print-ast (ast/literal "")))))

  (testing "numbers print as the tree holds them"
    ;; Scanned numbers are always doubles, so a source `123` shows as 123.0.
    ;; Trimming that is chapter 7's `stringify`, not the AST printer's job.
    (is (= "123.0" (printer/print-ast (ast/literal 123.0))))
    (is (= "45.67" (printer/print-ast (ast/literal 45.67))))
    (is (= "0.0"   (printer/print-ast (ast/literal 0.0))))
    (is (= "-1.5"  (printer/print-ast (ast/literal -1.5))))
    (is (= "123"   (printer/print-ast (ast/literal 123)))
        "the book's demo passes a Java int; we match its toString")))

(deftest printing-unary-and-grouping
  (is (= "(- 1.0)" (printer/print-ast (ast/unary minus (num-lit 1)))))
  (is (= "(! true)" (printer/print-ast (ast/unary bang (ast/literal true)))))
  (is (= "(group 1.0)" (printer/print-ast (ast/grouping (num-lit 1)))))
  (testing "nesting is shown, not collapsed"
    (is (= "(group (group 1.0))"
           (printer/print-ast (ast/grouping (ast/grouping (num-lit 1))))))
    (is (= "(- (- 1.0))"
           (printer/print-ast (ast/unary minus (ast/unary minus (num-lit 1))))))))

(deftest printing-every-binary-operator
  ;; operator -> "==" | "!=" | "<" | "<=" | ">" | ">=" | "+" | "-" | "*" | "/"
  (doseq [[type lexeme] [[:equal-equal "=="] [:bang-equal "!="]
                         [:less "<"] [:less-equal "<="]
                         [:greater ">"] [:greater-equal ">="]
                         [:plus "+"] [:minus "-"] [:star "*"] [:slash "/"]]]
    (is (= (str "(" lexeme " 1.0 2.0)")
           (printer/print-ast
            (ast/binary (num-lit 1) (op type lexeme) (num-lit 2))))
        (str "binary " lexeme))))

(deftest printing-makes-precedence-visible
  ;; The reason the printer is not a pretty-printer: both of these would read
  ;; as "1 + 2 * 3" in Lox syntax, but they are different trees.
  (let [one (num-lit 1) two (num-lit 2) three (num-lit 3)
        left-heavy  (ast/binary (ast/binary one plus two) star three)
        right-heavy (ast/binary one plus (ast/binary two star three))]
    (is (= "(* (+ 1.0 2.0) 3.0)" (printer/print-ast left-heavy)))
    (is (= "(+ 1.0 (* 2.0 3.0))" (printer/print-ast right-heavy)))
    (is (not= (printer/print-ast left-heavy)
              (printer/print-ast right-heavy)))))

(deftest printing-a-deep-tree
  ;; 1 - (2 * 3) < 4 == false  -- the expression section 5.1.3 uses to show
  ;; off the grammar, parenthesised here by hand since there is still no parser.
  (let [tree (ast/binary
              (ast/binary
               (ast/binary (num-lit 1)
                           minus
                           (ast/grouping (ast/binary (num-lit 2) star (num-lit 3))))
               (op :less "<")
               (num-lit 4))
              (op :equal-equal "==")
              (ast/literal false))]
    (is (= "(== (< (- 1.0 (group (* 2.0 3.0))) 4.0) false)"
           (printer/print-ast tree))))

  (testing "recursion is not depth-limited by the printer for realistic trees"
    (let [deep (reduce (fn [acc _] (ast/grouping acc)) (num-lit 1) (range 200))]
      (is (= 200 (count (re-seq #"\(group" (printer/print-ast deep)))))
      (is (str/includes? (printer/print-ast deep) "(group 1.0)"))
      (is (str/ends-with? (printer/print-ast deep)
                          (str "1.0" (str/join (repeat 200 ")"))))))))

(deftest parenthesize-helper
  (is (= "(name)" (printer/parenthesize "name")))
  (is (= "(name 1.0)" (printer/parenthesize "name" (num-lit 1))))
  (is (= "(name 1.0 2.0)"
         (printer/parenthesize "name" (num-lit 1) (num-lit 2))))
  (testing "every part is separated by exactly one space"
    (is (not (str/includes? (printer/parenthesize "n" (num-lit 1)) "  ")))))

(deftest print-ast-rejects-non-nodes
  (is (thrown? IllegalArgumentException (printer/print-ast nil)))
  (is (thrown? IllegalArgumentException (printer/print-ast 42)))
  (is (thrown? IllegalArgumentException (printer/print-ast plus))))

;;; ===========================================================================
;;; 6. Challenge 5.3 - the RPN printer
;;; ===========================================================================

(deftest rpn-the-challenges-example
  ;; "(1 + 2) * (4 - 3)" becomes "1 2 + 4 3 - *"
  (let [tree (ast/binary
              (ast/grouping (ast/binary (ast/literal 1) plus (ast/literal 2)))
              star
              (ast/grouping (ast/binary (ast/literal 4) minus (ast/literal 3))))]
    (is (= "1 2 + 4 3 - *" (printer/print-rpn tree)))))

(deftest rpn-basics
  (is (= "1.0" (printer/print-rpn (num-lit 1))))
  (is (= "nil" (printer/print-rpn (ast/literal nil))))
  (is (= "1.0 2.0 +" (printer/print-rpn (ast/binary (num-lit 1) plus (num-lit 2)))))
  (is (= "1.0 2.0 /" (printer/print-rpn (ast/binary (num-lit 1) slash (num-lit 2)))))

  (testing "grouping disappears: RPN needs no parentheses"
    (is (= (printer/print-rpn (num-lit 1))
           (printer/print-rpn (ast/grouping (num-lit 1)))))
    (is (= (printer/print-rpn (ast/binary (num-lit 1) plus (num-lit 2)))
           (printer/print-rpn
            (ast/grouping (ast/binary (num-lit 1) plus (num-lit 2)))))))

  (testing "unary minus is written ~ so it cannot be read as subtraction"
    (is (= "123.0 ~" (printer/print-rpn (ast/unary minus (num-lit 123)))))
    (is (not= (printer/print-rpn (ast/unary minus (num-lit 1)))
              (printer/print-rpn (ast/binary (num-lit 1) minus (num-lit 1))))))

  (testing "unary ! keeps its lexeme - Lox has no binary !"
    (is (= "true !" (printer/print-rpn (ast/unary bang (ast/literal true)))))))

(deftest rpn-is-evaluable-left-to-right
  ;; The real test of an RPN rendering: running the output on a stack machine
  ;; must give the same answer as evaluating the tree.
  (letfn [(eval-rpn [s]
            (->> (str/split s #"\s+")
                 (reduce (fn [stack tok]
                           (case tok
                             "+" (let [[b a & r] stack] (conj r (+ a b)))
                             "-" (let [[b a & r] stack] (conj r (- a b)))
                             "*" (let [[b a & r] stack] (conj r (* a b)))
                             "/" (let [[b a & r] stack] (conj r (/ a b)))
                             "~" (let [[a & r] stack] (conj r (- a)))
                             (conj stack (Double/parseDouble tok))))
                         '())
                 first))
          (eval-tree [e]
            (ast/accept e {:literal  :value
                           :grouping #(eval-tree (:expression %))
                           :unary    #(- (eval-tree (:right %)))
                           :binary   #(let [a (eval-tree (:left %))
                                            b (eval-tree (:right %))]
                                        (case (:type (:operator %))
                                          :plus  (+ a b)
                                          :minus (- a b)
                                          :star  (* a b)
                                          :slash (/ a b)))}))]
    (doseq [tree [(ast/binary (num-lit 1) plus (num-lit 2))
                  (ast/binary (ast/grouping (ast/binary (num-lit 1) plus (num-lit 2)))
                              star
                              (ast/grouping (ast/binary (num-lit 4) minus (num-lit 3))))
                  (ast/binary (ast/unary minus (num-lit 5)) star (num-lit 3))
                  (ast/binary (num-lit 1)
                              minus
                              (ast/binary (num-lit 2) star (num-lit 3)))
                  (ast/unary minus (ast/unary minus (num-lit 7)))]]
      (is (== (eval-tree tree) (eval-rpn (printer/print-rpn tree)))
          (str "RPN of " (printer/print-ast tree))))))

;;; ===========================================================================
;;; 7. Putting chapters 4 and 5 together
;;; ===========================================================================

(deftest trees-can-be-built-from-scanned-tokens
  ;; There is no parser until chapter 6, but the tokens the scanner produces
  ;; must already be usable as node fields, literals and all.
  (let [tokens (vec (scanner/scan-tokens "-123 * (45.67)"))
        [t-minus t-123 t-star _lparen t-4567] tokens]
    (is (= :minus  (:type t-minus)))
    (is (= :number (:type t-123)))
    (is (= :star   (:type t-star)))
    (is (= :number (:type t-4567)))
    (let [tree (ast/binary (ast/unary t-minus (ast/literal (:literal t-123)))
                           t-star
                           (ast/grouping (ast/literal (:literal t-4567))))]
      (is (ast/expr? tree))
      (is (= "(* (- 123.0) (group 45.67))" (printer/print-ast tree)))
      (is (= "123.0 ~ 45.67 *" (printer/print-rpn tree)))))

  (testing "a scanned string literal keeps its value in the tree"
    (let [tokens (vec (scanner/scan-tokens "\"hi\""))
          tree   (ast/literal (:literal (first tokens)))]
      (is (= "hi" (printer/print-ast tree)))))

  (testing "scanned keyword literals"
    (doseq [[src value text] [["true" true "true"]
                              ["false" false "false"]
                              ["nil" nil "nil"]]]
      (let [t (first (scanner/scan-tokens src))]
        (is (= (keyword src) (:type t)))
        (is (= text (printer/print-ast (ast/literal value))))))))

(deftest chapter-demo-main-prints-both-renderings
  (let [[_ lines] (u/capture-out #(printer/-main))]
    (is (= 2 (count lines)))
    (is (str/includes? (first lines) "(* (- 123) (group 45.67))"))
    (is (str/includes? (second lines) "123 ~ 45.67 *"))))

(deftest no-errors-were-reported-by-any-of-this
  ;; Chapter 5 adds no new error paths: building and printing trees never
  ;; touches the scanner's error machinery.
  (printer/print-ast (printer/demo-expression))
  (printer/print-rpn (printer/demo-expression))
  (is (not (u/had-error?)))
  (is (= [] (u/errors))))
