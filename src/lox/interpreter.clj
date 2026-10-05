;;;; =============================================================================
;;;; Chapter 7 - Evaluating Expressions
;;;;
;;;; "In this chapter, our interpreter will take breath, open its eyes, and
;;;;  execute some code."
;;;;
;;;; Chapters 4-6 built a pipeline that ends in a syntax tree: source text ->
;;;; tokens -> tree. The tree was inert; chapter 6's `run` could only *print*
;;;; it. This chapter adds the last stage, and it is the simplest of the three
;;;; strategies the book lists (compile to machine code, lower to bytecode, or
;;;; walk the tree): we walk the tree and compute a value at each node.
;;;;
;;;; The chapter answers two questions.
;;;;
;;;; --------------------------------------------------------------------------
;;;; 1. What kinds of values do we produce?  (section 7.1)
;;;; --------------------------------------------------------------------------
;;;;
;;;; Lox is dynamically typed, so a Lox value must be able to hold a number, a
;;;; string, a boolean or nil, and must be able to say at runtime which it is.
;;;; The book reaches for `java.lang.Object` plus the boxed primitives:
;;;;
;;;;   Lox type        book (Java)        here (Clojure)
;;;;   -------------   ----------------   -----------------------------------
;;;;   any Lox value   Object             any object (no wrapper type at all)
;;;;   nil             null               nil
;;;;   Boolean         Boolean            java.lang.Boolean (true / false)
;;;;   number          Double             java.lang.Double
;;;;   string          String             java.lang.String
;;;;
;;;; This is the one place where Clojure buys us *nothing* over Java, and that
;;;; is the point: both languages run on the JVM, so "a Lox value" is the same
;;;; set of JVM objects in both. The scanner already produces `Double` for
;;;; number literals (`Double/parseDouble` in `lox.scanner/scan-number`), so
;;;; literals need no conversion here at all.
;;;;
;;;; Note that Lox numbers are Doubles *only*. A Clojure `1` is a `Long` and is
;;;; therefore not a Lox number - `lox-number?` tests `(instance? Double x)`,
;;;; not Clojure's `number?`, exactly as the book tests `instanceof Double`.
;;;; Tests that build literal nodes by hand must write `1.0`, not `1`.
;;;;
;;;; --------------------------------------------------------------------------
;;;; 2. How do we organize the evaluation code?  (section 7.2)
;;;; --------------------------------------------------------------------------
;;;;
;;;; The book reuses the Visitor pattern from chapter 5: `class Interpreter
;;;; implements Expr.Visitor<Object>`. Chapter 5 of this project already
;;;; reduced that interface to "a map from node type to a function", so the
;;;; interpreter is `(ast/defvisitor interpreter ...)` - the same shape as
;;;; `lox.ast-printer/printer`, with values in place of strings. The book notes
;;;; the AST printer "is almost exactly what a real interpreter does, except
;;;; instead of concatenating strings, it computes values"; here the two really
;;;; are the same code with a different map.
;;;;
;;;;   book                                  here
;;;;   -----------------------------------   ---------------------------------
;;;;   class Interpreter                     the `interpreter` visitor map
;;;;   implements Expr.Visitor<Object>       (checked by `defvisitor`)
;;;;   evaluate(expr)                        (evaluate expr)
;;;;   isTruthy(obj)                         (truthy? v)
;;;;   isEqual(a, b)                         (lox-equal? a b)
;;;;   stringify(obj)                        (stringify v)
;;;;   checkNumberOperand(op, r)             (check-number-operand! op r)
;;;;   checkNumberOperands(op, l, r)         (check-number-operands! op l r)
;;;;   class RuntimeError                    `lox.errors/runtime-error-ex`
;;;;   interpret(expr)                       (interpret expr)
;;;;
;;;; There is no interpreter *object* yet because there is nothing to put in
;;;; it: chapter 7's evaluation is a pure function of the tree. The book still
;;;; makes `Lox.interpreter` a static field, and says why - "it will later when
;;;; the interpreter stores global variables. Those variables should persist
;;;; throughout the REPL session." Chapter 8 introduces that `Environment`,
;;;; and `evaluate` will take it as an argument then.
;;;;
;;;; --------------------------------------------------------------------------
;;;; 3. Runtime errors  (section 7.3)
;;;; --------------------------------------------------------------------------
;;;;
;;;; The book's casts can fail, and a raw `ClassCastException` would both leak
;;;; the implementation language and kill the REPL. So it checks types *before*
;;;; casting and throws its own `RuntimeError`, carrying the operator token so
;;;; the report can name a line. Two semantic details the book pins down here,
;;;; both of which the tests assert:
;;;;
;;;;   * operands are evaluated left to right, and
;;;;   * *both* operands are evaluated before either is type-checked,
;;;;
;;;; so `say("left") - say("right")` prints both before failing. Neither is an
;;;; implementation detail once a program can observe side effects.
;;;;
;;;; A runtime error has to unwind an arbitrarily deep recursion - `2 * (3 /
;;;; -"muffin")` must escape the `-`, the `/` and the `*` - so it is thrown,
;;;; not returned. `lox.errors/throw-runtime-error!` throws an `ex-info`
;;;; tagged `:lox/runtime-error`, and `interpret` is the single catch point.
;;;; A runtime error stops the *expression*, not the interpreter.
;;;;
;;;; --------------------------------------------------------------------------
;;;; Challenges (all three; see docs/chapter07-challenges.md)
;;;; --------------------------------------------------------------------------
;;;;
;;;;   7.1  ordering comparisons on strings     *compare-strings?*          off
;;;;   7.2  "scone" + 4  ==>  "scone4"          *string-coercing-plus?*     off
;;;;   7.3  division by zero is a runtime error *error-on-division-by-zero?* off
;;;;
;;;; Each is off by default for the reason the book keeps stressing in this
;;;; chapter - "to ensure that jlox and clox work the same" - since all three
;;;; change the meaning of programs that are already *valid*. Flipping a switch
;;;; turns one on, and the test suite exercises every one in both positions.
;;;; This is the same convention chapter 6 used for its grammar challenges.
;;;; =============================================================================
(ns lox.interpreter
  (:require [lox.ast :as ast]
            [lox.errors :as err]))

;;; ---------------------------------------------------------------------------
;;; Challenge switches - see the note above and docs/chapter07-challenges.md
;;; ---------------------------------------------------------------------------

(def ^:dynamic *compare-strings?*
  "Challenge 7.1. When true, `<` `<=` `>` `>=` also accept two strings and
  order them lexicographically. Mixed types remain a runtime error."
  false)

(def ^:dynamic *string-coercing-plus?*
  "Challenge 7.2. When true, `+` with *either* operand a string converts the
  other to a string and concatenates, so \"scone\" + 4 is \"scone4\"."
  false)

(def ^:dynamic *error-on-division-by-zero?*
  "Challenge 7.3. When true, `/` by zero is a runtime error instead of
  producing the IEEE 754 infinity or NaN."
  false)

;;; ---------------------------------------------------------------------------
;;; 7.1 Representing values
;;;
;;; The book uses `instanceof` against the boxed primitives. These predicates
;;; are those checks, named. `lox-number?` is deliberately *not* Clojure's
;;; `number?`: Lox has exactly one numeric type, the double, and a stray Long
;;; or BigDecimal reaching the evaluator is a bug in whoever built the node,
;;; not a Lox value to be computed with.
;;; ---------------------------------------------------------------------------

(defn lox-number?  "Book: `x instanceof Double`."  [v] (instance? Double v))
(defn lox-string?  "Book: `x instanceof String`."  [v] (instance? String v))
(defn lox-boolean? "Book: `x instanceof Boolean`." [v] (instance? Boolean v))
(defn lox-nil?     "Book: `x == null`."            [v] (nil? v))

(defn lox-value?
  "True if `v` is a representable Lox value. Chapter 7 has four types; later
  chapters add functions (10), classes and instances (12)."
  [v]
  (or (lox-nil? v) (lox-boolean? v) (lox-number? v) (lox-string? v)))

(defn type-name
  "The Lox-facing name of `v`'s type: \"nil\", \"Boolean\", \"number\" or
  \"string\". Not in the book - used by the tests and the docs to talk about
  the table in section 7.1 without naming Java classes."
  [v]
  (cond
    (lox-nil? v)     "nil"
    (lox-boolean? v) "Boolean"
    (lox-number? v)  "number"
    (lox-string? v)  "string"
    :else            (str "not a Lox value (" (some-> v class .getName) ")")))

;;; ---------------------------------------------------------------------------
;;; 7.2.4 Truthiness and falsiness
;;; ---------------------------------------------------------------------------

(defn truthy?
  "Book: `isTruthy`. Lox follows Ruby: `false` and `nil` are falsey, and
  *everything* else is truthy - including 0 and the empty string, which are
  falsey in C, Python, JavaScript and PHP respectively.

  Clojure's own truthiness happens to be the identical rule, so `(if v ...)`
  would work. We spell the rule out anyway: that the host language agrees
  today is a coincidence, not a decision of Lox's, and the book's point is
  that this partition is a language's own arbitrary choice."
  [v]
  (cond
    (lox-nil? v)     false
    (lox-boolean? v) v
    :else            true))

(defn falsey?
  "The complement of `truthy?`. Not in the book; it reads better in `!`."
  [v]
  (not (truthy? v)))

;;; ---------------------------------------------------------------------------
;;; 7.2.5 Equality
;;; ---------------------------------------------------------------------------

(defn lox-equal?
  "Book: `isEqual`. Two nils are equal; nil equals nothing else; otherwise
  defer to `Object.equals`.

  `.equals` rather than Clojure's `=` is a deliberate, tested choice, and it is
  the subtle corner the book calls out in its NaN aside. Clojure's `=` compares
  numbers by *numeric value* (`Numbers.equiv`, i.e. Java `==`), so it would
  report NaN == NaN as false and 0.0 == -0.0 as true. `Double.equals` compares
  bit patterns, so it says the opposite of both. The book uses `equals()`, and
  notes that this means Lox does not follow IEEE here; we match the book,
  because two implementations of one language disagreeing about whether
  `(0/0) == (0/0)` is exactly the sort of thing it warns about.

  Note also that Lox does no implicit conversion in `==`: `1 == \"1\"` is
  false, and so is `true == 1`, since Boolean and Double never `.equals` each
  other. Unlike the comparison operators, `==` accepts any pair of types and
  so can never raise a runtime error."
  [a b]
  (cond
    (and (lox-nil? a) (lox-nil? b)) true
    (lox-nil? a)                    false
    :else                           (.equals ^Object a b)))

;;; ---------------------------------------------------------------------------
;;; 7.4 stringify - values as the *user* sees them
;;; ---------------------------------------------------------------------------

(defn stringify
  "Book: `stringify`. Convert a Lox value to its display string.

  Two edge cases, both from section 7.4:

    * nil prints as \"nil\", not as Java's \"null\" or Clojure's empty string.
    * Lox has a single number type, the double, so integer-valued numbers must
      print without the `.0` that `Double.toString` insists on: 1.0 -> \"1\".

  The `.0` trimming is a string hack in the book and a string hack here, and
  it is load-bearing for cross-implementation consistency: the book keeps it
  specifically so that jlox and clox both print `1` for `1`.

  Note that this is *not* `lox.ast-printer/literal->string`, which deliberately
  keeps the `.0` because its job is to show what the tree actually holds."
  [v]
  (cond
    (lox-nil? v) "nil"
    (lox-number? v)
    (let [text (str v)]
      (if (.endsWith ^String text ".0")
        (subs text 0 (- (count text) 2))
        text))
    :else (str v)))

;;; ---------------------------------------------------------------------------
;;; 7.3.1 Detecting runtime errors
;;; ---------------------------------------------------------------------------

(defn check-number-operand!
  "Book: `checkNumberOperand`. Guard for unary `-`."
  [operator operand]
  (when-not (lox-number? operand)
    (err/throw-runtime-error! operator "Operand must be a number."))
  operand)

(defn check-number-operands!
  "Book: `checkNumberOperands`. Guard for the arithmetic and comparison
  binaries."
  [operator left right]
  (when-not (and (lox-number? left) (lox-number? right))
    (err/throw-runtime-error! operator "Operands must be numbers."))
  nil)

(defn check-comparable-operands!
  "`checkNumberOperands`, widened by challenge 7.1.

  With `*compare-strings?*` off this is exactly the book's check, message
  included. With it on, two strings are also acceptable, and the message says
  so - a wrong message is its own kind of bug, so it tracks the rule."
  [operator left right]
  (let [ok? (or (and (lox-number? left) (lox-number? right))
                (and *compare-strings?* (lox-string? left) (lox-string? right)))]
    (when-not ok?
      (err/throw-runtime-error!
       operator
       (if *compare-strings?*
         "Operands must be two numbers or two strings."
         "Operands must be numbers.")))
    nil))

;;; ---------------------------------------------------------------------------
;;; The operators
;;; ---------------------------------------------------------------------------

(defn- eval-plus
  "`+`, the odd one out: overloaded for numeric addition and string
  concatenation, so it checks types instead of assuming them (section 7.2.5).

  Challenge 7.2 adds the third case, where one operand is a string and the
  other is coerced. It is checked *after* the two book cases so that
  `\"a\" + \"b\"` and `1 + 2` behave identically whether the switch is on or
  off; only the pairs that were previously errors can change meaning."
  [operator left right]
  (cond
    (and (lox-number? left) (lox-number? right)) (+ (double left) (double right))
    (and (lox-string? left) (lox-string? right)) (str left right)

    (and *string-coercing-plus?*
         (or (lox-string? left) (lox-string? right)))
    ;; `stringify`, not `str`, so the coerced side reads the way the user's
    ;; own `print` would: "scone" + 4 is "scone4", never "scone4.0".
    (str (stringify left) (stringify right))

    :else
    (err/throw-runtime-error!
     operator "Operands must be two numbers or two strings.")))

(defn- eval-divide
  "`/`. Challenge 7.3 optionally rejects a zero divisor.

  Off (the book, and the default), `1/0` is IEEE 754 Infinity and `0/0` is
  NaN, because Lox numbers are doubles and that is what doubles do. On, a zero
  divisor raises a runtime error. `(zero? right)` catches -0.0 too, which
  divides to -Infinity and is just as surely a division by zero.

  The `(double ...)` casts are not decoration - see the note on `eval-binary`.
  Clojure's `/` on two *boxed* Doubles throws `ArithmeticException: Divide by
  zero`; on two *primitive* doubles it divides and yields Infinity, which is
  what the book's `(double)left / (double)right` does."
  [operator left right]
  (if (and *error-on-division-by-zero?* (zero? right))
    (err/throw-runtime-error! operator "Division by zero.")
    (/ (double left) (double right))))

(defn- eval-comparison
  "The ordering operators `<` `<=` `>` `>=`, for the already-validated pair
  `left`/`right`. `op` is the operator's token type.

  Two separate orderings, deliberately:

    * Numbers use *primitive* double comparison, as the book's
      `(double)left > (double)right` does. This is IEEE 754 ordering, in which
      every comparison involving NaN is false (including `NaN <= NaN`) and
      `-0.0` and `0.0` compare equal. Clojure's `compare` would instead use
      `Double.compareTo`, which totally orders NaN above everything and `-0.0`
      below `0.0` - so `NaN > 1` would come out *true*. That is the same
      `equals`-versus-`==` split the book flags for `isEqual`, and here the
      book takes the other side of it, so we do too.

    * Strings (challenge 7.1 only) use `String.compareTo`, i.e. UTF-16
      code-unit order - the ordering Java uses and the one C's `strcmp`
      approximates for ASCII. See the challenge doc for why *mixed* types are
      still refused."
  [op left right]
  (if (lox-string? left)
    (let [c (.compareTo ^String left ^String right)]
      (case op
        :greater       (pos? c)
        :greater-equal (not (neg? c))
        :less          (neg? c)
        :less-equal    (not (pos? c))))
    (let [l (double left)
          r (double right)]
      (case op
        :greater       (> l r)
        :greater-equal (>= l r)
        :less          (< l r)
        :less-equal    (<= l r)))))

(defn- eval-binary
  "Dispatch a binary operator on its token type. Book: the `switch` in
  `visitBinaryExpr`.

  The book's `switch` falls out of the bottom and returns null for any
  operator it does not know, with a `// Unreachable.` comment. Unreachable
  code that silently produces a value is a trap, so the default branch throws
  instead: if the parser ever hands the interpreter an operator it has no rule
  for, we want to hear about it at that moment.

  Every arithmetic operand goes through `(double ...)`, mirroring the book's
  `(double)left` casts. For `/` the cast is *load-bearing*, not cosmetic:
  Clojure's `/` on two boxed `Double` objects goes through
  `clojure.lang.Numbers`, which rejects a zero divisor with an
  `ArithmeticException` instead of producing IEEE 754 Infinity. For `+`, `-`
  and `*` the boxed and primitive paths agree, and the cast is there so that
  all four operators read the same way and none of them has to be re-derived
  when someone next edits this `case`."
  [operator left right]
  (case (:type operator)
    ;; Equality - any types, never an error (section 7.2.5).
    :bang-equal    (not (lox-equal? left right))
    :equal-equal   (lox-equal? left right)

    ;; Comparison - numbers (plus strings under challenge 7.1). Always yields
    ;; a Boolean, unlike arithmetic, whose result type follows its operands.
    (:greater :greater-equal :less :less-equal)
    (do (check-comparable-operands! operator left right)
        (eval-comparison (:type operator) left right))

    ;; Arithmetic.
    :minus         (do (check-number-operands! operator left right)
                       (- (double left) (double right)))
    :star          (do (check-number-operands! operator left right)
                       (* (double left) (double right)))
    :slash         (do (check-number-operands! operator left right)
                       (eval-divide operator left right))
    :plus          (eval-plus operator left right)

    ;; Chapter 6, challenge 6.1 - the C comma operator, which the parser
    ;; builds as a :binary node with a `,` operator. Both operands have
    ;; already been evaluated by the caller, left to right; the operator's
    ;; whole job is to discard the left value and yield the right.
    :comma         right

    (err/throw-runtime-error!
     operator (str "Unsupported binary operator '" (:lexeme operator) "'."))))

(defn- eval-unary
  "Dispatch a unary operator. Book: the `switch` in `visitUnaryExpr`."
  [operator right]
  (case (:type operator)
    :bang  (not (truthy? right))
    :minus (do (check-number-operand! operator right)
               (- (double right)))
    (err/throw-runtime-error!
     operator (str "Unsupported unary operator '" (:lexeme operator) "'."))))

;;; ---------------------------------------------------------------------------
;;; 7.2 The visitor
;;; ---------------------------------------------------------------------------

(declare evaluate)

(ast/defvisitor interpreter
  "Book: `class Interpreter implements Expr.Visitor<Object>`.

  Evaluation is a *post-order* traversal: every node evaluates its children
  before doing its own work. That is visible in each clause below - the
  recursive `evaluate` calls come first."
  ;; 7.2.1. "A literal is a bit of syntax that produces a value." The scanner
  ;; produced the value and the parser stored it, so there is nothing to do
  ;; but hand it back.
  (:literal  [{:keys [value]}]
             value)

  ;; 7.2.2. Lox keeps a node for parentheses even though the inner expression
  ;; would do - chapter 8 needs it to tell `a = 1` from `(a) = 1`.
  (:grouping [{:keys [expression]}]
             (evaluate expression))

  ;; 7.2.3.
  (:unary    [{:keys [operator right]}]
             (eval-unary operator (evaluate right)))

  ;; 7.2.5. The two `evaluate` calls fix Lox's evaluation order as left to
  ;; right, and both run before `eval-binary` type-checks anything.
  (:binary   [{:keys [left operator right]}]
             (let [l (evaluate left)
                   r (evaluate right)]
               (eval-binary operator l r)))

  ;; Chapter 6, challenge 6.2 - the ternary `c ? t : e`.
  ;;
  ;; This is the node that justifies itself here rather than in the parser.
  ;; Unlike every clause above, it does *not* evaluate all its children: the
  ;; condition decides which branch runs, and the other branch must never be
  ;; evaluated, or a guard like `x == nil ? "safe" : x / 0` would defeat
  ;; itself. A nested pair of :binary nodes could not express that without the
  ;; evaluator special-casing the `?` and `:` tokens.
  (:conditional [{:keys [condition then-branch else-branch]}]
                (if (truthy? (evaluate condition))
                  (evaluate then-branch)
                  (evaluate else-branch))))

(defn evaluate
  "Book: `Interpreter.evaluate`. Evaluate one expression node to a Lox value.

  Runtime errors propagate as thrown exceptions; `interpret` catches them."
  [expr]
  (ast/accept expr interpreter))

;;; ---------------------------------------------------------------------------
;;; 7.4 Hooking up the interpreter
;;; ---------------------------------------------------------------------------

(defn interpret
  "Book: `Interpreter.interpret`. Evaluate `expr`, print the result, and report
  a runtime error rather than letting it escape.

  Returns the value on success and nil on a runtime error. The book's method
  is `void`; returning the value costs nothing and lets a test assert on the
  result without parsing stdout. `lox.errors/had-runtime-error` is still the
  flag a caller should branch on, since nil is also a perfectly good Lox value.

  This is the *only* place a runtime error is caught, which is what section
  7.3 is driving at: the error unwinds the entire evaluation of the expression
  and stops there, leaving the interpreter (and a REPL session) alive."
  [expr]
  (try
    (let [value (evaluate expr)]
      (println (stringify value))
      value)
    (catch clojure.lang.ExceptionInfo e
      (if (err/runtime-error? e)
        (do (err/report-runtime-error e) nil)
        (throw e)))))

(defn try-evaluate
  "Evaluate `expr`, reporting a runtime error instead of throwing, but *not*
  printing the result.

  Not in the book, which has no caller that wants one without the other. The
  tests do: asserting on a returned value beats capturing stdout, and from
  chapter 8 the printing moves into the `print` statement anyway."
  [expr]
  (try
    (evaluate expr)
    (catch clojure.lang.ExceptionInfo e
      (if (err/runtime-error? e)
        (do (err/report-runtime-error e) nil)
        (throw e)))))
