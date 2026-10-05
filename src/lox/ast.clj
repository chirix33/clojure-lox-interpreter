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
;;;;
;;;; UPDATED IN CHAPTER 6: `:conditional` was added for challenge 6.2's ternary
;;;; operator. The four productions listed above are still exactly chapter 5's;
;;;; see the comment on the node itself.
;;;;
;;;; --------------------------------------------------------------------------
;;;; UPDATED IN CHAPTER 8 - Statements and State
;;;; --------------------------------------------------------------------------
;;;;
;;;; This is the chapter the file was written in anticipation of, and the one
;;;; that tests the claim above. It adds, in total:
;;;;
;;;;   * two expression productions - `:variable` (8.2.1) and `:assign` (8.4.1)
;;;;   * a second *family* of nodes, `stmt`, with four productions:
;;;;     `:block`, `:expression`, `:print` and `:var`
;;;;
;;;; The book's `GenerateAst.main()` grows a second `defineAst` call, and the
;;;; book notes the parameterisation was there all along ("That's why we passed
;;;; in `Expr` as a parameter to `defineAst()`"). `define-ast` took its family
;;;; name as a parameter from the start too, so the statement grammar really is
;;;; one more call. Three things *did* have to change, and each one was a place
;;;; where chapter 5 had assumed there would only ever be one family:
;;;;
;;;;   1. a registry (`ast-families`, `node-type->family`), so that generic
;;;;      helpers like `fields-of` and `accept` can ask which grammar a node
;;;;      came from instead of assuming `expr`;
;;;;   2. `defvisitor` takes an optional family keyword, so that completeness
;;;;      is checked against the right field table - the book's reason for
;;;;      needing a second interface, `Stmt.Visitor<Void>`, at all;
;;;;   3. three new field kinds - `:stmt`, `:stmts` and `:expr-or-nil` - for
;;;;      `List<Stmt> statements` and the nullable `Expr initializer`.
;;;;
;;;; Section 8.1.1's design point is that expressions and statements are
;;;; *disjoint*: nowhere does the grammar accept either one. The book gets that
;;;; from two unrelated Java class hierarchies. Here it comes from the registry
;;;; plus `check-field!`: a `:stmt` handed to an `:expr` field throws where the
;;;; node is built, which is the same error javac would report, at the same
;;;; point in the pipeline.
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

(declare expr? stmt?)

(def field-kinds
  "Kind keyword -> [predicate, human-readable description].

  `:object` is the book's `Object value`: any Lox value at all, including nil
  (Lox's `nil`), so its predicate always holds.

  ADDED IN CHAPTER 8. Three kinds join the original three, and each one names a
  Java type the book's `defineAst` descriptions already use:

    :expr-or-nil  the book's nullable `Expr initializer` on `Stmt.Var`. A
                  `var a;` declaration has no initializer, and the book stores
                  a literal Java null there - *not* a `Literal(nil)` node - so
                  that \"implicitly nil\" (section 8.3.1) and challenge 8.2's
                  \"uninitialized is an error\" can tell the two apart.
    :stmt         `Stmt body`; first needed by chapter 9's `if` and `while`.
    :stmts        the book's `List<Stmt> statements` on `Stmt.Block`."
  {:expr        [(fn [x] (expr? x))               "an expression node"]
   :expr-or-nil [(fn [x] (or (nil? x) (expr? x))) "an expression node or nil"]
   :stmt        [(fn [x] (stmt? x))               "a statement node"]
   :stmts       [(fn [x] (and (sequential? x) (every? stmt? x)))
                 "a sequence of statement nodes"]
   :token       [(fn [x] (tok/token? x))          "a token"]
   :object      [(constantly true)                "any value"]})

(def child-field-kinds
  "The field kinds that hold *other nodes*, and so are traversed by `children`."
  #{:expr :expr-or-nil :stmt :stmts})

;;; ---------------------------------------------------------------------------
;;; The family registry
;;;
;;; ADDED IN CHAPTER 8. Chapter 5 had exactly one family of nodes, so
;;; `define-ast` could hard-code the names `expr-types` / `expr?`, and the
;;; generic helpers below could name `expr-types` directly. Section 8.1.1 adds
;;; a second, disjoint family:
;;;
;;;   "There is no place in the grammar where both an expression and a
;;;    statement are allowed. [...] Since the two syntaxes are disjoint, we
;;;    don't need a single base class that they all inherit from."
;;;
;;; The book gets its separation from two unrelated Java class hierarchies,
;;; which is also what lets javac catch "passing a statement to a Java method
;;; that expects an expression". We have no classes, so the registry below is
;;; where that knowledge lives: it maps each family to its field table and each
;;; node type to the family that owns it. `check-field!` then rejects a
;;; statement handed to an `:expr` field - the same mistake, caught at the same
;;; moment, construction time.
;;; ---------------------------------------------------------------------------

(def ast-families
  "family keyword -> that family's field table. Book: the two `defineAst` calls."
  (atom {}))

(def node-type->family
  "node-type keyword -> the family that owns it, e.g. :binary -> :expr.

  Node types are unique across families *by assertion*: `register-family!`
  refuses a duplicate, so no node map is ever ambiguous about which grammar it
  came from. This is why `:print` can be a statement node type while `:print`
  is also a *token* type - tokens are keyed by `:type`, nodes by `:node`."
  (atom {}))

(defn register-family!
  "Record `types` as the field table of `family`. Called by `define-ast`."
  [family types]
  (doseq [nt (keys types)]
    (when-let [owner (get @node-type->family nt)]
      (when (not= owner family)
        (throw (IllegalArgumentException.
                (str "Node type " nt " is already owned by the " owner
                     " family; it cannot also belong to " family "."))))))
  (swap! ast-families assoc family types)
  (swap! node-type->family into (zipmap (keys types) (repeat family)))
  family)

(defn family-types
  "The field table of `family`, or nil if no such family is defined."
  [family]
  (get @ast-families family))

(defn node-family
  "Which family `node` belongs to - :expr or :stmt - or nil if it is not a node."
  [node]
  (when (map? node) (get @node-type->family (:node node))))

(defn node?
  "True if `x` is an AST node of *any* family.
  Book: `x instanceof Expr || x instanceof Stmt`."
  [x]
  (some? (node-family x)))

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
  `(binary left operator right)` reads like the expression it represents.

  CHAPTER 8 adds the optional trailing `opts` map, with one key:

    :suffix  appended to every generated constructor and predicate name.

  The statement family needs it. Three of the book's `Stmt` subclasses are
  `Expression`, `Print` and `Var`, and `print`, `var` and `var?` are all taken
  in Clojure - `clojure.core/print`, the `var` *special form*, and
  `clojure.core/var?`. Defining those names here would shadow them, or for
  `var` break the reader outright. `:suffix \"-stmt\"` yields `print-stmt`,
  `var-stmt`, `block-stmt` and friends, which read better at the call sites
  anyway: in `(ast/var-stmt name init)` you can see a *statement* being built."
  [base types & [opts]]
  (let [base-name    (name base)
        suffix       (or (:suffix opts) "")
        family       (keyword base-name)
        types-sym    (symbol (str base-name "-types"))
        node-set-sym (symbol (str base-name "-node-types"))
        pred-sym     (symbol (str base-name "?"))
        class-name   (str/capitalize base-name)
        defs
        (for [[node-type fields] types]
          (let [ctor-name (symbol (str (name node-type) suffix))
                ctor-pred (symbol (str (name node-type) suffix "?"))
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
       (register-family! ~family ~types-sym)
       (defn ~pred-sym
         ~(str "True if `x` is any " base-name " node.")
         [~'x]
         (and (map? ~'x) (contains? ~node-set-sym (:node ~'x))))
       ~@defs
       ~family)))

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
   :unary    [[:token operator] [:expr right]]

   ;; Chapter 6, challenge 6.2 - the ternary conditional `c ? t : e`.
   ;;
   ;; The book has no such node; this is the first demonstration that the
   ;; chapter-5 claim above actually holds. Adding a production to the language
   ;; is one line here, and the only other places that had to change are the
   ;; ones that genuinely *do* something per node type - the two visitors in
   ;; `lox.ast-printer`, which `defvisitor` refused to compile until they
   ;; handled it. That compile-time nag is exactly the guarantee the book gets
   ;; from implementing `Expr.Visitor<R>`.
   ;;
   ;; It is a node of its own rather than a nested :binary pair because the
   ;; three operands are genuinely one construct: chapter 7 must evaluate the
   ;; condition and then exactly one branch, which a :binary tree could not
   ;; express without special-casing the `?` and `:` tokens anyway.
   :conditional [[:expr condition] [:expr then-branch] [:expr else-branch]]

   ;; ---------------------------------------------------------------------
   ;; Chapter 8 - Statements and State
   ;;
   ;;   "Variable : Token name"            (section 8.2.1)
   ;;   "Assign   : Token name, Expr value" (section 8.4.1)
   ;;
   ;; :variable is "simply a wrapper around the token for the variable name".
   ;; It holds a *token*, not a string, even though the environment is keyed
   ;; by the bare lexeme - because a runtime error has to name a line, and the
   ;; line is on the token (section 8.3: "The exception contains the
   ;; variable's token so we can tell the user where in their code they
   ;; messed up").
   ;;
   ;; :assign's `name` is also a token and *not* an :expr, and section 8.4.1
   ;; is emphatic about why: the left side of an `=` is not an expression that
   ;; produces a value, it is an l-value naming a storage location. Giving the
   ;; field the `:token` kind rather than `:expr` makes `check-field!` refuse
   ;; `(assign <some expr> v)` at construction time, so the distinction the
   ;; book draws in prose is enforced by the node itself.
   :variable [[:token name]]
   :assign   [[:token name] [:expr value]]})

;;; ---------------------------------------------------------------------------
;;; The chapter 8 statement grammar - section 8.1.1
;;;
;;; Compare with the book's second `defineAst` call:
;;;
;;;   "Block      : List<Stmt> statements",
;;;   "Expression : Expr expression",
;;;   "Print      : Expr expression",
;;;   "Var        : Token name, Expr initializer"
;;;
;;; and with the grammar those four nodes cover (sections 8.1, 8.2.1, 8.5.2):
;;;
;;;   program     -> declaration* EOF ;
;;;   declaration -> varDecl | statement ;
;;;   varDecl     -> "var" IDENTIFIER ( "=" expression )? ";" ;
;;;   statement   -> exprStmt | printStmt | block ;
;;;   exprStmt    -> expression ";" ;
;;;   printStmt   -> "print" expression ";" ;
;;;   block       -> "{" declaration* "}" ;
;;;
;;; `:suffix "-stmt"` is explained on `define-ast`: `print`, `var` and `var?`
;;; are names Clojure has already spoken for.
;;;
;;; Note that `:expression` and `:print` have *identical* shapes - both are a
;;; single `Expr expression` - and are still two node types rather than one
;;; tagged node. That is the book's split, and it is the right one: the two
;;; differ in nothing but behaviour, and behaviour is exactly what the visitor
;;; dispatches on. One node type with a flag would push that dispatch into an
;;; `if` inside a visit method, which is the thing the Visitor pattern exists
;;; to avoid.
;;; ---------------------------------------------------------------------------

(define-ast stmt
  {:block      [[:stmts statements]]
   :expression [[:expr expression]]
   :print      [[:expr expression]]
   :var        [[:token name] [:expr-or-nil initializer]]}
  {:suffix "-stmt"})

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
  turns it into the keyword the node map is actually keyed by.

  CHAPTER 8: looks the family up in the registry rather than assuming `expr`,
  so this works unchanged for statements."
  [node]
  (get (family-types (node-family node)) (node-type node)))

(defn field-key
  "The map key a declared field name corresponds to: `left` -> :left."
  [field]
  (keyword (name field)))

(defn children
  "The direct child *nodes* of `node`, in source order.

  The book has no equivalent: in Java you reach for the specific fields you
  already know a subclass has. Having the shape available as *data* means
  generic traversals (a size counter, a depth check, a search) need no
  per-node-type code at all - which is the other half of the expression
  problem the chapter describes.

  CHAPTER 8: `:stmt` and `:stmts` fields are children too, and a `:stmts`
  field contributes all of its statements in order, so one traversal can walk
  a whole program rather than a single expression. An absent `:expr-or-nil`
  (a `var a;` with no initializer) contributes nothing, because there is no
  node there to visit."
  [node]
  (into [] (mapcat (fn [[kind field]]
                     (let [v (get node (field-key field))]
                       (cond
                         (= :stmts kind) (seq v)
                         (nil? v)        nil
                         :else           [v])))
                   (filter (comp child-field-kinds first) (fields-of node)))))

;;; ---------------------------------------------------------------------------
;;; The visitor - section 5.3
;;; ---------------------------------------------------------------------------

(defn accept
  "Book: `expr.accept(visitor)`.

  A visitor is a map from node type to a one-argument function, so the double
  dispatch that the Java pattern needs two method calls to achieve is a single
  map lookup here. A missing entry throws rather than returning nil, mirroring
  the compile error Java would have given us.

  CHAPTER 8: this is also `stmt.accept(this)`. The book needs a second
  interface, `Stmt.Visitor<Void>`, because a Java visitor is a type and the
  two families are unrelated types. A visitor here is a *map*, and a map over
  statement node types is the same kind of thing as a map over expression node
  types, so one `accept` serves both. The families stay separate where it
  matters - `defvisitor` checks completeness against one family, and
  `check-field!` refuses a statement in an expression slot."
  [node visitor]
  (when-not (node? node)
    (throw (IllegalArgumentException.
            (str "Not an AST node: " (pr-str node)))))
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
  that only cares about literals, say).

  CHAPTER 8: an optional family keyword may follow the name -

    (defvisitor executor :stmt
      \"Book: `Stmt.Visitor<Void>`.\"
      (:expression [{:keys [expression]}] ...)
      ...)

  It defaults to `:expr`, so every chapter 5-7 call site is unchanged. The
  family decides which field table completeness is checked against, which is
  the whole point: a `Stmt.Visitor` that forgot `visitBlockStmt` must not
  compile, and an expression visitor must not be allowed to \"handle\" :print."
  [name & body]
  (let [family   (if (keyword? (first body)) (first body) :expr)
        body     (if (keyword? (first body)) (rest body) body)
        types    (or (family-types family)
                     (throw (ex-info (str "defvisitor " name ": unknown node "
                                          "family " family ". Known: "
                                          (pr-str (vec (sort (keys @ast-families)))))
                                     {:family family})))
        doc      (when (string? (first body)) (first body))
        clauses  (if doc (rest body) body)
        handled  (set (map first clauses))
        partial? (:partial (meta name))
        missing  (remove handled (keys types))
        extra    (remove (set (keys types)) handled)]
    (when (seq extra)
      (throw (ex-info (str "defvisitor " name ": " (pr-str (vec extra))
                           " is/are not " family " node type(s)")
                      {:unknown (vec extra) :family family})))
    (when (and (not partial?) (seq missing))
      (throw (ex-info (str "defvisitor " name ": no method for node type(s) "
                           (pr-str (vec missing))
                           ". Handle them, or tag the visitor ^:partial.")
                      {:missing (vec missing)})))
    `(def ~name
       ~@(when doc [doc])
       ~(into {} (for [[node-type argv & fn-body] clauses]
                   [node-type `(fn ~argv ~@fn-body)])))))
