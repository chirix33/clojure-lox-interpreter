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
;;;;
;;;; =============================================================================
;;;; UPDATED IN CHAPTER 8 - Statements and State
;;;; =============================================================================
;;;;
;;;; Chapter 6's `parse()` was, in the book's words, "a temporary hack to get
;;;; the last chapter up and running": it parsed one expression and stopped.
;;;; Chapter 8 gives the grammar its real starting rule and `parse` its real
;;;; contract - a whole program, as a list of statements.
;;;;
;;;;   program     -> declaration* EOF ;
;;;;
;;;;   declaration -> varDecl
;;;;                | statement ;
;;;;   varDecl     -> "var" IDENTIFIER ( "=" expression )? ";" ;
;;;;
;;;;   statement   -> exprStmt
;;;;                | printStmt
;;;;                | block ;
;;;;   exprStmt    -> expression ";" ;
;;;;   printStmt   -> "print" expression ";" ;
;;;;   block       -> "{" declaration* "}" ;
;;;;
;;;;   expression  -> assignment ;
;;;;   assignment  -> IDENTIFIER "=" assignment
;;;;                | equality ;
;;;;   primary     -> ... | IDENTIFIER ;
;;;;
;;;; Four things in this chapter are worth more than a one-line note.
;;;;
;;;; 1. WHY `declaration` AND `statement` ARE TWO RULES (section 8.2.1).
;;;;    Not every statement may appear everywhere. `if (monday) print "x";` is
;;;;    fine; `if (monday) var beverage = "espresso";` is not, because the
;;;;    scope of that variable would be anybody's guess. So the grammar is
;;;;    stratified the same way chapter 6 stratified expressions by precedence:
;;;;    `declaration` is the "lower precedence" level that may declare names,
;;;;    `statement` the higher one that may not, and a block - which is a
;;;;    `statement` containing `declaration`s - is the way back down, exactly
;;;;    as parentheses are for expressions. Chapter 9 is where the distinction
;;;;    starts paying, since `if` and `while` take a `statement`, not a
;;;;    `declaration`.
;;;;
;;;; 2. WHERE PANIC MODE FINALLY GETS USED. `synchronize` has existed, unused
;;;;    and unit-tested, since chapter 6. `parse-declaration` is the statement
;;;;    boundary the book promised - "This declaration() method is the method
;;;;    we call repeatedly [...] so it's the right place to synchronize when
;;;;    the parser goes into panic mode" - so from this chapter a syntax error
;;;;    costs you one statement rather than the whole file, and a file with two
;;;;    unrelated mistakes reports both.
;;;;
;;;; 3. THE ASSIGNMENT TRICK (section 8.4.1). `a = 1` cannot be parsed by
;;;;    looking at `a`: with one token of lookahead the parser cannot know the
;;;;    `=` is coming, and in `makeList().head.next = node` it may be an
;;;;    unbounded number of tokens away. So `parse-assignment` parses the left
;;;;    side as an ordinary expression and, if an `=` turns up, *converts* the
;;;;    r-value node it just built into an l-value. That works because every
;;;;    valid assignment target also happens to be valid as an expression. It
;;;;    is also why chapter 5 kept a `:grouping` node around something an
;;;;    evaluator could have dropped: `a = 3` is legal and `(a) = 3` is not,
;;;;    and the only thing that distinguishes them is that node.
;;;;
;;;; 4. WHERE ASSIGNMENT SITS AMONG THE CHALLENGE LEVELS. The book slots
;;;;    `assignment` between `expression` and `equality`. With challenges 6.1
;;;;    and 6.2 enabled the chain becomes
;;;;
;;;;      expression -> comma -> assignment -> conditional -> equality
;;;;
;;;;    which is C's order: `,` loosest, then `=`, then `?:`. With both
;;;;    switches off - the default - `comma` and `conditional` fall straight
;;;;    through and the chain is exactly the book's. Chapter 6's note that the
;;;;    ternary's middle operand uses `parse-expression` so it would "pick up
;;;;    assignment automatically when chapter 8 adds it" is now load-bearing:
;;;;    `a ? b = 1 : c` parses, as it does in C.
;;;;
;;;; Chapter 6's single-expression entry point is still here, under the name
;;;; `parse-one-expression`, because `clox --ast` and the chapter 6 test suite
;;;; are both submissions of their own. `parse` is the book's chapter 8 one.
;;;;
;;;; =============================================================================
;;;; UPDATED IN CHAPTER 9 - Control Flow
;;;; =============================================================================
;;;;
;;;; This chapter is almost entirely a parser chapter. Of the four constructs
;;;; it adds, one needs no interpreter support at all and the other three need
;;;; three or four lines each, while every one of them needs a production here.
;;;;
;;;;   statement -> exprStmt | forStmt | ifStmt | printStmt | whileStmt
;;;;              | block ;
;;;;
;;;;   ifStmt    -> "if" "(" expression ")" statement ( "else" statement )? ;
;;;;   whileStmt -> "while" "(" expression ")" statement ;
;;;;   forStmt   -> "for" "(" ( varDecl | exprStmt | ";" )
;;;;                expression? ";" expression? ")" statement ;
;;;;
;;;;   assignment -> IDENTIFIER "=" assignment | logic_or ;
;;;;   logic_or   -> logic_and ( "or" logic_and )* ;
;;;;   logic_and  -> equality ( "and" equality )* ;
;;;;
;;;; Three things here are worth more than a one-line note, and each has its
;;;; own commentary at the definition.
;;;;
;;;; 1. THE DANGLING ELSE is resolved by doing nothing. The grammar above is
;;;;    genuinely ambiguous, and `parse-if-statement` disambiguates it the way
;;;;    every C-family language does - `else` binds to the nearest `if` -
;;;;    without a line of code to that effect, purely because the innermost
;;;;    recursive call is the one holding the token stream when the `else`
;;;;    appears. It is the clearest example in the book of the parser, not the
;;;;    written grammar, being the language's definition.
;;;;
;;;; 2. `for` IS DESUGARED, NOT IMPLEMENTED (section 9.5.1). It produces no
;;;;    node of its own; `parse-for-statement` rewrites it into the `:block`,
;;;;    `:var` and `:while` nodes chapter 8 and this chapter already provide,
;;;;    so `lox.interpreter` contains no mention of `for` whatsoever. This is
;;;;    the first time the front end *translates* rather than transcribes.
;;;;
;;;; 3. THE LOGICAL OPERATORS GET THEIR OWN NODE even though their fields are
;;;;    identical to `:binary`'s, because they short-circuit, and that is a
;;;;    difference in semantics rather than shape. The parser does not
;;;;    implement short-circuiting - "The syntax doesn't care that they
;;;;    short-circuit. That's a semantic concern." - it just picks the node
;;;;    type that tells the interpreter which rule to apply.
;;;;
;;;; Challenge 9.3 adds `break`, behind `lox.token/*allow-break?*`, with the
;;;; "must be inside a loop" rule enforced here as a syntax error via the
;;;; `:loop-depth` field of the parser state. See docs/chapter09-challenges.md.
;;;; =============================================================================
(ns lox.parser
  (:require [lox.ast :as ast]
            [lox.errors :as err]
            [lox.scanner :as scanner]
            [lox.token :as tok]))

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
  "Book: `new Parser(tokens)`.

  CHAPTER 9 adds `:loop-depth`, which the book has no equivalent for because
  the book has no `break`. Challenge 9.3 requires that `break` outside a loop
  be a *syntax* error, so the parser has to know whether it is inside one, and
  \"inside a loop\" is a property of the parse in progress - exactly the kind
  of thing the rest of this map already holds. Java would reach for a mutable
  `int loopDepth` field and a try/finally to restore it; threading it through
  the state map means a rule that fails part-way cannot leave the count wrong,
  because the caller still holds the old state. See `parse-loop-body`."
  [tokens]
  {:tokens (vec tokens) :current 0 :loop-depth 0})

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
  which is the chapter's second error site.

  CHAPTER 8 adds the IDENTIFIER clause:

    primary -> NUMBER | STRING | \"true\" | \"false\" | \"nil\"
            |  \"(\" expression \")\" | IDENTIFIER ;"
  [state]
  (cond
    (check? state :false) [(advance state) (ast/literal false)]
    (check? state :true)  [(advance state) (ast/literal true)]
    (check? state :nil)   [(advance state) (ast/literal nil)]

    (or (check? state :number) (check? state :string))
    (let [state (advance state)]
      [state (ast/literal (:literal (previous-token state)))])

    ;; Chapter 8, section 8.2.1. "That IDENTIFIER clause matches a single
    ;; identifier token, which is understood to be the name of the variable
    ;; being accessed." The whole token is kept, not just its lexeme, so that
    ;; a runtime "Undefined variable" error can name a line.
    (check? state :identifier)
    (let [state (advance state)]
      [state (ast/variable (previous-token state))])

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
;;; Chapter 9, section 9.3 - the logical operators
;;;
;;;   logic_or  -> logic_and ( "or" logic_and )* ;
;;;   logic_and -> equality  ( "and" equality )* ;
;;;
;;; Two new precedence levels, slotted "right between assignment and equality"
;;; with `or` looser than `and` - mirroring C's `||` and `&&`. (The book's own
;;; margin note wonders why the two do not simply share a level the way the
;;; comparison operators do. They do not, in C or here, and chaining
;;; `a or b and c` as `a or (b and c)` is the behaviour everyone expects.)
;;;
;;; These are *shaped* exactly like the four binary levels of chapter 6 - a
;;; left-associative series - and they are deliberately NOT routed through
;;; `parse-left-assoc-binary`, because that helper builds `:binary` nodes and
;;; these must build `:logical` ones. The duplication is three lines and it is
;;; the honest kind: the two constructs really are different, and section 9.3
;;; spends a paragraph on why. (`binary-levels` stays the table of operators
;;; that evaluate both operands, which is what makes it the right table for
;;; challenge 6.3's error productions to consult and the wrong one for these.)
;;;
;;; Short-circuiting does not appear anywhere in this file: "The syntax doesn't
;;; care that they short-circuit. That's a semantic concern." The parser's only
;;; job is to produce a node type that tells the interpreter which semantics to
;;; apply, and the operator token inside it says which of the two.
;;; ---------------------------------------------------------------------------

(defn- parse-logical-series
  "Parse a left-associative series of `op-type` operators whose operands are
  parsed by `operand`, building `:logical` nodes.

  The `:logical` counterpart of `parse-left-assoc-binary`. Factored out
  because `parse-or` and `parse-and` differ in nothing but their operator and
  their operand level - which is the book's observation about `or()` and
  `and()` being near-copies, acted on."
  [state operand op-type]
  (loop [[state expr] (operand state)]
    (if-let [state' (match state op-type)]
      (let [operator      (previous-token state')
            [state right] (operand state')]
        (recur [state (ast/logical expr operator right)]))
      [state expr])))

(defn parse-and
  "Book: `and()`.

    logic_and -> equality ( \"and\" equality )* ;"
  [state]
  (parse-logical-series state parse-equality :and))

(defn parse-or
  "Book: `or()`.

    logic_or -> logic_and ( \"or\" logic_and )* ;"
  [state]
  (parse-logical-series state parse-and :or))

;;; ---------------------------------------------------------------------------
;;; Challenge 6.2 - the ternary conditional operator
;;;
;;;   conditional -> logic_or ( "?" expression ":" conditional )? ;
;;;
;;; CHAPTER 9 changed the operand level from `equality` to `logic_or`, which is
;;; where C puts it: `?:` binds looser than `||` but tighter than `=`, so the
;;; full chain with both challenge levels enabled is now
;;;
;;;   expression -> comma -> assignment -> conditional -> logic_or
;;;              -> logic_and -> equality -> ...
;;;
;;; and `a or b ? c : d` parses as `(a or b) ? c : d`, as it does in C. With
;;; the challenge switches off the chain is exactly the book's chapter 9 one,
;;; because `comma` and `conditional` both fall straight through.
;;;
;;; Two questions the challenge asks, and the answers this encodes:
;;;
;;; * What precedence is allowed between `?` and `:`? In C, a *full* expression
;;;   - the middle operand is unambiguously fenced in by the two tokens, so
;;;   nothing needs to be excluded. `a ? b = c : d` is legal C. We use
;;;   `parse-expression`, which picked up assignment automatically when
;;;   chapter 8 added it and picks up `or`/`and` the same way now.
;;;
;;; * Left- or right-associative? Right. `a ? b : c ? d : e` groups as
;;;   `a ? b : (c ? d : e)`, which is what makes `else if` chains work. The
;;;   recursion on the *else* branch (rather than a loop) is what expresses it.
;;; ---------------------------------------------------------------------------

(defn parse-conditional
  "Challenge 6.2. Parse a ternary, or fall through to `logic_or`.

  CHAPTER 9: the operand level is `parse-or` rather than `parse-equality`, so
  that the two new logical levels sit *below* the ternary, as in C."
  [state]
  (let [[state condition] (parse-or state)]
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

;;; ---------------------------------------------------------------------------
;;; Chapter 8, section 8.4.1 - assignment
;;;
;;;   assignment -> IDENTIFIER "=" assignment | conditional ;
;;;
;;; (Chapter 8's book text writes `| equality`; chapter 9 changes it to
;;; `| logic_or`. `conditional` is the next level down once challenge 6.2 is in
;;; the picture, and falls straight through to `logic_or` when it is off, so
;;; this production is the book's either way.)
;;; ---------------------------------------------------------------------------

(defn parse-assignment
  "Book: `assignment()`.

  Parses the left-hand side as an ordinary expression and, on seeing an `=`,
  converts that r-value node into an l-value - the trick section 8.4.1 builds
  up to. Right-associative, so `a = b = c` is `a = (b = c)`; that is why the
  right-hand side recurses into `parse-assignment` rather than looping the way
  the binary levels do.

  Two details that look like sloppiness and are not:

  * An invalid target is *reported but not thrown*. \"We report an error if the
    left-hand side isn't a valid assignment target, but we don't throw it
    because the parser isn't in a confused state where we need to go into panic
    mode and synchronize.\" We know exactly where we are - just past a complete
    expression and an `=` - so we keep parsing. `lox.errors/had-error` is set,
    so nothing will be executed.

  * On that path we return the left-hand expression, as the book does, and
    discard the parsed right-hand side. The tree is wrong, but it is only ever
    handed to a caller that has already been told not to run it, and parsing
    the right-hand side (rather than leaving it in the token stream) is what
    keeps the *next* statement boundary where the user would expect it.

  Only `:variable` is a valid target today; chapter 12 adds `:get` for
  `object.field = value`."
  [state]
  (let [[state expr] (parse-conditional state)]
    (if-let [state (match state :equal)]
      (let [equals        (previous-token state)
            [state value] (parse-assignment state)]
        (if (ast/variable? expr)
          [state (ast/assign (:name expr) value)]
          (do (err/error equals "Invalid assignment target.")
              [state expr])))
      [state expr])))

(defn parse-comma
  "Challenge 6.1. Parse a comma expression, or fall through to `assignment`.

  CHAPTER 8: the operand level is now `assignment`, not `conditional`, which
  puts `,` looser than `=` as in C - so `a = 1, b = 2` is two assignments and
  not a parse error."
  [state]
  (if *allow-comma?*
    (parse-left-assoc-binary state parse-assignment [:comma])
    (parse-assignment state)))

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

(defn parse-one-expression
  "Chapter 6's `Parser.parse()`. Parse a token vector into a *single*
  expression node, requiring that it is the whole input.

  Returns the node, or **nil** if a syntax error was found. The book is explicit
  about this contract: \"The parser promises not to crash or hang on invalid
  syntax, but it doesn't promise to return a usable syntax tree if an error is
  found.\" `lox.errors/had-error` is the flag callers should test; the nil is a
  convenience, not the signal.

  RENAMED IN CHAPTER 8, from `parse`. The book replaces this method outright -
  it was \"a temporary hack to get the last chapter up and running\" - and
  `parse` below is now the book's chapter 8 version, returning a list of
  statements. This one survives under a new name for two reasons: `clox --ast`
  still demonstrates chapter 6 from the command line, and challenge 8.1's REPL
  needs exactly this - \"can the whole of this input be read as one
  expression?\" - to tell an expression the user typed from a statement.

  Challenge 6.3 is applied before the descent begins: a leading binary operator
  is reported and its operand discarded, and then - since there is nothing left
  to build a tree from in a single-expression grammar - we stop."
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
  "Convenience: scan *and* parse a string of Lox source as one expression.

  Kept here rather than in `lox.core` so that nothing which merely wants a
  syntax tree has to depend on the command-line entry point. Scan errors are
  reported by the scanner and leave `lox.errors/had-error` set, just as parse
  errors do, so a caller only has to check the one flag.

  Chapter 6's entry point; `parse-program` is chapter 8's."
  [source]
  (parse-one-expression (scanner/scan-tokens source)))

;;; ---------------------------------------------------------------------------
;;; Chapter 8 - statements, declarations and blocks
;;;
;;; Each rule keeps the signature the expression rules use,
;;;
;;;     state -> [state' stmt]
;;;
;;; except `parse-block`, which returns [state' stmts] because the book's
;;; `block()` also returns a bare list rather than a `Stmt.Block`:
;;;
;;;   "Having block() return the raw list of statements and leaving it to
;;;    statement() to wrap the list in a Stmt.Block looks a little odd. I did
;;;    it that way because we'll reuse block() later for parsing function
;;;    bodies and we don't want that body wrapped in a Stmt.Block."
;;;
;;; Chapter 10 is where that pays off.
;;; ---------------------------------------------------------------------------

(declare parse-declaration parse-statement parse-var-declaration)

(defn parse-print-statement
  "Book: `printStatement()`.

    printStmt -> \"print\" expression \";\" ;

  The `print` token has already been consumed by `parse-statement`."
  [state]
  (let [[state value] (parse-expression state)
        state         (consume state :semicolon "Expect ';' after value.")]
    [state (ast/print-stmt value)]))

(defn parse-expression-statement
  "Book: `expressionStatement()`.

    exprStmt -> expression \";\" ;

  The fall-through case. \"It's hard to proactively recognize an expression
  from its first token\", so anything that is not a recognised statement form
  is assumed to be one of these - and if it is not an expression either,
  `parse-primary` reports \"Expect expression.\" at the offending token."
  [state]
  (let [[state expr] (parse-expression state)
        state        (consume state :semicolon "Expect ';' after expression.")]
    [state (ast/expression-stmt expr)]))

(defn parse-block
  "Book: `block()`. Parse `declaration*` up to the closing brace.

    block -> \"{\" declaration* \"}\" ;

  Returns [state statements]; the `{` has already been consumed.

  The `at-end?` test in the loop condition is not redundant with the `}` test:
  \"We have to be careful to avoid infinite loops, even when parsing invalid
  code. If the user forgets a closing }, the parser needs to not get stuck.\"
  Running out of tokens drops out of the loop and lets `consume` report the
  missing brace against the :eof token.

  Statements that failed to parse come back as nil from `parse-declaration`
  and are dropped. The book appends the null to its list, which is harmless
  there only because `hadError` guarantees the list is never executed; an
  `:stmts` field here is checked at construction, so a nil would be caught by
  `block-stmt` instead - a worse place to find out. Dropping them keeps the
  partial tree well-formed and still reports every error."
  [state]
  (loop [state state statements []]
    (if (or (check? state :right-brace) (at-end? state))
      [(consume state :right-brace "Expect '}' after block.") statements]
      (let [[state stmt] (parse-declaration state)]
        (recur state (if stmt (conj statements stmt) statements))))))


;;; ---------------------------------------------------------------------------
;;; Chapter 9 - Control Flow
;;;
;;;   statement -> exprStmt | forStmt | ifStmt | printStmt | whileStmt | block ;
;;;
;;;   ifStmt    -> "if" "(" expression ")" statement ( "else" statement )? ;
;;;   whileStmt -> "while" "(" expression ")" statement ;
;;;   forStmt   -> "for" "(" ( varDecl | exprStmt | ";" )
;;;                expression? ";" expression? ")" statement ;
;;;
;;; Each branch body is a `statement`, not a `declaration`, which is chapter
;;; 8's stratification finally earning its keep: `if (a) var b = 1;` does not
;;; parse, because there would be no answer to the question of what scope `b`
;;; lives in. A braced body is a `block`, which is a `statement` that contains
;;; `declaration`s - so the way to declare something in a branch is to open a
;;; scope for it, which is the only reading that has a sensible answer.
;;;
;;; Note what the parentheses around each condition are and are not for. The
;;; closing one is load-bearing: without a delimiter the parser could not tell
;;; where the condition expression ends and the body statement begins. The
;;; opening one does nothing at all, and the book's aside on why it is there
;;; anyway - Dennis Ritchie wanted a `)` without an unbalanced `(` - is the
;;; honest answer. Lua and some BASICs use a trailing `then` instead; Go and
;;; Swift require a braced body and use the `{` as the delimiter.
;;; ---------------------------------------------------------------------------

(defn parse-loop-body
  "Parse the body `statement` of a loop, with `:loop-depth` raised for the
  duration. Returns [state' body] with the *original* depth restored.

  Only challenge 9.3 cares: `break` is legal exactly when this count is
  positive. The restore is what makes

      while (a) break;   break;

  report the second `break` and not the first, and because the count travels
  in the threaded state rather than in a mutable field, there is no try/finally
  to forget - a rule that throws out of here leaves the caller's state, and so
  the caller's depth, untouched.

  Nesting is counted rather than flagged so that a `break` in an *outer* loop's
  body remains legal after an inner loop has closed."
  [state]
  (let [outer-depth  (:loop-depth state)
        [state body] (parse-statement (update state :loop-depth inc))]
    [(assoc state :loop-depth outer-depth) body]))

(defn parse-if-statement
  "Book: `ifStatement()`.

    ifStmt -> \"if\" \"(\" expression \")\" statement ( \"else\" statement )? ;

  The `if` token has already been consumed.

  THE DANGLING ELSE. `if (a) if (b) x(); else y();` is genuinely ambiguous as
  a context-free grammar - the `else` could close either `if` - and the fix
  the book takes is the one every C-family language takes: bind `else` to the
  nearest preceding `if`. There is no code here that implements that rule,
  which is the interesting part:

    \"Our parser conveniently does that already. Since ifStatement() eagerly
     looks for an else before returning, the innermost call to a nested series
     will claim the else clause for itself before returning to the outer if
     statements.\"

  The inner call is the one holding the token stream when `else` comes into
  view, so it takes it. Recursive descent resolves the ambiguity by being
  recursive descent. The book notes the alternative - a grammar that splits
  every statement rule into an with-else and a without-else pair - and calls
  it annoying, which it is.

  A missing `else` leaves the branch nil rather than an empty block; see the
  `:stmt-or-nil` note in `lox.ast`."
  [state]
  (let [state               (consume state :left-paren "Expect '(' after 'if'.")
        [state condition]   (parse-expression state)
        state               (consume state :right-paren
                                     "Expect ')' after if condition.")
        [state then-branch] (parse-statement state)
        [state else-branch] (if-let [state' (match state :else)]
                              (parse-statement state')
                              [state nil])]
    [state (ast/if-stmt condition then-branch else-branch)]))

(defn parse-while-statement
  "Book: `whileStatement()`.

    whileStmt -> \"while\" \"(\" expression \")\" statement ;

  The `while` token has already been consumed. \"The grammar is dead simple
  and this is a straight translation of it\" - the only thing here that is not
  in the book is `parse-loop-body` in place of a bare `parse-statement`, which
  is challenge 9.3's bookkeeping."
  [state]
  (let [state             (consume state :left-paren "Expect '(' after 'while'.")
        [state condition] (parse-expression state)
        state             (consume state :right-paren
                                   "Expect ')' after condition.")
        [state body]      (parse-loop-body state)]
    [state (ast/while-stmt condition body)]))

;;; ---------------------------------------------------------------------------
;;; Section 9.5.1 - desugaring the `for` loop
;;;
;;; This is the one construct in the chapter that adds no node type and no
;;; interpreter code. A C-style `for` is syntactic sugar: everything it does
;;; can be said with the statements we already have, so the parser says it that
;;; way and the back end never finds out.
;;;
;;;   for (var i = 0; i < 10; i = i + 1) print i;
;;;
;;; becomes the tree for
;;;
;;;   {
;;;     var i = 0;
;;;     while (i < 10) {
;;;       print i;
;;;       i = i + 1;
;;;     }
;;;   }
;;;
;;; Three wrappings, built from the inside out because each one encloses the
;;; last - which is why the book builds them in reverse source order:
;;;
;;;   1. the increment is appended to the body, inside a block, so that it runs
;;;      at the end of every iteration;
;;;   2. condition + body become a `while`, with `true` substituted for an
;;;      omitted condition to give `for (;;)` its infinite loop;
;;;   3. the initializer is prepended, inside a second block, so that it runs
;;;      once - and so that a `var` initializer is scoped to the loop and not
;;;      to the enclosing block. That outer block is what makes `i` invisible
;;;      after the loop, which is the behaviour the chapter promises when it
;;;      says the variable "is scoped to the rest of the for loop".
;;;
;;; Each wrapping is also skipped when its clause was omitted, so `for (;;) x;`
;;; desugars to a bare `while (true) x;` with no empty blocks around it. That
;;; matters for more than tidiness: a `--ast` dump of a `for` loop is how a
;;; reader *sees* the desugaring, so the tree should contain what the source
;;; said and nothing else.
;;;
;;; One consequence worth stating, since it is a real behavioural difference
;;; and not just a representation choice: because the increment lives at the
;;; end of the body, a challenge 9.3 `break` skips it, which is correct (the
;;; loop is over). A `continue`, had we added one, would skip it too, which
;;; would be *wrong* - and that is the standard argument for why `continue`
;;; cannot be desugared this way and `for` sometimes earns a real node after
;;; all. See docs/chapter09-challenges.md.
;;; ---------------------------------------------------------------------------

(defn parse-for-initializer
  "The first clause of a `for`: a var declaration, an expression statement, or
  nothing at all. Returns [state' initializer-or-nil].

  \"If the token following that is a semicolon then the initializer has been
  omitted. Otherwise, we check for a var keyword to see if it's a variable
  declaration. If neither of those matched, it must be an expression. We parse
  that and wrap it in an expression statement so that the initializer is
  always of type Stmt.\"

  Both non-empty branches consume the `;` themselves, as part of the `varDecl`
  / `exprStmt` they parse. This is the one place in the grammar where a
  declaration and a statement are both allowed, which the book cheerfully
  admits undercuts chapter 8's prose: \"In a previous chapter, I said we can
  split expression and statement syntax trees into two separate class
  hierarchies because there's no single place in the grammar that allows both
  an expression and a statement. That wasn't entirely true, I guess.\" The
  *families* are still disjoint, though - this clause produces a `stmt` either
  way, which is exactly why the book wraps the expression case."
  [state]
  (cond
    (check? state :semicolon) [(advance state) nil]
    (check? state :var)       (parse-var-declaration (advance state))
    :else                     (parse-expression-statement state)))

(defn parse-for-statement
  "Book: `forStatement()`. Parse a C-style `for` and desugar it on the spot.

    forStmt -> \"for\" \"(\" ( varDecl | exprStmt | \";\" )
               expression? \";\" expression? \")\" statement ;

  The `for` token has already been consumed. Returns a tree built only from
  `:block`, `:while` and whatever the clauses produced - never a `:for` node,
  because there is no such thing. See the commentary above."
  [state]
  (let [state (consume state :left-paren "Expect '(' after 'for'.")

        ;; Clause 1 - the initializer. Runs once, before anything else.
        [state initializer] (parse-for-initializer state)

        ;; Clause 2 - the condition. Checked before every iteration, the
        ;; first one included. An omitted condition is an infinite loop.
        [state condition]   (if (check? state :semicolon)
                              [state nil]
                              (parse-expression state))
        state (consume state :semicolon "Expect ';' after loop condition.")

        ;; Clause 3 - the increment. Runs at the end of every iteration and
        ;; its value is discarded, so it is useful only for its side effect.
        [state increment]   (if (check? state :right-paren)
                              [state nil]
                              (parse-expression state))
        state (consume state :right-paren "Expect ')' after for clauses.")

        ;; The body. `parse-loop-body` rather than `parse-statement`, so that
        ;; challenge 9.3's `break` is legal inside a `for` as well as inside a
        ;; `while`. The desugaring below happens *after* this, so the
        ;; `:loop-depth` bookkeeping cannot be left to the `:while` node we
        ;; are about to build - by then the body has long since been parsed.
        [state body]        (parse-loop-body state)

        ;; --- the desugaring, built inside out ---
        body (if increment
               (ast/block-stmt [body (ast/expression-stmt increment)])
               body)
        body (ast/while-stmt (or condition (ast/literal true)) body)
        body (if initializer
               (ast/block-stmt [initializer body])
               body)]
    [state body]))

;;; ---------------------------------------------------------------------------
;;; Challenge 9.3 - `break`
;;;
;;;   breakStmt -> "break" ";" ;
;;;
;;; "It should be a syntax error to have a break statement appear outside of
;;;  any enclosing loop." Two halves, and only the first is obvious:
;;;
;;;   * it is a *syntax* error, so it is detected here and not at run time,
;;;     and a program containing one never executes at all - not even the
;;;     statements before the offending `break`;
;;;   * "outside of any enclosing loop" is about the *statically* enclosing
;;;     loop, which is what makes it checkable at parse time at all.
;;;     `:loop-depth` counts exactly that, and the challenge's reminder that
;;;     "the break may be nested inside other blocks and if statements that
;;;     also need to be exited" is handled for free: blocks and `if`s do not
;;;     touch the depth, so a `break` three blocks deep inside a loop body is
;;;     still inside the loop.
;;;
;;; The error is reported but *not thrown*, for the reason section 8.4.1 gives
;;; for "Invalid assignment target.": the parser is not confused. We are
;;; looking at `break` followed by `;`, we know precisely how to carry on, and
;;; panicking would discard the rest of a perfectly parseable statement. The
;;; node is built as if nothing were wrong; `had-error` guarantees it is never
;;; run, and parsing continues so that a second, unrelated mistake is still
;;; reported in the same pass.
;;; ---------------------------------------------------------------------------

(defn parse-break-statement
  "Challenge 9.3. Parse `break ;`, reporting an error if we are not in a loop.

  The `break` token has already been consumed."
  [state]
  (let [break-token (previous-token state)
        state       (consume state :semicolon "Expect ';' after 'break'.")]
    (when-not (pos? (:loop-depth state 0))
      (err/error break-token "Must be inside a loop to use 'break'."))
    [state (ast/break-stmt break-token)]))

(defn parse-statement
  "Book: `statement()`.

    statement -> exprStmt | forStmt | ifStmt | printStmt | whileStmt | block ;

  \"We determine which specific statement rule is matched by looking at the
  current token\" - the leading keyword or brace - and fall through to an
  expression statement when none matches.

  CHAPTER 9 adds three clauses, four with challenge 9.3's `break`. They are
  listed in the book's order, which is alphabetical by keyword rather than
  meaningful: every clause is selected by a distinct leading token, so no
  clause can shadow another and the order is free. Chapter 10 adds `return`."
  [state]
  (cond
    (check? state :for)
    (parse-for-statement (advance state))

    (check? state :if)
    (parse-if-statement (advance state))

    (check? state :print)
    (parse-print-statement (advance state))

    (check? state :while)
    (parse-while-statement (advance state))

    ;; Challenge 9.3. Only reachable when `lox.token/*allow-break?*` is on,
    ;; because the scanner produces no `:break` token otherwise - with the
    ;; switch off `break` arrives as an `:identifier` and falls through to the
    ;; expression statement below, exactly as in the book's Lox.
    (check? state :break)
    (parse-break-statement (advance state))

    (check? state :left-brace)
    (let [[state statements] (parse-block (advance state))]
      [state (ast/block-stmt statements)])

    :else
    (parse-expression-statement state)))

(defn parse-var-declaration
  "Book: `varDeclaration()`.

    varDecl -> \"var\" IDENTIFIER ( \"=\" expression )? \";\" ;

  The `var` token has already been consumed. A missing initializer leaves the
  field nil - *not* a `(literal nil)` node - which is the distinction section
  8.3.1 needs in order to say \"Lox sets a variable to nil if it isn't
  explicitly initialized\", and that challenge 8.2 needs in order to say the
  opposite."
  [state]
  (let [state (consume state :identifier "Expect variable name.")
        name  (previous-token state)
        [state initializer]
        (if-let [state' (match state :equal)]
          (parse-expression state')
          [state nil])
        state (consume state :semicolon
                       "Expect ';' after variable declaration.")]
    [state (ast/var-stmt name initializer)]))

(defn parse-declaration
  "Book: `declaration()`.

    declaration -> varDecl | statement ;

  **This is the synchronization point.** The machinery has been sitting in
  this file unused since chapter 6, and the book is explicit that here is
  where it belongs: \"This declaration() method is the method we call
  repeatedly when parsing a series of statements in a block or a script, so
  it's the right place to synchronize when the parser goes into panic mode.\"

  Returns [state stmt], where `stmt` is nil if this declaration failed. On
  failure the state comes from the exception rather than from the caller: the
  cursor inside a half-parsed statement is where `synchronize` needs to start
  discarding from, and a pure parser cannot read it off a shared object the
  way the book's mutable `current` field lets it.

  Challenge 6.3's check runs here rather than once per *file* as it did in
  chapter 6, which is what the comment there anticipated: a statement that
  begins with a stray binary operator is now reported, discarded, and followed
  by an attempt at the next statement."
  [state]
  (try
    (if-let [type (and *binary-error-productions?*
                       (leading-binary-operator state))]
      [(parse-missing-left-operand state type) nil]
      (if-let [state' (match state :var)]
        (parse-var-declaration state')
        (parse-statement state)))
    (catch clojure.lang.ExceptionInfo e
      (if (parse-error? e)
        [(synchronize (:state (ex-data e))) nil]
        (throw e)))))

(defn parse
  "Book: chapter 8's `Parser.parse()`. Parse a token vector into a **vector of
  statements** - a whole Lox program.

    program -> declaration* EOF ;

  Never nil and never throws: a program with syntax errors yields the
  statements that did parse, and `lox.errors/had-error` is the signal that the
  result must not be executed. That is a strictly different contract from
  chapter 6's `parse-one-expression`, which returned nil, and it is why
  `lox.core/run` tests the flag rather than the value - a partial list is
  truthy but just as unrunnable.

  \"The mandatory end token ensures the parser consumes the entire input and
  doesn't silently ignore erroneous unconsumed tokens at the end of a script.\"
  Chapter 6 had to bolt an explicit \"Expect end of expression.\" check on to
  get that property; here it falls out of looping until :eof, so that
  stop-gap now lives only in `parse-one-expression`.

  The loop terminates because every iteration consumes at least one token: a
  successful declaration ends on a `;` or `}`, and a failed one goes through
  `synchronize`, which advances before it starts looking."
  [tokens]
  (loop [state (make-parser tokens) statements []]
    (if (at-end? state)
      statements
      (let [[state stmt] (parse-declaration state)]
        (recur state (if stmt (conj statements stmt) statements))))))

(defn parse-program
  "Convenience: scan *and* parse a string of Lox source into statements.
  Chapter 8's counterpart to chapter 6's `parse-source`."
  [source]
  (parse (scanner/scan-tokens source)))

;;; ---------------------------------------------------------------------------
;;; Challenge 8.1 - a REPL that takes both statements and expressions
;;;
;;; "The REPL no longer supports entering a single expression and automatically
;;;  printing its result value. That's a drag. Add support to the REPL to let
;;;  users type in both statements and expressions. If they enter a statement,
;;;  execute it. If they enter an expression, evaluate it and display the
;;;  result value."
;;;
;;; The classification cannot be done by peeking at the first token: `a` begins
;;; an expression (`a + 1`) and also a statement (`a = 1;`). What distinguishes
;;; them is whether the *entire* line parses as one expression - which is
;;; precisely the question chapter 6's entry point answers, so this is a second
;;; reason to have kept it.
;;;
;;; Trying and backtracking means a failed attempt must leave no trace, or the
;;; REPL would report "Expect expression." for every perfectly good `print`
;;; statement. Leaving no trace takes *two* things, and it is worth naming them
;;; separately because the first one alone looks sufficient and is not:
;;;
;;;   * the recorded error state is rolled back, via
;;;     `lox.errors/error-state` / `restore-error-state!`; and
;;;   * printing is suppressed for the duration of the attempt, via
;;;     `lox.errors/*print-errors?*`.
;;;
;;; Without the second, a rolled-back error has still been written to stderr
;;; and the user has still read it - the state is clean and the session is not.
;;; (That is a failure a unit test asserting on `error-messages` would never
;;; have caught, which is why the chapter 8 suite also captures stderr.)
;;;
;;; The snapshot is taken *after* scanning, and scanning happens outside the
;;; suppression, so a genuine scan error ("Unterminated string.") is reported
;;; once, printed once, and survives the rollback.
;;; ---------------------------------------------------------------------------

(defn parse-repl
  "Challenge 8.1. Classify one line of REPL input.

  Returns `[:expression expr]` if the whole line is a single expression, else
  `[:statements stmts]`.

  A failed expression attempt is silent and leaves no error state behind; the
  statement parse that follows reports whatever is genuinely wrong."
  [source]
  (let [tokens (scanner/scan-tokens source)
        saved  (err/error-state)
        expr   (binding [err/*print-errors?* false]
                 (parse-one-expression tokens))]
    (if (and (some? expr) (not @err/had-error))
      [:expression expr]
      (do (err/restore-error-state! saved)
          [:statements (parse tokens)]))))

;;; ---------------------------------------------------------------------------
;;; Grammar introspection - used by the tests and by the docs
;;; ---------------------------------------------------------------------------

(def grammar
  "The expression grammar, as text, lowest precedence first.

  Recorded so a test can assert that the implementation and the documented
  grammar were changed together - the kind of drift that is otherwise only
  caught by a human reading both.

  CHAPTER 8 changed two lines: `expression` now routes to `assignment`, and
  `primary` gained its IDENTIFIER clause.

  CHAPTER 9 changed one and added two: `assignment` falls through to
  `logic_or` instead of `equality`, and the two logical levels sit between."
  ["expression -> assignment ;"
   "assignment -> IDENTIFIER \"=\" assignment | logic_or ;"
   "logic_or   -> logic_and ( \"or\" logic_and )* ;"
   "logic_and  -> equality ( \"and\" equality )* ;"
   "equality   -> comparison ( ( \"!=\" | \"==\" ) comparison )* ;"
   "comparison -> term ( ( \">\" | \">=\" | \"<\" | \"<=\" ) term )* ;"
   "term       -> factor ( ( \"-\" | \"+\" ) factor )* ;"
   "factor     -> unary ( ( \"/\" | \"*\" ) unary )* ;"
   "unary      -> ( \"!\" | \"-\" ) unary | primary ;"
   "primary    -> NUMBER | STRING | \"true\" | \"false\" | \"nil\""
   "           |  \"(\" expression \")\" | IDENTIFIER ;"])

(def statement-grammar
  "The chapter 8 statement grammar, as text, starting rule first.

  Separate from `grammar` because the two really are disjoint grammars over
  disjoint node families - see `lox.ast`, section 8.1.1.

  CHAPTER 9 adds three productions - `ifStmt`, `whileStmt` and `forStmt` -
  and three clauses to `statement`. Note that `forStmt` is listed here even
  though no `:for` node exists: it is a real production of the *syntactic*
  grammar, which is what this documents. What the parser builds from it is a
  separate question, answered by `parse-for-statement`'s desugaring."
  ["program     -> declaration* EOF ;"
   "declaration -> varDecl | statement ;"
   "varDecl     -> \"var\" IDENTIFIER ( \"=\" expression )? \";\" ;"
   "statement   -> exprStmt | forStmt | ifStmt | printStmt | whileStmt"
   "             | block ;"
   "exprStmt    -> expression \";\" ;"
   "forStmt     -> \"for\" \"(\" ( varDecl | exprStmt | \";\" )"
   "               expression? \";\" expression? \")\" statement ;"
   "ifStmt      -> \"if\" \"(\" expression \")\" statement"
   "               ( \"else\" statement )? ;"
   "printStmt   -> \"print\" expression \";\" ;"
   "whileStmt   -> \"while\" \"(\" expression \")\" statement ;"
   "block       -> \"{\" declaration* \"}\" ;"])

(def break-grammar
  "Challenge 9.3's one production, kept apart from `statement-grammar` for the
  same reason the challenge is kept behind a switch: it is not part of the
  book's Lox. With `lox.token/*allow-break?*` on, `statement` gains a
  `| breakStmt` clause as well."
  ["breakStmt   -> \"break\" \";\" ;"])

(def precedence-order
  "Rule names from loosest-binding to tightest. Book: the table in section 6.1.

  CHAPTER 8 and 9 both widened this. `:assignment` is loosest of the book's
  levels, then chapter 9's `:logic-or` and `:logic-and`, and only then the
  four `binary-levels` of chapter 6. The two challenge levels are *not* here:
  `comma` and `conditional` are off by default, so the default grammar's
  precedence table is exactly the book's, which is what this records."
  (into [:expression :assignment :logic-or :logic-and]
        (concat (map :rule binary-levels) [:unary :primary])))

(def logical-levels
  "The chapter 9 precedence levels, loosest first, as data.

  The counterpart of `binary-levels`, and deliberately a separate table: these
  two operators short-circuit, so they must *not* be swept up by anything that
  iterates the operators which evaluate both operands - challenge 6.3's error
  productions above being the live example."
  [{:rule :logic-or  :token :or  :operand :logic-and}
   {:rule :logic-and :token :and :operand :equality}])

(def statement-rules
  "The statement rules, in the order `statement-grammar` documents them."
  [:program :declaration :var-decl :statement :expr-stmt :for-stmt :if-stmt
   :print-stmt :while-stmt :block])
