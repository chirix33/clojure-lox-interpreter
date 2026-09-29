;;;; =============================================================================
;;;; Chapter 5 - Representing Code
;;;;
;;;; Chapter 4 turned source text into a *flat* sequence of tokens. A flat list
;;;; cannot express nesting, and expressions nest arbitrarily deeply, so this
;;;; chapter defines the richer representation the parser will produce and the
;;;; interpreter will consume: an **abstract syntax tree**.
;;;;
;;;; The subset of Lox's syntactic grammar the book introduces here (5.1.3):
;;;;
;;;;   expression -> literal | unary | binary | grouping ;
;;;;   literal    -> NUMBER | STRING | "true" | "false" | "nil" ;
;;;;   grouping   -> "(" expression ")" ;
;;;;   unary      -> ( "-" | "!" ) expression ;
;;;;   binary     -> expression operator expression ;
;;;;   operator   -> "==" | "!=" | "<" | "<=" | ">" | ">="
;;;;              |  "+"  | "-"  | "*" | "/" ;
;;;;
;;;; (That grammar is ambiguous - chapter 6 fixes it with precedence levels.
;;;; The *tree shapes* it needs, however, are exactly the four defined below.)
;;;;
;;;; --------------------------------------------------------------------------
;;;; Translating the book's design to Clojure
;;;; --------------------------------------------------------------------------
;;;;
;;;; 1. NODES ARE MAPS, NOT CLASSES.
;;;;    The book writes an abstract `Expr` class with a nested subclass per
;;;;    production, each a bag of final fields and no behaviour. Section 5.2.1
;;;;    ("Disoriented objects") points out that this is the awkward bit: these
;;;;    types exist only to carry data between the parser and the interpreter,
;;;;    and "this style is very natural in functional languages". So here a node
;;;;    is just an immutable map tagged with its production:
;;;;
;;;;      {:node :binary :left <expr> :operator <token> :right <expr>}
;;;;
;;;;    The tag key is `:node` rather than `:type` on purpose: token maps from
;;;;    chapter 4 already use `:type`, and from chapter 8 onwards statement
;;;;    nodes share names with token types (`:print`, `:var`, `:while`, ...).
;;;;    A distinct key keeps `lox.token/token?` and `lox.ast/expr?` from ever
;;;;    confusing a token for a node.
;;;;
;;;; 2. THE GENERATOR IS A MACRO, NOT A CODE-EMITTING SCRIPT.
;;;;    Section 5.2.2 ("Metaprogramming the trees") writes `GenerateAst.java`,
;;;;    a tool that prints `Expr.java`, because Java cannot describe "a name and
;;;;    a list of typed fields" and get the boilerplate for free. Clojure can:
;;;;    `define-ast` below takes the same declarative description the book
;;;;    passes to `defineAst()` and expands - at compile time, in place, with no
;;;;    generated file to check in - into constructors, predicates, a field
;;;;    table and a node-type set. The book notes that "an actual scripting
;;;;    language would be a better fit for this than Java"; this is that fit.
;;;;
;;;; 3. THE VISITOR IS A MAP OF FUNCTIONS.
;;;;    Section 5.3 is really about the *expression problem*: OO makes new node
;;;;    types cheap and new operations expensive, and the Visitor pattern buys
;;;;    back the second at the cost of ceremony. A `Visitor<R>` interface is,
;;;;    stripped of Java, a lookup from node kind to a function returning R -
;;;;    which is a map. `accept` does that lookup, and `defvisitor` checks at
;;;;    *compile time* that every production is handled, recovering the one real
;;;;    benefit of the Java interface: the compiler yelling at you when you add
;;;;    a node type and forget a visit method.
;;;;
;;;;      book                              here
;;;;      ----------------------------      ----------------------------------
;;;;      abstract class Expr               (no class; maps tagged with :node)
;;;;      static class Expr.Binary          (binary left operator right)
;;;;      new Expr.Binary(l, op, r)         (binary l op r)
;;;;      expr instanceof Expr.Binary       (binary? expr)
;;;;      interface Expr.Visitor<R>         a map {:binary f, :grouping f, ...}
;;;;      expr.accept(visitor)              (accept expr visitor)
;;;;      GenerateAst.defineAst(...)        (define-ast expr {...})
;;;;
;;;; Later chapters extend the grammar (chapter 8 adds `variable`/`assign` plus
;;;; a whole `stmt` family, 9 `logical`, 10 `call`, 12 `get`/`set`/`this`, 13
;;;; `super`). Each is one more entry in the `define-ast` map below - no other
;;;; file has to change in order to *declare* it.
;;;; =============================================================================
(ns lox.ast
  (:require [clojure.string :as str]
            [lox.token :as tok]))

;;; ---------------------------------------------------------------------------
;;; Field kinds
;;;
;;; The book's type descriptions ("Expr left, Token operator, Expr right") are
;;; there for javac. Clojure will not check them for us, so we keep the same
;;; information and check it ourselves at construction time. Catching a
;;; malformed node where it is *built* (in the parser) instead of where it is
;;; *walked* (in the interpreter, several recursive calls later) is worth the
;;; handful of nanoseconds.
;;; ---------------------------------------------------------------------------

(declare expr?)

(def field-kinds
  "Kind keyword -> [predicate, human-readable description].

  `:object` is the book's `Object value`: any Lox value at all, including nil
  (Lox's `nil`), so its predicate always holds."
  {:expr   [(fn [x] (expr? x))      "an expression node"]
   :token  [(fn [x] (tok/token? x)) "a token"]
   :object [(constantly true)       "any value"]})

(defn check-field!
  "Throw a helpful exception if `value` is not a valid `kind` for field
  `field` of a `node-type` node. Returns `value` so it can be used inline."
  [node-type field kind value]
  (let [[pred description] (or (get field-kinds kind)
                               (throw (IllegalArgumentException.
                                       (str "Unknown field kind: " kind))))]
    (when-not (pred value)
      (throw (IllegalArgumentException.
              (str "Cannot build " node-type " node: field " field
                   " must be " description ", got: " (pr-str value)))))
    value))

;;; ---------------------------------------------------------------------------
;;; define-ast - the book's GenerateAst, as a macro
;;; ---------------------------------------------------------------------------

(defmacro define-ast
  "Define an abstract-syntax-tree family. Book: `GenerateAst.defineAst()`.

  `base` is a symbol naming the family (`expr`, and from chapter 8 `stmt`).
  `types` is a literal map from node-type keyword to a vector of typed fields,
  each written `[kind field-name]` - the same information carried by the book's
  \"Binary : Expr left, Token operator, Expr right\" description strings.

  For `(define-ast expr {:binary [[:expr left] [:token operator] ...] ...})`
  this expands into:

    expr-types       the field table, as data (so tools can introspect it)
    expr-node-types  the set of node-type keywords
    expr?            predicate: is x a node of this family?
    binary           constructor, (binary left operator right)
    binary?          predicate
    ... one constructor and one predicate per entry ...

  Field order in the vector is the constructor's argument order, which is the
  book's field order, which is source order. That matters for readability:
  `(binary left operator right)` reads like the expression it represents."
  [base types]
  (let [base-name    (name base)
        types-sym    (symbol (str base-name "-types"))
        node-set-sym (symbol (str base-name "-node-types"))
        pred-sym     (symbol (str base-name "?"))
        class-name   (str/capitalize base-name)
        defs
        (for [[node-type fields] types]
          (let [ctor-name (symbol (name node-type))
                ctor-pred (symbol (str (name node-type) "?"))
                args      (mapv (fn [[_ field]] (symbol (name field))) fields)
                checks    (map (fn [[kind field]]
                                 `(check-field! ~node-type ~(keyword (name field))
                                                ~kind ~(symbol (name field))))
                               fields)
                pairs     (mapcat (fn [[_ field]]
                                    [(keyword (name field)) (symbol (name field))])
                                  fields)]
            `(do
               (defn ~ctor-name
                 ~(str "Build a " (name node-type) " node. Book: `new "
                       class-name "." (str/capitalize (name node-type)) "(...)`.")
                 ~args
                 ~@checks
                 (array-map :node ~node-type ~@pairs))
               (defn ~ctor-pred
                 ~(str "True if `x` is a " (name node-type) " node. Book: `x "
                       "instanceof " class-name "."
                       (str/capitalize (name node-type)) "`.")
                 [~'x]
                 (and (map? ~'x) (= ~node-type (:node ~'x)))))))]
    `(do
       (def ~types-sym
         ~(str "Field table for the " base-name " family: node type -> "
               "[[kind field] ...]. Book: the list of type descriptions "
               "passed to `defineAst()`.")
         (quote ~types))
       (def ~node-set-sym
         ~(str "Every " base-name " node type. Book: the set of `" class-name
               "` subclasses.")
         ~(set (keys types)))
       (defn ~pred-sym
         ~(str "True if `x` is any " base-name " node.")
         [~'x]
         (and (map? ~'x) (contains? ~node-set-sym (:node ~'x))))
       ~@defs
       ~(keyword base-name))))

;;; ---------------------------------------------------------------------------
;;; The chapter 5 expression grammar
;;;
;;; Compare with the book's GenerateAst.main():
;;;
;;;   "Binary   : Expr left, Token operator, Expr right",
;;;   "Grouping : Expr expression",
;;;   "Literal  : Object value",
;;;   "Unary    : Token operator, Expr right"
;;; ---------------------------------------------------------------------------

(define-ast expr
  {:binary   [[:expr left] [:token operator] [:expr right]]
   :grouping [[:expr expression]]
   :literal  [[:object value]]
   :unary    [[:token operator] [:expr right]]})

;;; ---------------------------------------------------------------------------
;;; Generic node access
;;; ---------------------------------------------------------------------------

(defn node-type
  "The production this node came from, e.g. :binary. Book: the node's class."
  [node]
  (:node node))

(defn fields-of
  "The declared fields of `node`, in source order, as [kind field] pairs.

  `field` is the symbol as written in the `define-ast` declaration; `field-key`
  turns it into the keyword the node map is actually keyed by."
  [node]
  (get expr-types (node-type node)))

(defn field-key
  "The map key a declared field name corresponds to: `left` -> :left."
  [field]
  (keyword (name field)))

(defn children
  "The direct sub-expressions of `node`, in source order.

  The book has no equivalent: in Java you reach for the specific fields you
  already know a subclass has. Having the shape available as *data* means
  generic traversals (a size counter, a depth check, a search) need no
  per-node-type code at all - which is the other half of the expression
  problem the chapter describes."
  [node]
  (into [] (for [[kind field] (fields-of node)
                 :when (= :expr kind)]
             (get node (field-key field)))))

;;; ---------------------------------------------------------------------------
;;; The visitor - section 5.3
;;; ---------------------------------------------------------------------------

(defn accept
  "Book: `expr.accept(visitor)`.

  A visitor is a map from node type to a one-argument function, so the double
  dispatch that the Java pattern needs two method calls to achieve is a single
  map lookup here. A missing entry throws rather than returning nil, mirroring
  the compile error Java would have given us."
  [node visitor]
  (when-not (expr? node)
    (throw (IllegalArgumentException.
            (str "Not an expression node: " (pr-str node)))))
  (let [nt (node-type node)
        f  (get visitor nt)]
    (when (nil? f)
      (throw (IllegalArgumentException.
              (str "Visitor has no method for " nt " nodes. Handled: "
                   (pr-str (vec (sort (keys visitor))))))))
    (f node)))

(defmacro defvisitor
  "Define a visitor over the expression family. Book: `implements Expr.Visitor<R>`.

  The shape mirrors the Java class, minus the ceremony:

    (defvisitor my-visitor
      \"Docstring.\"
      (:binary   [{:keys [left operator right]}] ...)
      (:grouping [{:keys [expression]}]          ...)
      (:literal  [{:keys [value]}]               ...)
      (:unary    [{:keys [operator right]}]      ...))

  Every node type must be handled; omitting one is a *compile-time* error,
  which is exactly the guarantee Java's `Visitor<R>` interface provides. Tag
  the name `^:partial` when an incomplete visitor is genuinely wanted (one
  that only cares about literals, say)."
  [name & body]
  (let [doc      (when (string? (first body)) (first body))
        clauses  (if doc (rest body) body)
        handled  (set (map first clauses))
        partial? (:partial (meta name))
        missing  (remove handled (keys expr-types))
        extra    (remove (set (keys expr-types)) handled)]
    (when (seq extra)
      (throw (ex-info (str "defvisitor " name ": unknown node type(s) "
                           (pr-str (vec extra)))
                      {:unknown (vec extra)})))
    (when (and (not partial?) (seq missing))
      (throw (ex-info (str "defvisitor " name ": no method for node type(s) "
                           (pr-str (vec missing))
                           ". Handle them, or tag the visitor ^:partial.")
                      {:missing (vec missing)})))
    `(def ~name
       ~@(when doc [doc])
       ~(into {} (for [[node-type argv & fn-body] clauses]
                   [node-type `(fn ~argv ~@fn-body)])))))
