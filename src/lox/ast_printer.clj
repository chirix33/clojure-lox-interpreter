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
;;;;
;;;; UPDATED IN CHAPTER 8 - Statements and State. Two changes:
;;;;
;;;;   * the two expression visitors handle `:variable` and `:assign`. Neither
;;;;     wanted to: `defvisitor` refused to compile this file until they did,
;;;;     which is the compile-time guarantee Java's `Expr.Visitor<R>` gives the
;;;;     book and the reason chapter 5 bothered to implement the check.
;;;;
;;;;   * a *third* visitor, `stmt-printer`, over the new statement family, and
;;;;     `print-program` to render a whole parsed script. The book has no
;;;;     equivalent - it drops the AST printer after chapter 7 - but `clox
;;;;     --ast` is how chapters 5 and 6 are demonstrated from the command line,
;;;;     and a chapter 8 script that printed only its first expression would
;;;;     have stopped being a demonstration of anything.
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
             (parenthesize (:lexeme operator) right))

  ;; Chapter 6, challenge 6.2. Printed as a three-operand prefix form:
  ;;
  ;;     a ? b : c   ->   (?: a b c)
  ;;
  ;; "?:" names the whole operator, the way "group" names a grouping - there is
  ;; no single operator token to take a lexeme from, since the construct is
  ;; spelled with two.
  (:conditional [{:keys [condition then-branch else-branch]}]
                (parenthesize "?:" condition then-branch else-branch))

  ;; Chapter 8. A variable reference prints as its bare name, the way a
  ;; literal prints as its bare value: there is no operator and no structure
  ;; to show, so parentheses would add noise and no information.
  (:variable [{:keys [name]}]
             (:lexeme name))

  ;; Chapter 8. `(= a 1)`. The name is printed directly rather than visited,
  ;; because an assignment target is a *token*, not a sub-expression - the
  ;; distinction section 8.4.1 draws between l-values and r-values is visible
  ;; in the printed form, which is exactly what this printer is for.
  (:assign   [{:keys [name value]}]
             (str "(= " (:lexeme name) " " (ast/accept value printer) ")"))

  ;; Chapter 9. `(or a b)`, `(and a b)` - the operator's own lexeme, exactly
  ;; as `:binary` does, because that is what the operator is called.
  ;;
  ;; The printed form is therefore *identical* to what a `:binary` node with
  ;; the same operator would produce. That is not a defect: this printer shows
  ;; the shape of the tree, and the shape really is the same. What differs is
  ;; when the operands are evaluated, which is behaviour, and behaviour is not
  ;; something a static rendering can show. (A Lisp reader would agree, for
  ;; that matter: `and` and `or` are special forms there for the same reason
  ;; they are a separate node here, and they still print as lists.)
  (:logical  [{:keys [left operator right]}]
             (parenthesize (:lexeme operator) left right)))

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
                       (:lexeme operator))))

  ;; Chapter 6, challenge 6.2. RPN has no standard spelling for a ternary, so
  ;; we use the obvious extension: all three operands, then the operator.
  ;; `?:` is unambiguous because it is the only three-operand form in Lox, so a
  ;; stack machine reading this can still pop exactly the right arity.
  (:conditional [{:keys [condition then-branch else-branch]}]
                (str (rpn-of condition) " " (rpn-of then-branch) " "
                     (rpn-of else-branch) " ?:"))

  ;; Chapter 8. A name is an operand, so it stands alone.
  (:variable [{:keys [name]}]
             (:lexeme name))

  ;; Chapter 8. Postfix, like every other operator: value, target, `=`. The
  ;; target is still a name and not a computed operand, so a stack machine
  ;; reading this has to treat `=` specially - which is the same observation
  ;; the book makes about l-values, arrived at from the other direction.
  (:assign   [{:keys [name value]}]
             (str (rpn-of value) " " (:lexeme name) " ="))

  ;; Chapter 9. Postfix like any other binary operator - but note that the
  ;; result is no longer faithfully evaluable by a stack machine, and for a
  ;; reason worth naming rather than hiding. RPN is strict: a stack machine
  ;; pops two operands that have *already* been computed, so `a b or` cannot
  ;; short-circuit. Rendering is still well defined and still round-trips the
  ;; tree's shape, which is what this printer is for; faithfully representing
  ;; lazy evaluation would need a notation with a concept of unevaluated code,
  ;; which RPN by construction does not have.
  (:logical  [{:keys [left operator right]}]
             (str (rpn-of left) " " (rpn-of right) " " (:lexeme operator))))

(defn print-rpn
  "Challenge 5.3: render `expr` in reverse Polish notation."
  [expr]
  (ast/accept expr rpn))

;;; ---------------------------------------------------------------------------
;;; Chapter 8 - printing statements
;;;
;;; Not in the book. The same Lisp-style, fully parenthesised convention as
;;; above, extended to the four statement forms:
;;;
;;;   print a + 1;        (print (+ a 1.0))
;;;   a + 1;              (; (+ a 1.0))
;;;   var a;              (var a)
;;;   var a = 1;          (var a 1.0)
;;;   { var a; print a; } (block (var a) (print a))
;;;
;;; and, from chapter 9:
;;;
;;;   if (a) b;             (if a (; b))
;;;   if (a) b; else c;     (if a (; b) (; c))
;;;   while (a) b;          (while a (; b))
;;;   for (var i = 0; i < 2; i = i + 1) b;
;;;                         (block (var i 0.0)
;;;                                (while (< i 2.0)
;;;                                  (block (; b) (; (= i (+ i 1.0))))))
;;;   break;                (break)
;;;
;;; That `for` line is the most useful thing this printer does in chapter 9.
;;; The desugaring of section 9.5.1 is invisible in the source and invisible
;;; in the interpreter, so a printed tree is the only place a reader can
;;; actually *see* that `for` became a block around a while around a block -
;;; which is why `clox --ast` on a `for` loop is worth running, and why the
;;; chapter 9 tests assert on these strings rather than on nested maps.
;;;
;;; An expression statement is tagged `;` because that semicolon is the only
;;; thing in the source that marks it, and because a statement that printed
;;; identically to its own expression would hide the one fact worth seeing -
;;; that a value is being computed and thrown away.
;;;
;;; `var` with and without an initializer print differently, with two elements
;;; rather than three. That is the point: the absent initializer is a missing
;;; *node*, not a node holding nil, and `(var a)` versus `(var a nil)` is the
;;; difference between "declared, implicitly nil" and "explicitly assigned
;;; nil" - a difference challenge 8.2 turns into different behaviour.
;;; ---------------------------------------------------------------------------

(declare stmt-printer)

(defn- stmt-of [stmt] (ast/accept stmt stmt-printer))

(ast/defvisitor stmt-printer :stmt
  "A `Stmt.Visitor<String>`, had the book written one."
  (:expression [{:keys [expression]}]
               (parenthesize ";" expression))
  (:print      [{:keys [expression]}]
               (parenthesize "print" expression))
  (:var        [{:keys [name initializer]}]
               (if (some? initializer)
                 (parenthesize (str "var " (:lexeme name)) initializer)
                 (str "(var " (:lexeme name) ")")))
  (:block      [{:keys [statements]}]
               (str "(block"
                    (str/join (map #(str " " (stmt-of %)) statements))
                    ")"))

  ;; Chapter 9.
  ;;
  ;;   if (a) b;           (if a (; b))
  ;;   if (a) b; else c;   (if a (; b) (; c))
  ;;
  ;; Two elements or three, mirroring `var` with and without an initializer,
  ;; and for the same reason: the absent else branch is a missing *node*, and
  ;; `(if a (; b))` versus `(if a (; b) (block))` is the difference between
  ;; "no else clause" and "an else clause containing nothing". The second is
  ;; writable in Lox (`if (a) b; else {}`) and means something subtly
  ;; different, so the two must not print alike.
  (:if         [{:keys [condition then-branch else-branch]}]
               (str "(if " (ast/accept condition printer)
                    " " (stmt-of then-branch)
                    (when (some? else-branch) (str " " (stmt-of else-branch)))
                    ")"))

  (:while      [{:keys [condition body]}]
               (str "(while " (ast/accept condition printer)
                    " " (stmt-of body) ")"))

  ;; Challenge 9.3. No operands, so it prints as a bare atom - the one
  ;; statement form with nothing inside it.
  (:break      [_]
               "(break)"))

(defn print-statement
  "Render one statement."
  [stmt]
  (ast/accept stmt stmt-printer))

(defn print-program
  "Render a parsed program - a sequence of statements - one per line.

  This is what `clox --ast` prints from chapter 8 onwards."
  [statements]
  (str/join "\n" (map print-statement statements)))

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
