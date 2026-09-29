;;;; =============================================================================
;;;; Chapter 5 - Representing Code  (section 5.4, "A (Not Very) Pretty Printer")
;;;;
;;;; The first thing anyone does with a new tree representation is print it, so
;;;; that the parser we write in chapter 6 can be checked by eye. The output is
;;;; deliberately *not* Lox syntax: re-printing `1 + 2 * 3` tells you nothing
;;;; about whether the `+` or the `*` ended up at the top of the tree. Instead
;;;; every node is fully parenthesised, Lisp style, so the nesting is explicit:
;;;;
;;;;     (* (- 123) (group 45.67))
;;;;
;;;; This file is also the chapter's demonstration that the visitor works. Book:
;;;; `AstPrinter implements Expr.Visitor<String>`; here, a map of functions
;;;; built by `lox.ast/defvisitor`, which is the same thing with less typing.
;;;;
;;;; Two visitors live here:
;;;;   print-ast   the book's AstPrinter (section 5.4)
;;;;   print-rpn   challenge 5.3 - reverse Polish notation
;;;;
;;;; Both exist to prove the point of section 5.3: adding a second operation
;;;; over the tree touched no node definition at all. In Java that is what the
;;;; Visitor pattern buys you; in Clojure it falls out of the data being data.
;;;; =============================================================================
(ns lox.ast-printer
  (:require [clojure.string :as str]
            [lox.ast :as ast]
            [lox.token :as tok]))

;;; ---------------------------------------------------------------------------
;;; Literal values
;;; ---------------------------------------------------------------------------

(defn literal->string
  "Render a literal's value. Book: `if (value == null) return \"nil\";` then
  `value.toString()`.

  Note that this is *not* chapter 7's `stringify`: it does not strip the
  trailing \".0\" from whole-number doubles, because the whole point of the AST
  printer is to show what is actually in the tree. A `NUMBER` token scanned
  from the source `123` carries the double 123.0, and this prints `123.0`.
  Clojure's `str` agrees with Java's `toString` on doubles, strings and
  booleans, so no special-casing is needed beyond nil."
  [value]
  (if (nil? value) "nil" (str value)))

;;; ---------------------------------------------------------------------------
;;; The AST printer - section 5.4
;;; ---------------------------------------------------------------------------

(declare printer)

(defn parenthesize
  "Book: `AstPrinter.parenthesize(name, exprs...)`. Wrap `name` and the printed
  form of each sub-expression in parentheses: `(+ 1 2)`.

  The recursive call goes back through `accept`, which is what lets one visitor
  print an arbitrarily deep tree."
  [name & exprs]
  (str "(" name
       (str/join (map #(str " " (ast/accept % printer)) exprs))
       ")"))

(ast/defvisitor printer
  "Book: `AstPrinter`, an `Expr.Visitor<String>`."
  (:binary   [{:keys [left operator right]}]
             (parenthesize (:lexeme operator) left right))
  (:grouping [{:keys [expression]}]
             (parenthesize "group" expression))
  (:literal  [{:keys [value]}]
             (literal->string value))
  (:unary    [{:keys [operator right]}]
             (parenthesize (:lexeme operator) right)))

(defn print-ast
  "Book: `AstPrinter.print(expr)`. Returns the string; it does not print it."
  [expr]
  (ast/accept expr printer))

;;; ---------------------------------------------------------------------------
;;; Challenge 5.3 - a reverse Polish notation printer
;;;
;;; "Define a visitor class for our syntax tree classes that takes an
;;;  expression, converts it to RPN, and returns the resulting string."
;;;
;;; (1 + 2) * (4 - 3)   ->   1 2 + 4 3 - *
;;;
;;; Grouping vanishes: in RPN the evaluation order is carried by the position
;;; of the operators, so parentheses have nothing left to say. That is the
;;; whole reason the notation exists.
;;;
;;; One deliberate decision: unary minus cannot simply emit "-", because
;;; `1 2 -` (binary subtraction) and `1 2 -` (negate, with a stray 1) would be
;;; indistinguishable. Unary negation is written `~`, which keeps the output
;;; unambiguous and therefore actually evaluable. Unary `!` needs no such
;;; treatment: Lox has no binary `!`.
;;; ---------------------------------------------------------------------------

(declare rpn)

(defn- rpn-of [expr] (ast/accept expr rpn))

(def unary-rpn-operators
  "Prefix operator token type -> its unambiguous RPN spelling. See above."
  {:minus "~"
   :bang  "!"})

(ast/defvisitor rpn
  "Challenge 5.3: an `Expr.Visitor<String>` producing reverse Polish notation."
  (:binary   [{:keys [left operator right]}]
             (str (rpn-of left) " " (rpn-of right) " " (:lexeme operator)))
  (:grouping [{:keys [expression]}]
             (rpn-of expression))
  (:literal  [{:keys [value]}]
             (literal->string value))
  (:unary    [{:keys [operator right]}]
             (str (rpn-of right) " "
                  (get unary-rpn-operators (:type operator)
                       (:lexeme operator)))))

(defn print-rpn
  "Challenge 5.3: render `expr` in reverse Polish notation."
  [expr]
  (ast/accept expr rpn))

;;; ---------------------------------------------------------------------------
;;; The chapter's demo
;;;
;;; Book: the throwaway `main()` at the end of section 5.4, which hand-builds
;;; the tree for `-123 * (45.67)` because there is no parser yet. The book says
;;; to delete it afterwards; we keep it as a runnable demonstration instead,
;;; since it is the only way to see chapter 5 do anything from the command line:
;;;
;;;   clojure -M -m lox.ast-printer
;;; ---------------------------------------------------------------------------

(defn demo-expression
  "The book's hand-built tree: `(* (- 123) (group 45.67))`."
  []
  (ast/binary
   (ast/unary (tok/make-token :minus "-" nil 1)
              (ast/literal 123))
   (tok/make-token :star "*" nil 1)
   (ast/grouping (ast/literal 45.67))))

(defn -main
  [& _args]
  (let [expr (demo-expression)]
    (println "AST: " (print-ast expr))
    (println "RPN: " (print-rpn expr))
    (flush)))
