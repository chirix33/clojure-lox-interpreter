;;;; =============================================================================
;;;; Chapter 8 - Statements and State  (section 8.3, "Environments")
;;;;
;;;; "The bindings that associate variables to values need to be stored
;;;;  somewhere. Ever since the Lisp folks invented parentheses, this data
;;;;  structure has been called an environment."
;;;;
;;;; An environment is a map from variable *name* to value, plus a reference to
;;;; the environment of the immediately enclosing scope (section 8.5.1). Look-up
;;;; walks that chain from the innermost scope outwards, which is what makes a
;;;; local declaration shadow an outer one of the same name.
;;;;
;;;;   book                                here
;;;;   ---------------------------------   ------------------------------------
;;;;   class Environment                   a map {:values <atom> :enclosing _}
;;;;   new Environment()                   (make-environment)
;;;;   new Environment(enclosing)          (make-environment enclosing)
;;;;   final Environment enclosing         :enclosing
;;;;   Map<String,Object> values           :values, an atom around a Clojure map
;;;;   define(name, value)                 (define! env name value)
;;;;   get(Token name)                     (get-var env name-token)
;;;;   assign(Token name, Object value)    (assign! env name-token value)
;;;;
;;;; --------------------------------------------------------------------------
;;;; Why an atom, in a language that prefers values
;;;; --------------------------------------------------------------------------
;;;;
;;;; Everything in this interpreter so far has been a pure function of
;;;; immutable data: the scanner threads `start`/`current` through a state map,
;;;; the parser threads a cursor, and chapter 7's `evaluate` is a function from
;;;; a tree to a value. An environment could be immutable too: `define!` would
;;;; return a *new* environment, and every evaluation step would have to thread
;;;; it the way the parser threads its cursor.
;;;;
;;;; That is deliberately not what happens here, for three reasons.
;;;;
;;;; 1. THE VISITOR SIGNATURE. `lox.ast/accept` calls a visit function with one
;;;;    argument - the node. A threaded environment would mean every visit
;;;;    function returns `[env value]`, which is a different and much noisier
;;;;    interpreter than the book's, for no gain in this chapter.
;;;;
;;;; 2. ALIASING IS THE POINT, FROM CHAPTER 10. A Lox closure captures the
;;;;    environment it was *defined* in, and an assignment made later through
;;;;    any other reference to that scope has to be visible through the
;;;;    closure. That is shared mutable identity, and it is a language-level
;;;;    requirement, not an implementation shortcut: a counter closure
;;;;
;;;;        fun makeCounter() { var i = 0; fun count() { i = i + 1; ... } ... }
;;;;
;;;;    is only observably correct if `i` has one identity that both the
;;;;    enclosing scope and the closure see. Threading values would force an
;;;;    explicit store-plus-indirection to simulate exactly this.
;;;;
;;;; 3. THE REPL. Globals must outlive one line of input, which means the
;;;;    global environment is a thing with an identity that persists across
;;;;    calls. The book makes the same observation about why `Lox.interpreter`
;;;;    is a static field.
;;;;
;;;; So: Clojure's answer to "mutable state that several references share" is a
;;;; reference type around an immutable value, and that is what `:values` is.
;;;; The *chain* is still immutable - `:enclosing` is a plain reference, and
;;;; the book marks it `final` for the same reason - and `binding-map` can hand
;;;; out a consistent snapshot of any scope, which the unit tests use.
;;;;
;;;; --------------------------------------------------------------------------
;;;; Semantic choices the chapter makes explicit
;;;; --------------------------------------------------------------------------
;;;;
;;;; * Keys are bare strings, not tokens (section 8.3): "all identifier tokens
;;;;   with the same name should refer to the same variable", and two tokens
;;;;   with the same lexeme differ in their line numbers, so they would be two
;;;;   different map keys.
;;;;
;;;; * `define!` does not check for an existing binding, so `var a` twice is
;;;;   legal and redefines. The book keeps it that way so scripts and the REPL
;;;;   obey one rule, and cites Scheme.
;;;;
;;;; * Reading an *undefined* variable is a **runtime** error, not a static
;;;;   one, because "using a variable isn't the same as referring to it" - a
;;;;   static rule would make mutually recursive functions impossible to write
;;;;   (chapter 10).
;;;;
;;;; * `assign!` may not create a binding: "assignment is not allowed to create
;;;;   a new variable", so an unknown name is the same runtime error. Lox has
;;;;   no implicit declaration; the chapter's design note explains why.
;;;;
;;;; Challenge 8.2 (make reading an uninitialized variable an error) is
;;;; implemented here, behind `*error-on-uninitialized?*`, and is off by
;;;; default. See docs/chapter08-challenges.md.
;;;; =============================================================================
(ns lox.environment
  (:require [lox.errors :as err]))

;;; ---------------------------------------------------------------------------
;;; Challenge 8.2 - uninitialized variables
;;; ---------------------------------------------------------------------------

(def ^:dynamic *error-on-uninitialized?*
  "Challenge 8.2. When true, `var a;` leaves `a` *declared but uninitialized*,
  and reading it before an assignment is a runtime error instead of yielding
  nil. Off by default: section 8.3.1 settles on \"Lox sets a variable to nil if
  it isn't explicitly initialized\", and that is the language the rest of the
  book - and clox - implements."
  false)

(def uninitialized
  "The sentinel stored for a declared-but-uninitialized variable under
  challenge 8.2.

  It must be a value no Lox program can produce, or a program could forge
  \"uninitialized\" and the error would fire on a legitimate read. A namespaced
  keyword is exactly that: `lox.interpreter/lox-value?` rejects it, so it can
  never arrive here from an evaluated expression."
  ::uninitialized)

(defn uninitialized?
  "True if `v` is the challenge 8.2 sentinel rather than a Lox value."
  [v]
  (identical? uninitialized v))

(defn initial-value
  "The value a `var` with no initializer binds to.

  nil (Lox's `nil`) normally; the sentinel under challenge 8.2."
  []
  (if *error-on-uninitialized?* uninitialized nil))

;;; ---------------------------------------------------------------------------
;;; Construction - the two constructors of section 8.5.1
;;; ---------------------------------------------------------------------------

(defn make-environment
  "Book: `new Environment()` and `new Environment(enclosing)`.

  With no argument, the global scope, which ends the chain. With one, a fresh
  local scope nested inside it."
  ([] (make-environment nil))
  ([enclosing]
   {:values (atom {}) :enclosing enclosing}))

(defn environment?
  "True if `x` looks like an environment."
  [x]
  (and (map? x)
       (instance? clojure.lang.Atom (:values x))
       (contains? x :enclosing)))

(defn enclosing
  "The environment of the immediately enclosing scope, or nil for the global
  one. Book: the `enclosing` field."
  [env]
  (:enclosing env))

(defn binding-map
  "A snapshot of just *this* scope's bindings, as an immutable map.

  Not in the book, where `values` is private. Handing out a value rather than
  the atom keeps callers (including the tests) from mutating a scope behind
  `define!`'s back."
  [env]
  @(:values env))

(defn depth
  "How many scopes enclose `env`; 0 for the global environment.

  Not in the book. The tests use it to assert that blocks really do create and
  then discard a scope, rather than inferring it from shadowing behaviour."
  [env]
  (loop [e env n 0]
    (if-let [outer (enclosing e)] (recur outer (inc n)) n)))

(defn globals
  "The outermost environment in `env`'s chain."
  [env]
  (if-let [outer (enclosing env)] (recur outer) env))

;;; ---------------------------------------------------------------------------
;;; define - section 8.3
;;; ---------------------------------------------------------------------------

(defn define!
  "Book: `Environment.define`. Bind `name` (a bare string) to `value` in *this*
  scope, whether or not it is already bound here.

  \"A variable statement doesn't just define a new variable, it can also be
   used to redefine an existing variable.\" Note that a redefinition always
  lands in the innermost scope: `define!` never walks the chain, so an inner
  `var a` shadows rather than overwrites an outer `a`."
  [env name value]
  (swap! (:values env) assoc name value)
  nil)

(defn defined-locally?
  "True if `name` is bound in *this* scope, ignoring enclosing ones."
  [env name]
  (contains? (binding-map env) name))

(defn resolve-environment
  "The innermost environment in `env`'s chain that binds `name`, or nil.

  Not in the book, which inlines this walk into `get` and `assign` as two
  almost identical recursions. Naming it once means look-up and assignment
  cannot disagree about *which* binding they found - and chapter 11's resolver
  is going to replace exactly this walk, so it is useful to have it in one
  place."
  [env name]
  (when env
    (if (defined-locally? env name)
      env
      (recur (enclosing env) name))))

;;; ---------------------------------------------------------------------------
;;; get - section 8.3, extended to walk the chain in 8.5.1
;;; ---------------------------------------------------------------------------

(defn get-var
  "Book: `Environment.get`. Read the variable named by the token `name-token`.

  Walks outwards from this scope, so the innermost binding wins - which *is*
  shadowing. An unknown name is a runtime error carrying the token, so the
  report can name the line.

  Under challenge 8.2 a binding may hold the `uninitialized` sentinel, and
  reading it is its own error with its own message: the variable does exist,
  so \"Undefined variable\" would be a lie."
  [env name-token]
  (let [name (:lexeme name-token)]
    (if-let [found (resolve-environment env name)]
      (let [value (get (binding-map found) name)]
        (if (uninitialized? value)
          (err/throw-runtime-error!
           name-token (str "Variable '" name "' is not initialized."))
          value))
      (err/throw-runtime-error!
       name-token (str "Undefined variable '" name "'.")))))

;;; ---------------------------------------------------------------------------
;;; assign - section 8.4.2, extended to walk the chain in 8.5.1
;;; ---------------------------------------------------------------------------

(defn assign!
  "Book: `Environment.assign`. Store `value` in the *existing* variable named
  by `name-token`.

  \"The key difference between assignment and definition is that assignment is
   not allowed to create a new variable\", so an unknown name is a runtime
  error rather than an implicit declaration. The binding written to is the one
  `get-var` would have read, found by the same walk, which is what makes

      var a = 1; { a = 2; } print a;   // 2

  assign to the outer `a` instead of quietly creating a local one."
  [env name-token value]
  (let [name (:lexeme name-token)]
    (if-let [found (resolve-environment env name)]
      (do (swap! (:values found) assoc name value) value)
      (err/throw-runtime-error!
       name-token (str "Undefined variable '" name "'.")))))

;;; ---------------------------------------------------------------------------
;;; Test and REPL support
;;; ---------------------------------------------------------------------------

(defn clear!
  "Drop every binding in *this* scope, leaving the chain intact.

  Not in the book. The interpreter's global environment has process lifetime -
  that is the whole point of the book making `Lox.interpreter` static - so the
  unit tests need a way to start from an empty one without having to rebind
  the var that holds it."
  [env]
  (reset! (:values env) {})
  nil)
