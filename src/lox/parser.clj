;;;; =============================================================================
;;;; Chapter 6 - Parsing Expressions
;;;;
;;;; Chapter 4 produced a flat token stream; chapter 5 defined the tree shapes.
;;;; This chapter writes the thing that turns the first into the second: a
;;;; **recursive descent parser**.
;;;;
;;;; --------------------------------------------------------------------------
;;;; The grammar (section 6.1)
;;;; --------------------------------------------------------------------------
;;;;
;;;; Chapter 5's grammar was ambiguous: `binary -> expression operator
;;;; expression` lets `6 / 3 - 1` nest either way. The fix is to *stratify* the
;;;; grammar - one rule per precedence level, each matching its own level or
;;;; anything tighter-binding:
;;;;
;;;;   expression -> equality ;
;;;;   equality   -> comparison ( ( "!=" | "==" ) comparison )* ;
;;;;   comparison -> term ( ( ">" | ">=" | "<" | "<=" ) term )* ;
;;;;   term       -> factor ( ( "-" | "+" ) factor )* ;
;;;;   factor     -> unary ( ( "/" | "*" ) unary )* ;
;;;;   unary      -> ( "!" | "-" ) unary | primary ;
;;;;   primary    -> NUMBER | STRING | "true" | "false" | "nil"
;;;;              |  "(" expression ")" ;
;;;;
;;;; Note the shape of the binary rules. The book first writes the natural
;;;; `factor -> factor ( "/" | "*" ) unary`, then rejects it: that production is
;;;; *left-recursive*, and a recursive descent parser implementing it would call
;;;; itself before consuming a token and blow the stack. Rewriting it as a flat
;;;; `unary ( ( "/" | "*" ) unary )*` matches the same language, and the loop it
;;;; implies is what builds the left-associative tree.
;;;;
;;;; --------------------------------------------------------------------------
;;;; Translating the book's design to Clojure
;;;; --------------------------------------------------------------------------
;;;;
;;;; 1. THE PARSER IS A VALUE, NOT A MUTABLE OBJECT.
;;;;    The book's `Parser` holds `List<Token> tokens` and a mutable cursor
;;;;    `int current`, and every helper advances it by side effect. Here the
;;;;    parser is a state map
;;;;
;;;;      {:tokens [...] :current 0}
;;;;
;;;;    and every rule is a pure function from that state to a pair
;;;;
;;;;      [state' expr]
;;;;
;;;;    This is the same trick chapter 4's scanner used for `start`/`current`,
;;;;    so the two phases read alike. `parse-*` threading a state through is
;;;;    wordier than `current++` at each call site, but it buys two things that
;;;;    matter for testing: any rule can be invoked on any token stream in
;;;;    isolation, and a failed parse cannot leave a shared object half-advanced.
;;;;
;;;; 2. BINARY LEVELS ARE ONE HIGHER-ORDER FUNCTION, NOT FOUR COPIES.
;;;;    `equality`, `comparison`, `term` and `factor` are, in the book,
;;;;    four textually identical methods differing only in which token types
;;;;    they match and which method they call for operands. The book's own
;;;;    margin note says as much: "If you wanted to do some clever Java 8, you
;;;;    could create a helper method for parsing a left-associative series of
;;;;    binary operators given a list of token types, and an operand method
;;;;    handle to simplify this redundant code." `parse-left-assoc-binary`
;;;;    below is exactly that helper. Each level is then one line, and the
;;;;    precedence table is visible as *data* in `binary-levels`.
;;;;
;;;; 3. PANIC MODE STILL USES AN EXCEPTION.
;;;;    Section 6.3.3 explains why: in recursive descent the parser's state is
;;;;    the call stack, so unwinding to a synchronization point means unwinding
;;;;    the stack, and an exception is how you do that. That reasoning applies
;;;;    verbatim to a stack of Clojure function calls, so `ParseError` becomes
;;;;    an `ex-info` tagged `::parse-error`, thrown by `error!` and caught in
;;;;    `parse`. The exception carries the state at the point of failure, so
;;;;    the catch site can synchronize and (from chapter 8) carry on.
;;;;
;;;;      book                              here
;;;;      ----------------------------      ----------------------------------
;;;;      new Parser(tokens)                (make-parser tokens)
;;;;      private int current               :current in the state map
;;;;      peek()                            peek-token
;;;;      previous()                        previous-token
;;;;      isAtEnd()                         at-end?
;;;;      check(type)                       check?
;;;;      advance()                         advance  (returns state only)
;;;;      match(types...)                   match    (returns state or nil)
;;;;      consume(type, msg)                consume
;;;;      error(token, msg)                 error!   (throws; book returns)
;;;;      synchronize()                     synchronize
;;;;      parse()                           parse
;;;;
;;;; --------------------------------------------------------------------------
;;;; Challenges
;;;; --------------------------------------------------------------------------
;;;;
;;;; All three end-of-chapter challenges are implemented here:
;;;;
;;;;   6.1  the C comma operator          `*allow-comma?*`       (default off)
;;;;   6.2  the ternary conditional `?:`  `*allow-conditional?*` (default off)
;;;;   6.3  error productions for a binary operator with no left operand
;;;;                                      `*binary-error-productions?*` (on)
;;;;
;;;; 6.1 and 6.2 are *off by default* deliberately. They are genuine grammar
;;;; extensions, not supersets that cost nothing: a comma expression at the
;;;; lowest precedence level would swallow the argument separators in a call
;;;; (chapter 10) and the separators in a multi-variable declaration
;;;; (chapter 8), so leaving it on would quietly break the language the rest of
;;;; the book builds. Keeping them behind dynamic vars means the default
;;;; grammar is exactly the book's, the challenge code is real and tested, and
;;;; a later chapter can never be surprised by it. See docs/chapter06-challenges.md.
;;;;
;;;; 6.3 is on by default: it only ever fires on input that is already a syntax
;;;; error, so it changes no valid program - it just produces a better message.
;;;; =============================================================================
(ns lox.parser
  (:require [lox.ast :as ast]
            [lox.errors :as err]
            [lox.scanner :as scanner]))

;;; ---------------------------------------------------------------------------
;;; Grammar switches - see the challenge notes above
;;; ---------------------------------------------------------------------------

(def ^:dynamic *allow-comma?*
  "Challenge 6.1. When true, `expression` admits the C comma operator at the
  lowest precedence level, left-associative."
  false)

(def ^:dynamic *allow-conditional?*
  "Challenge 6.2. When true, `expression` admits the C ternary `?:`, binding
  tighter than `,` and looser than `==`, and right-associative."
  false)

(def ^:dynamic *binary-error-productions?*
  "Challenge 6.3. When true, a binary operator appearing with no left-hand
  operand is reported as its own error and its right operand is parsed and
  discarded, instead of the generic \"Expect expression.\"."
  true)

;;; ---------------------------------------------------------------------------
;;; Parser state
;;;
;;; Book: the two fields of `class Parser`. `:tokens` is the vector produced by
;;; the scanner, always terminated by an :eof token - which is why `peek-token`
;;; never needs a bounds check and `at-end?` can be a simple type test.
;;; ---------------------------------------------------------------------------

(defn make-parser
  "Book: `new Parser(tokens)`."
  [tokens]
  {:tokens (vec tokens) :current 0})

(defn peek-token
  "Book: `peek()`. The next token, not yet consumed."
  [{:keys [tokens current]}]
  (nth tokens current))

(defn previous-token
  "Book: `previous()`. The most recently consumed token.

  Pairing this with `match` is what lets a rule say \"did I see an operator?\"
  and \"which one was it?\" as two separate, simple steps."
  [{:keys [tokens current]}]
  (nth tokens (dec current)))

(defn at-end?
  "Book: `isAtEnd()`. True once the cursor reaches the :eof token."
  [state]
  (= :eof (:type (peek-token state))))

(defn check?
  "Book: `check(type)`. Look at the next token without consuming it.

  Never true at :eof, so no rule can accidentally match the sentinel."
  [state type]
  (and (not (at-end? state)) (= type (:type (peek-token state)))))

(defn advance
  "Book: `advance()`, minus the return value.

  The Java method both moves the cursor *and* hands back the consumed token.
  A pure function cannot do both without returning a pair, and the caller
  almost always wants only one of the two - so `advance` returns the new state
  and `previous-token` retrieves the consumed token when it is wanted."
  [state]
  (if (at-end? state) state (update state :current inc)))

(defn match
  "Book: `match(types...)`.

  Returns the advanced state if the next token has any of `types`, or nil if
  it does not. nil-as-false lets a rule read `(if-let [state (match ...)] ...)`,
  which keeps \"did it match\" and \"the state after matching\" together -
  where the book has a boolean plus a hidden side effect."
  [state & types]
  (when (some #(check? state %) types)
    (advance state)))

;;; ---------------------------------------------------------------------------
;;; Errors and panic mode - section 6.3
;;; ---------------------------------------------------------------------------

(defn parse-error?
  "True if `e` is the parser's unwind signal. Book: `e instanceof ParseError`."
  [e]
  (boolean (and (instance? clojure.lang.ExceptionInfo e)
                (::parse-error (ex-data e)))))

(defn error!
  "Book: `Parser.error(token, message)` *and* the `throw` at its call sites.

  The book returns the `ParseError` rather than throwing it, so that each call
  site can choose whether to unwind - some errors (chapter 10's argument-count
  limit) should be reported without entering panic mode. We make that choice
  explicit instead: `error!` always throws, and a site that wants to report and
  continue calls `lox.errors/error` directly. Same flexibility, but you can see
  at the call site which one is happening.

  Reporting goes through `lox.errors/error`, whose token overload was added in
  this chapter (section 6.3.2) and points at the offending lexeme."
  [state token message]
  (err/error token message)
  (throw (ex-info message {::parse-error true
                           :token token
                           :message message
                           :state state})))

(def synchronization-keywords
  "Book: the `switch` in `synchronize()`. Token types that (almost) always
  begin a statement, and so make a good place to resume after an error."
  #{:class :fun :var :for :if :while :print :return})

(defn synchronize
  "Book: `synchronize()`. Discard tokens until we are plausibly at the start of
  the next statement.

  A semicolon just consumed means the previous statement is over; a statement
  keyword coming up means the next one is about to begin. Neither test is
  exact - a `for (;;)` clause separator will fool the first - but section 6.3.1
  makes the case that it does not need to be: the first error was reported
  precisely, and everything after it is best-effort suppression of cascades.

  Chapter 6 has no statements yet, so nothing calls this in anger until
  chapter 8. It is written now because the machinery belongs with the rest of
  the error recovery, and it is unit-tested directly."
  [state]
  (loop [state (advance state)]
    (cond
      (at-end? state)                                     state
      (= :semicolon (:type (previous-token state)))       state
      (contains? synchronization-keywords
                 (:type (peek-token state)))              state
      :else                                               (recur (advance state)))))

(defn consume
  "Book: `consume(type, message)`. Require a particular token, or fail.

  Returns the advanced state; the consumed token is available through
  `previous-token`, exactly as after `match`."
  [state type message]
  (if (check? state type)
    (advance state)
    (error! state (peek-token state) message)))

;;; ---------------------------------------------------------------------------
;;; The grammar rules
;;;
;;; Every rule below has the same signature:
;;;
;;;     state -> [state' expr]
;;;
;;; which is the pure-function reading of the book's `private Expr rule()`:
;;; the `Expr` is the second element, and the cursor movement that Java hid in
;;; `this.current` is the first.
;;; ---------------------------------------------------------------------------

(declare parse-expression)

(defn parse-primary
  "Book: `primary()`.

    primary -> NUMBER | STRING | \"true\" | \"false\" | \"nil\"
            |  \"(\" expression \")\" ;

  The literal *values* come from the token's `:literal` field for NUMBER and
  STRING (the scanner already converted the text to a Double or a String), and
  are written out directly for the three keyword literals. Note `nil` is the
  Lox nil here, and `(ast/literal nil)` is a perfectly good node - which is why
  chapter 5 gave the `value` field the permissive `:object` kind.

  Falling off the end means the next token cannot begin an expression at all,
  which is the chapter's second error site."
  [state]
  (cond
    (check? state :false) [(advance state) (ast/literal false)]
    (check? state :true)  [(advance state) (ast/literal true)]
    (check? state :nil)   [(advance state) (ast/literal nil)]

    (or (check? state :number) (check? state :string))
    (let [state (advance state)]
      [state (ast/literal (:literal (previous-token state)))])

    (check? state :left-paren)
    (let [state           (advance state)
          [state inner]   (parse-expression state)
          state           (consume state :right-paren "Expect ')' after expression.")]
      [state (ast/grouping inner)])

    :else
    (error! state (peek-token state) "Expect expression.")))

(defn parse-unary
  "Book: `unary()`.

    unary -> ( \"!\" | \"-\" ) unary | primary ;

  The recursive call - rather than a call to `primary` - is what makes `!!true`
  and `--1` parse. Unary operators are right-associative for free: the operand
  is parsed *before* the node is built, so the innermost operator ends up
  deepest in the tree."
  [state]
  (if-let [state' (match state :bang :minus)]
    (let [operator      (previous-token state')
          [state right] (parse-unary state')]
      [state (ast/unary operator right)])
    (parse-primary state)))

(defn parse-left-assoc-binary
  "Parse `operand ( <op> operand )*` into a left-leaning tree of :binary nodes.

  This is the book's four near-identical binary methods, factored into one -
  the helper its own margin note suggests. The loop is the `( ... )*` of the
  grammar rule; re-binding `expr` to the node just built on each turn is what
  makes the nesting left-associative:

      a == b == c   parses as   ((a == b) == c)

  `operand` is the function for the next-tighter precedence level, and
  `operators` is the set of token types at this level."
  [state operand operators]
  (loop [[state expr] (operand state)]
    (if-let [state' (apply match state operators)]
      (let [operator      (previous-token state')
            [state right] (operand state')]
        (recur [state (ast/binary expr operator right)]))
      [state expr])))

(def binary-levels
  "The binary half of the precedence table of section 6.1, lowest first.

  The book encodes this table in the *call graph* of its methods: `equality`
  happens to call `comparison`, which happens to call `term`. Here it is data,
  so the precedence order can be read off in one place - and asserted in a
  test, which is harder to do against a call graph."
  [{:rule :equality   :operators [:bang-equal :equal-equal]}
   {:rule :comparison :operators [:greater :greater-equal :less :less-equal]}
   {:rule :term       :operators [:minus :plus]}
   {:rule :factor     :operators [:slash :star]}])

(def ^:private binary-parser
  "Compose `binary-levels` into a single parse function for the lowest level.

  Built right-to-left: `factor`'s operand is `parse-unary`, `term`'s operand is
  `factor`, and so on up to `equality`, whose operand is `comparison`. That is
  precisely the chain of calls the book writes out by hand."
  (reduce (fn [operand {:keys [operators]}]
            (fn [state] (parse-left-assoc-binary state operand operators)))
          parse-unary
          (reverse binary-levels)))

(defn parse-equality
  "Book: `equality()`, and with it `comparison()`, `term()` and `factor()`.

    equality   -> comparison ( ( \"!=\" | \"==\" ) comparison )* ;
    comparison -> term ( ( \">\" | \">=\" | \"<\" | \"<=\" ) term )* ;
    term       -> factor ( ( \"-\" | \"+\" ) factor )* ;
    factor     -> unary ( ( \"/\" | \"*\" ) unary )* ;"
  [state]
  (binary-parser state))

;;; ---------------------------------------------------------------------------
;;; Challenge 6.2 - the ternary conditional operator
;;;
;;;   conditional -> equality ( "?" expression ":" conditional )? ;
;;;
;;; Two questions the challenge asks, and the answers this encodes:
;;;
;;; * What precedence is allowed between `?` and `:`? In C, a *full* expression
;;;   - the middle operand is unambiguously fenced in by the two tokens, so
;;;   nothing needs to be excluded. `a ? b = c : d` is legal C. We use
;;;   `parse-expression`, which will pick up assignment automatically when
;;;   chapter 8 adds it.
;;;
;;; * Left- or right-associative? Right. `a ? b : c ? d : e` groups as
;;;   `a ? b : (c ? d : e)`, which is what makes `else if` chains work. The
;;;   recursion on the *else* branch (rather than a loop) is what expresses it.
;;; ---------------------------------------------------------------------------

(defn parse-conditional
  "Challenge 6.2. Parse a ternary, or fall through to `equality`."
  [state]
  (let [[state condition] (parse-equality state)]
    (if-let [state (and *allow-conditional?* (match state :question))]
      (let [[state then-branch] (parse-expression state)
            state               (consume state :colon
                                         "Expect ':' after then branch of conditional expression.")
            [state else-branch] (parse-conditional state)]
        [state (ast/conditional condition then-branch else-branch)])
      [state condition])))

;;; ---------------------------------------------------------------------------
;;; Challenge 6.1 - the comma operator
;;;
;;;   expression -> comma ;
;;;   comma      -> conditional ( "," conditional )* ;
;;;
;;; Lowest precedence of all (in C it binds looser than assignment), and
;;; left-associative, so `1, 2, 3` is `(1, 2), 3`. At runtime it will evaluate
;;; the left operand, discard it, and yield the right - but that is chapter 7's
;;; problem; here we only have to build the tree. It reuses the :binary node
;;; with the `,` token as its operator, exactly as C treats it as a binary
;;; operator.
;;; ---------------------------------------------------------------------------

(defn parse-comma
  "Challenge 6.1. Parse a comma expression, or fall through to `conditional`."
  [state]
  (if *allow-comma?*
    (parse-left-assoc-binary state parse-conditional [:comma])
    (parse-conditional state)))

(defn parse-expression
  "Book: `expression()`.

    expression -> equality ;

  The book notes that this rule exists only so the others read well, and so
  that later chapters can add assignment and logical operators by editing one
  production. The two challenge levels slot in here for exactly that reason."
  [state]
  (parse-comma state))

;;; ---------------------------------------------------------------------------
;;; Challenge 6.3 - error productions for a missing left-hand operand
;;;
;;; "Add error productions to handle each binary operator appearing without a
;;;  left-hand operand. In other words, detect a binary operator appearing at
;;;  the beginning of an expression. Report that as an error, but also parse
;;;  and discard a right-hand operand with the appropriate precedence."
;;;
;;; Without this, `* 3` reports the generic "Expect expression." at the `*`,
;;; which is true but unhelpful. With it we say what is actually wrong, and -
;;; the important half - we consume the operator *and its right operand at the
;;; correct precedence*, so the parser is left in a sane place rather than
;;; staring at the same offending token.
;;;
;;; `-` is deliberately absent: it is also a legal *unary* operator, so a
;;; leading `-` is not an error at all and `parse-unary` will have handled it
;;; long before we get here.
;;; ---------------------------------------------------------------------------

(def binary-operators-by-level
  "Token type -> the parse function for its right-hand operand's precedence.

  Derived from `binary-levels` so the two can never drift apart: the operand of
  an error production must be parsed at the level *tighter* than the operator's
  own, which is the operand function that level was built with."
  (let [operand-fns (reduce (fn [acc {:keys [operators]}]
                              (let [operand (:next acc)]
                                {:next (fn [state]
                                         (parse-left-assoc-binary state operand operators))
                                 :table (into (:table acc)
                                              (map (fn [op] [op operand]) operators))}))
                            {:next parse-unary :table {}}
                            (reverse binary-levels))]
    (:table operand-fns)))

(def prefix-operators
  "Operators that may legitimately begin an expression, and so are never a
  missing-left-operand error.

  `-` is the whole reason this set exists: it is both a binary operator (at the
  term level) and a unary one, so a leading `-` is perfectly good Lox and
  `parse-unary` will have consumed it long before the challenge 6.3 check runs.
  `!` is unary-only and never reaches the check at all."
  #{:minus :bang})

(defn- leading-binary-operator
  "The token type at `state` if it is a binary operator that cannot start an
  expression, else nil. `:comma` and `:question` join the list when their
  challenge grammars are enabled."
  [state]
  (let [type (:type (peek-token state))]
    (when (and (not (contains? prefix-operators type))
               (or (contains? binary-operators-by-level type)
                   (and *allow-comma?* (= :comma type))
                   (and *allow-conditional?* (= :question type))))
      type)))

(defn parse-missing-left-operand
  "Challenge 6.3. Report a binary operator used with no left operand, then
  consume its right operand and discard it.

  Returns the state after the discarded operand. The caller is responsible for
  having already recorded the error; we report here and *do not* throw, because
  the whole point is to keep parsing."
  [state type]
  (let [operator (peek-token state)
        state    (advance state)
        operand  (get binary-operators-by-level type parse-conditional)]
    ;; Report *before* parsing the operand: if the right-hand side turns out to
    ;; be broken too it will throw, and the user should still be told about the
    ;; error we actually detected first.
    (err/error operator
               (str "Binary operator '" (:lexeme operator)
                    "' requires a left-hand operand."))
    (let [[state _discarded] (operand state)]
      state)))

;;; ---------------------------------------------------------------------------
;;; The entry point - section 6.4
;;; ---------------------------------------------------------------------------

(defn parse
  "Book: `Parser.parse()`. Parse a token vector into a single expression node.

  Returns the node, or **nil** if a syntax error was found. The book is explicit
  about this contract: \"The parser promises not to crash or hang on invalid
  syntax, but it doesn't promise to return a usable syntax tree if an error is
  found.\" `lox.errors/had-error` is the flag callers should test; the nil is a
  convenience, not the signal.

  Chapter 8 replaces this with a loop over statements, and the `catch` moves to
  the statement boundary so that `synchronize` can do its job. For now there is
  only one expression to parse, so an error unwinds all the way out here.

  Challenge 6.3 is applied before the descent begins: a leading binary operator
  is reported and its operand discarded, and then - since there is nothing left
  to build a tree from in a single-expression grammar - we stop. From chapter 8
  the same check will let the parser recover and continue with the next
  statement."
  [tokens]
  (let [state (make-parser tokens)]
    (try
      (if-let [type (and *binary-error-productions?*
                         (leading-binary-operator state))]
        (do (parse-missing-left-operand state type) nil)
        (let [[state expr] (parse-expression state)]
          ;; Not in the book. Chapter 6's `parse()` parses one expression and
          ;; returns, silently ignoring whatever follows - so `1, 2` would
          ;; quietly parse as `1`. That is a hole in the parser's first duty
          ;; ("detect and report the error"), and it disappears on its own in
          ;; chapter 8, where the statement loop runs until :eof and any
          ;; leftover token is the start of the next statement. Until then we
          ;; close it explicitly, so no input parses "successfully" while a
          ;; chunk of it goes unread.
          (if (at-end? state)
            expr
            (error! state (peek-token state) "Expect end of expression."))))
      (catch clojure.lang.ExceptionInfo e
        (if (parse-error? e)
          nil
          (throw e))))))

(defn parse-source
  "Convenience: scan *and* parse a string of Lox source.

  Kept here rather than in `lox.core` so that nothing which merely wants a
  syntax tree has to depend on the command-line entry point. Scan errors are
  reported by the scanner and leave `lox.errors/had-error` set, just as parse
  errors do, so a caller only has to check the one flag."
  [source]
  (parse (scanner/scan-tokens source)))

;;; ---------------------------------------------------------------------------
;;; Grammar introspection - used by the tests and by the docs
;;; ---------------------------------------------------------------------------

(def grammar
  "The chapter 6 grammar, as text, lowest precedence first.

  Recorded so a test can assert that the implementation and the documented
  grammar were changed together - the kind of drift that is otherwise only
  caught by a human reading both."
  ["expression -> equality ;"
   "equality   -> comparison ( ( \"!=\" | \"==\" ) comparison )* ;"
   "comparison -> term ( ( \">\" | \">=\" | \"<\" | \"<=\" ) term )* ;"
   "term       -> factor ( ( \"-\" | \"+\" ) factor )* ;"
   "factor     -> unary ( ( \"/\" | \"*\" ) unary )* ;"
   "unary      -> ( \"!\" | \"-\" ) unary | primary ;"
   "primary    -> NUMBER | STRING | \"true\" | \"false\" | \"nil\""
   "           |  \"(\" expression \")\" ;"])

(def precedence-order
  "Rule names from loosest-binding to tightest. Book: the table in section 6.1."
  (into [:expression]
        (concat (map :rule binary-levels) [:unary :primary])))
