# Clojure Lox

**Name**: Ashraf Abdul-Muumin

## Description
A tree-walking interpreter for the **Lox** programming language, written in
**Clojure**.

[Crafting Interpreters]: https://craftinginterpreters.com/

**Current state: Chapter 6 — Parsing Expressions.**

| Chapter | Topic | Status |
| ------- | ----- | ------ |
| 4  | Scanning                | ✅ complete |
| 5  | Representing Code       | ✅ complete |
| 6  | Parsing Expressions     | ✅ complete |
| 7  | Evaluating Expressions  | ✅ complete |
| 8  | Statements and State    | not started |
| 9  | Control Flow            | not started |
| 10 | Functions               | not started |
| 11 | Resolving and Binding   | not started |
| 12 | Classes                 | not started |
| 13 | Inheritance             | not started |

---

## Requirements

* **JDK 11 or newer** (developed against OpenJDK 17).

That is the only hard requirement. The `scripts/` wrappers download the three
Clojure core jars into `lib/` on first use, so you do **not** need Leiningen or
the Clojure CLI installed. If you do have them, the standard commands work too.

## Build and run

### Option A — no build tool required (recommended for grading)

```bash
./scripts/test.sh                        # run the whole unit-test suite
./scripts/lox.sh examples/ch07_evaluating.lox   # evaluate and print a value
./scripts/lox.sh                         # interactive REPL (Ctrl-D to exit)
./scripts/lox.sh --ast examples/ch06_parsing.lox       # chapter 6 syntax tree
./scripts/lox.sh --tokens examples/ch04_scanning.lox   # chapter 4 token dump
./scripts/astdemo.sh                     # chapter 5: hand-built syntax tree
```

As of chapter 7 the pipeline runs end to end — the REPL scans, parses and
**evaluates** what you type, and prints the resulting value:

```
> 1 + 2 * 3
7
> (1 + 2) * 3
9
> "cr" + "aft"
craft
> 10 / 4
2.5
> !nil
true
> 1 == "1"
false
> -"muffin"
Operand must be a number.
[line 1]
> 1 +
[line 1] Error at end: Expect expression.
```

Note the last two: a runtime error and a syntax error both leave the session
alive. Earlier stages of the pipeline stay reachable by flag, so each
chapter's submission is still runnable on its own:

| Flag | Chapter | Pipeline |
| ---- | ------- | -------- |
| `--tokens` | 4 | source → tokens |
| `--ast` | 6 | source → syntax tree |
| *(none)* | 7 | source → value |

On Windows PowerShell, use the `.ps1` equivalents:

```bash
powershell -File scripts/test.ps1
powershell -File scripts/lox.ps1 examples/ch07_evaluating.lox
powershell -File scripts/astdemo.ps1
```

The first run downloads `clojure`, `spec.alpha` and `core.specs.alpha` from
Maven Central into `lib/` (about 4.7 MB) and caches them there. `lib/` is
git-ignored.

### Option B — Leiningen

```bash
lein test
lein run examples/ch07_evaluating.lox
lein run                                 # REPL
lein run -m lox.ast-printer              # chapter 5 demo
lein uberjar
```

### Option C — Clojure CLI (tools.deps)

```bash
clojure -M:test
clojure -M -m lox.core examples/ch07_evaluating.lox
clojure -M -m lox.core                   # REPL
clojure -M -m lox.ast-printer            # chapter 5 demo
```

### Exit codes

Following the book's use of the UNIX `sysexits.h` conventions:

| Code | Meaning |
| ---- | ------- |
| 0    | success |
| 64   | bad command line usage |
| 65   | the source had a static (scan/parse) error — it never ran |
| 70   | the source ran and hit a runtime error (chapter 7) |

---

## Layout

```
src/lox/
  token.clj      Chapter 4 — token types, the reserved-word table, token maps
  errors.clj     Chapter 4 — error reporting (`hadError`, `report`, `error`)
  scanner.clj    Chapter 4 — the scanner proper
  core.clj       Chapter 4 — CLI entry point: main / runFile / runPrompt / run
  ast.clj        Chapter 5 — the expression AST: define-ast, nodes, visitors
  ast_printer.clj Chapter 5 — AstPrinter (5.4) and the RPN printer (chal. 5.3)
  parser.clj     Chapter 6 — the recursive descent parser
  interpreter.clj Chapter 7 — the tree-walking evaluator and runtime errors

test/lox/
  test_util.clj                 shared fixtures and helpers
  test_runner.clj               dependency-free runner (`-m lox.test-runner`)
  chapter_04_scanning_test.clj  Chapter 4 tests
  chapter_05_ast_test.clj       Chapter 5 tests
  chapter_06_parsing_test.clj   Chapter 6 tests
  chapter_07_evaluating_test.clj Chapter 7 tests

examples/
  ch04_scanning.lox             sample input exercising the lexical grammar
  ch06_parsing.lox              one expression exercising every precedence level
  ch07_evaluating.lox           one expression to evaluate, plus a tour in comments

scripts/
  bootstrap.{sh,ps1}  fetch the Clojure jars into lib/
  test.{sh,ps1}       run the test suite
  lox.{sh,ps1}        run a script or the REPL
  astdemo.{sh,ps1}    run the chapter 5 syntax-tree demo

docs/
  chapter05-challenges.md   written answers to challenges 5.1 and 5.2
  chapter06-challenges.md   challenges 6.1-6.3: grammars, design notes, switches
  chapter07-challenges.md   challenges 7.1-7.3: comparisons, string +, divide by zero
```

Every source file carries a header comment naming the chapter it comes from
and a short description of what that chapter added.

---

## Design notes

Translating Java to Clojure meant a few deliberate choices. They are documented
in the file headers, but in summary:

**Tokens are maps, not classes.** A token is
`{:type :left-paren :lexeme "(" :literal nil :line 1}`. Token types are
keywords (`:left-paren`, `:while`, `:eof`) rather than a Java `enum`. Values
compare with `=`, print readably, and destructure directly at every use site.

**The scanner is a pure function.** The book's `Scanner` mutates the fields
`start`, `current` and `line`. Here those live in a *scanner state map* that is
threaded through pure helper functions, and the main `while (!isAtEnd())` loop
becomes `loop`/`recur`. Each helper is named after its Java counterpart, and
`scanner.clj`'s header has the full correspondence table.

**`match()` is split into a predicate plus an explicit `advance`.** The Java
`match()` both tests and consumes. Splitting it keeps consumption visible at the
call site, which matters in a language without statement sequencing by default.

**Errors are recorded as well as printed.** `lox.errors` keeps the book's
`hadError` flag and `report`/`error` functions, but also appends every reported
error to an atom. This makes error *reporting* directly unit-testable rather
than forcing tests to scrape stderr, and costs nothing at runtime.

### Chapter 5 - Representing Code

**Nodes are maps tagged with `:node`.** A syntax-tree node is
`{:node :binary :left <expr> :operator <token> :right <expr>}`. The book needs
an abstract `Expr` class with a nested subclass per production; section 5.2.1
("Disoriented objects") then admits these classes are pure data with no
behaviour and that "this style is very natural in functional languages". In
Clojure it simply is the data. The tag key is `:node` and not `:type` because
tokens already use `:type`, and from chapter 8 onward statement node names
collide with token types (`print`, `var`, `while`, ...); a separate key keeps
`lox.token/token?` and `lox.ast/expr?` from ever confusing the two.

**`GenerateAst` becomes a macro.** The book writes a Java program that *prints*
`Expr.java`, because Java cannot turn "a name and a list of typed fields" into
a class by itself. `lox.ast/define-ast` takes the same declarative description
and expands, at compile time and with no generated file to check in, into the
constructors, predicates, node-type set and field table:

```clojure
(define-ast expr
  {:binary   [[:expr left] [:token operator] [:expr right]]
   :grouping [[:expr expression]]
   :literal  [[:object value]]
   :unary    [[:token operator] [:expr right]]})
```

That is a line-for-line translation of the four strings the book passes to
`defineAst()`, and the book's own aside - "an actual scripting language would
be a better fit for this than Java" - is the argument for doing it this way.

**The field types are kept, and checked.** The `Expr`/`Token`/`Object` types in
the book's descriptions exist for `javac`. Clojure will not check them, so the
constructors check them at run time instead, failing where the node is *built*
(in the parser) rather than several recursive calls later in the interpreter.
The message names the node, the field, what was expected and what arrived.

**A visitor is a map of functions.** `Expr.Visitor<R>` is, with the Java
removed, a lookup from node kind to a function returning `R`. `accept` does
that lookup in one step rather than the pattern's two, and `defvisitor` checks
at *macroexpansion* time that every production is handled - which is the one
genuine benefit of the Java interface (the compiler complaining when you add a
node type and forget a visit method). Tag a visitor `^:partial` to opt out on
purpose.

**Shape is available as data.** Because the field table survives to run time,
`(children node)` returns a node's sub-expressions with no per-node-type code,
so generic traversals (counting nodes, measuring depth, searching) will work
for every node type chapters 8-13 add, for free. That is the other half of the
expression problem section 5.3 describes.

### Chapter 6 - Parsing Expressions

**The parser is a value, not a mutable object.** The book's `Parser` holds a
token list and a mutable cursor `int current`, and every helper advances it by
side effect. Here the parser is a state map `{:tokens [...] :current 0}` and
every grammar rule is a pure function

```
state -> [state' expr]
```

which is the functional reading of `private Expr rule()`: the `Expr` is the
second element, and the cursor movement Java hid in `this.current` is the
first. This is the same treatment chapter 4's scanner gave `start`/`current`,
so the two phases read alike. It is wordier than `current++` at each call site,
but it buys two things that matter directly for testing: any rule can be run
against any token stream in isolation, and a failed parse cannot leave a shared
object half-advanced.

**`match` returns a state or nil, not a boolean.** The book's `match()` returns
`true` *and* advances the cursor as a side effect, so "did it match" and "where
are we now" are separated. Returning the advanced state (or nil) keeps them
together at the call site:

```clojure
(if-let [state' (match state :bang :minus)] ...)
```

**The four binary levels are one higher-order function.** `equality`,
`comparison`, `term` and `factor` are, in the book, four textually identical
methods differing only in which token types they match and which method they
call for operands. The book's own margin note suggests factoring them - "If you
wanted to do some clever Java 8, you could create a helper method for parsing a
left-associative series of binary operators" - and `parse-left-assoc-binary` is
that helper. The payoff is that the precedence table becomes **data**:

```clojure
(def binary-levels
  [{:rule :equality   :operators [:bang-equal :equal-equal]}
   {:rule :comparison :operators [:greater :greater-equal :less :less-equal]}
   {:rule :term       :operators [:minus :plus]}
   {:rule :factor     :operators [:slash :star]}])
```

The book encodes the same table in the *call graph* of its methods (`equality`
happens to call `comparison`, which happens to call `term`). Having it as data
means the precedence order can be read off in one place, asserted in a test,
and reused - challenge 6.3's error-production table is derived from it, so the
two cannot drift apart.

**Panic mode still uses an exception.** Section 6.3.3's argument is that in
recursive descent the parser's state *is* the call stack, so unwinding to a
synchronization point means unwinding the stack. That reasoning applies
verbatim to a stack of Clojure calls, so `ParseError` becomes an `ex-info`
tagged `::parse-error`, thrown by `error!` and caught in `parse`. The one
change: the book's `error()` *returns* the exception so each call site can
choose whether to throw. `error!` always throws, and a site that wants to
report and keep going calls `lox.errors/error` directly - the same flexibility,
but visible at the call site rather than in the control flow.

**`synchronize` is written now and tested now.** Nothing calls it in anger
until chapter 8, since chapter 6 has no statements to synchronize between. It
is written here anyway because it belongs with the rest of the error recovery,
and it is unit-tested directly - including the property that makes it safe to
call in a loop: it always consumes at least one token, so a caller can never
spin forever on a bad one.

### Chapter 7 - Evaluating Expressions

**The interpreter is a visitor map, exactly like the AST printer.** The book
writes `class Interpreter implements Expr.Visitor<Object>`, and notes that the
AST printer "is almost exactly what a real interpreter does, except instead of
concatenating strings, it computes values". Chapter 5 here had already reduced
`Expr.Visitor<R>` to a map from node type to function, so that observation
becomes literal: `lox.interpreter/interpreter` and
`lox.ast-printer/printer` are the same `defvisitor` form with different return
values. `defvisitor` still refuses to compile a visitor that misses a node
type, which is the guarantee the Java interface was there to provide.

**There is no interpreter object yet.** Chapter 7's evaluation is a pure
function of the tree, so `evaluate` takes a node and returns a value. The book
makes `Lox.interpreter` a static field anyway, and says why: global variables
have to survive between REPL lines. That is chapter 8's `Environment`, and
`evaluate` will take it as a parameter when it exists.

**The value representation is the one place Clojure buys nothing.** Both
languages run on the JVM, so "a Lox value" is the same set of objects in each:
`nil`, `Boolean`, `Double`, `String`. The predicates are named
(`lox-number?` and friends) but they are just the book's `instanceof` checks.
Note that `lox-number?` is deliberately *not* Clojure's `number?` — Lox has
exactly one numeric type, and a `Long` reaching the evaluator is a bug in
whoever built the node.

**Two places where the obvious Clojure translation is wrong.** Both were found
by tests, both are now pinned by tests, and both come from the same root cause:
Clojure's numeric tower is not Java's `double`.

* `(/ 1.0 0.0)` is `Infinity`, but `(/ boxed-one boxed-zero)` **throws**
  `ArithmeticException`. Clojure's `/` on boxed `Double` objects routes through
  `clojure.lang.Numbers`, which rejects a zero divisor; on primitive doubles it
  compiles to the JVM's division and yields the IEEE infinity the book expects.
  The interpreter's values are always boxed, so the book's `(double)left /
  (double)right` casts have to be written out rather than dropped.
* Clojure's `compare` on two `Double`s uses `Double.compareTo`, which totally
  orders `NaN` above everything — so a `compare`-based `>` would make
  `NaN > 1` **true**. The book compares primitive doubles, where every NaN
  comparison is false, so `eval-comparison` uses `>` / `<` directly.

The irony is that `isEqual` needs the *opposite* choice: there the book calls
`.equals`, so `NaN == NaN` is true and `-0.0 == 0.0` is false — the reverse of
both IEEE and Clojure's `=`. The book flags this ("Lox uses the latter, so
doesn't follow IEEE") and this implementation matches it rather than
"improving" it, because two Lox implementations disagreeing about
`(0/0) == (0/0)` is the exact failure the chapter warns about.

**Unreachable code throws instead of returning nil.** The book's `switch`
statements fall out of the bottom with `// Unreachable.` and `return null`. An
unreachable branch that silently produces a value is a trap: if the parser ever
hands the interpreter an operator it has no rule for, the result is a stray nil
flowing onward instead of a diagnosis. Both `eval-binary` and `eval-unary`
raise a Lox runtime error there instead, and it is tested.

## Deviations from the book

I added one deliberate addition, from the chapter's own challenge list:

* **Challenge 4.4 — block comments.** `/* ... */` comments are supported, and
  they **nest**. Newlines inside them still increment the line counter, and an
  unterminated block comment is a scan error. This is a strict superset of the
  book's lexical grammar: no program valid under the book's scanner scans
  differently here.

Chapter 5's challenges:

* **Challenge 5.1 - desugaring the grammar metasyntax.** Answered in
  `docs/chapter05-challenges.md`; no code change.
* **Challenge 5.2 - the Visitor pattern's dual.** Discussed in the same file.
* **Challenge 5.3 - an RPN printer.** Implemented as `lox.ast-printer/print-rpn`
  and unit-tested, including a small stack machine that *runs* the generated
  RPN and checks that it agrees with evaluating the tree. One deliberate
  decision: unary negation is written `~` rather than `-`, because `1 2 -`
  would otherwise be ambiguous between subtraction and negation, and an RPN
  rendering that cannot be evaluated is not much of a rendering. Unary `!`
  keeps its lexeme, since Lox has no binary `!`.

Chapter 6's challenges (full write-up in `docs/chapter06-challenges.md`):

* **Challenge 6.1 - the comma operator** and **challenge 6.2 - the ternary
  `?:`** are both implemented and tested, but are **off by default**, behind
  `lox.parser/*allow-comma?*` and `lox.parser/*allow-conditional?*`.

  This is the one place where a challenge is not simply left on. Chapter 4's
  block comments could be, because they are a strict superset - no valid
  program changes meaning. A comma expression at the lowest precedence level is
  not: `,` is a separator elsewhere in the language the rest of the book builds,
  so `f(a, b)` in chapter 10 would parse as a call with the *single* argument
  `(a, b)`. C hits the same conflict and resolves it with a special case in the
  grammar for function arguments; threading that special case through several
  later chapters, for a feature the book's Lox does not have, is not worth it.
  So the default grammar is exactly the book's, and the challenge code is real,
  reachable and tested under `binding`.

  The ternary needed two supporting changes: `?` and `:` became scannable
  tokens (`:question`, `:colon`), and `:conditional` was added to `define-ast`.
  That addition was a fair test of chapter 5's claim that a new production
  costs one line - it did, plus the two visitors in `lox.ast_printer`, which
  `defvisitor` refused to compile until they handled the new node. That
  compile-time nag is exactly the guarantee the book gets from
  `implements Expr.Visitor<R>`, and it worked as advertised.

* **Challenge 6.3 - error productions for a binary operator with no left
  operand** is **on** by default, because it can only fire on input that is
  already a syntax error. `* 3` now reports "Binary operator '*' requires a
  left-hand operand." instead of the generic "Expect expression.", and - the
  important half of the challenge - consumes the operator's right operand *at
  the operator's own precedence level*, so `* 1 + 2` produces one error rather
  than a cascade. `-` is excluded from the check, since a leading `-` is a
  legal unary operator rather than a mistake.

Chapter 7's challenges (full write-up in `docs/chapter07-challenges.md`):

* **Challenge 7.1 - comparisons on other types.** Implemented for *two
  strings*, ordered by `String.compareTo`, behind
  `lox.interpreter/*compare-strings?*`. Mixed pairs like the challenge's
  `3 < "pancake"` stay a runtime error: any ordering for them would be
  invented, and the languages that guessed have since changed their minds
  (Python 2 compared mixed types by type *name* and Python 3 made it a
  `TypeError`; JavaScript coerces, which makes its `<` not even a total order).
  The error message widens with the switch so it never misstates the rule.
* **Challenge 7.2 - string-coercing `+`.** Implemented behind
  `lox.interpreter/*string-coercing-plus?*`, so `"scone" + 4` is `"scone4"`.
  It is checked *after* the book's two cases, so only pairs that were already
  runtime errors can change meaning, and it converts with `stringify` rather
  than the host's `str` — otherwise a Lox number would concatenate as
  `"scone4.0"`.
* **Challenge 7.3 - division by zero.** Implemented behind
  `lox.interpreter/*error-on-division-by-zero?*`. The written half of the
  challenge is answered in the doc, with a comparison table across six
  languages; the short version is that a silent `NaN` is exactly the failure
  mode section 7.3 argues against, and Lox cannot even test for `NaN` (it has
  no `isnan`, and `NaN == NaN` is true here).

All three are **off** by default, for the reason the book repeats throughout
this chapter: each changes the meaning of programs that are *already valid*, so
switching one on by default would make this interpreter quietly disagree with
every other Lox. Each is exercised by the tests in both positions.

One further deviation, not from a challenge: **`parse` reports leftover
tokens.** The book's chapter 6 `parse()` parses one expression and returns,
silently ignoring anything after it - so `1, 2` would quietly parse as `1` with
no indication that half the input went unread. The hole closes on its own in
chapter 8, where `parse` loops until `:eof`; until then it is closed explicitly
with "Expect end of expression."

And one standing addition for the sake of the earlier submissions: the book
deletes each chapter's temporary output when the next chapter replaces it,
whereas here every stage stays reachable by flag. `run` evaluates, as section
7.4 says, while `--ast` still prints chapter 6's syntax tree and `--tokens`
still dumps chapter 4's tokens. Each chapter is a graded submission of its own,
and its example script should stay runnable.

The AST printer's numbers deserve a note: it prints what is in the tree, so a
`NUMBER` token scanned from the source `123` shows as `123.0`. Trimming that
trailing `.0` is `lox.interpreter/stringify`'s job as of chapter 7, not the
printer's - the printer exists to show the tree exactly as it is. The two
coexist deliberately, and a test asserts the difference: `1` parses to `1.0`
and evaluates to `1`.

Everything else matches the book, including the intentional edge cases:
`.5` scans as `DOT` then `NUMBER`, `5.` scans as `NUMBER` then `DOT`, `-123` is
two tokens, Lox strings are multi-line, and Lox strings have no escape
sequences (so `"a\nb"` is six characters, backslash included).

---

## Testing

`./scripts/test.sh` runs the whole suite: **174 test cases / 1,167
assertions** across chapters 4 to 7.

| Chapter | Test cases | Assertions |
| ------- | ---------- | ---------- |
| 4 — Scanning | 23 | 137 |
| 5 — Representing Code | 32 | 206 |
| 6 — Parsing Expressions | 83 | 335 |
| 7 — Evaluating Expressions | 36 | 489 |
| **total** | **174** | **1,167** |

A single chapter can be run on its own:

```bash
./scripts/test.sh lox.chapter-07-evaluating-test
```

### Chapter 4 - 23 test cases / 137 assertions

Covering:

* the EOF token, empty input, and whitespace-only input
* every single-character token, and every one-or-two-character operator
* maximal munch (`===` → `==` `=`, `!!=` → `!` `!=`, `orchid` → one identifier)
* line comments, division vs. comment disambiguation
* block comments: simple, multi-line, nested, and unterminated (challenge 4.4)
* string literals: normal, empty, multi-line, quote-stripping, no escapes,
  and unterminated (reported on the correct line)
* number literals: integer and decimal, all doubles, plus the `.5` / `5.` /
  `123.abs()` / `-123` edge cases
* all 16 reserved words, case sensitivity, and identifiers that merely start
  with a keyword
* lexical errors: correct message, correct line, and the fact that scanning
  *continues* after one so multiple errors surface in a single run
* line-number tracking across newlines, strings and block comments
* a whole-program smoke test over a realistic Lox source file

### Chapter 5 - 32 test cases / 206 assertions

Covering:

* **the grammar as data** - that exactly the four productions of section 5.1.3
  exist, and that each one's fields match `GenerateAst`'s type descriptions
  field for field, in order
* **constructors** - the map each one builds, its argument order (source
  order: `(binary left operator right)`), and the `:node` tag
* **predicates** - `binary?`/`grouping?`/`literal?`/`unary?` each accepting
  their own node type and no other; `expr?` rejecting nil, scalars, keywords,
  vectors, empty maps and unknown tags
* **tokens vs. nodes** - that no token is ever an `expr?` and no node is ever
  a `token?`, which is the whole reason the tag key is `:node`
* **field-kind checking** - every `:expr` field rejecting non-nodes (including
  tokens), every `:token` field rejecting non-tokens (including nodes),
  `:object` accepting anything including nil, and the error message naming the
  node, the field, the expectation and the offending value
* **nodes as values** - structural equality regardless of how a tree was
  built, inequality on differing operands, operators, shapes and line numbers,
  usability as map and set keys, and immutability under `assoc`
* **generic access** - `node-type`, `fields-of`, and `children` returning
  sub-expressions in source order (and excluding operator tokens), plus a
  node counter and a depth function written with `children` alone
* **the visitor** - dispatch to the right handler for each node type, the
  whole node being passed through, rejection of non-nodes, and a clear throw
  (listing what *is* handled) when a visit method is missing
* **`defvisitor`** - a missing node type failing at macroexpansion time with
  the missing types in its `ex-data`, an unknown node type rejected the same
  way, `^:partial` opting out, the expansion's shape, and an `eval`'d visitor
  actually working
* **the AST printer** - the book's own worked example
  `(* (- 123) (group 45.67))`; nil, true, false, strings, the empty string and
  numbers; unary, grouping and nested grouping; all ten binary operators; two
  trees that would print identically in Lox syntax but differ here (the point
  of the printer); the deep `1 - (2 * 3) < 4 == false` tree; a 200-level tree;
  the `parenthesize` helper; and rejection of non-nodes
* **the RPN printer** - the challenge's own example `1 2 + 4 3 - *`, grouping
  vanishing, unary `~` staying distinct from binary `-`, and a stack machine
  that evaluates the generated RPN and compares it against evaluating the tree
  for five different expressions
* **chapters 4 and 5 together** - trees built from real scanned tokens
  (numbers, strings, `true`/`false`/`nil`), printed both ways
* **the chapter demo** - that `lox.ast-printer/-main` prints the output the
  book says it should
* **error state** - that building and printing trees reports no errors

### Chapter 6 - 83 test cases / 335 assertions

Covering:

* **the parser primitives** - `peek`/`previous`/`check`/`advance`/`match`/
  `consume`, including the `:eof` boundary: that `check` never matches at
  `:eof` (even when asked for `:eof` itself), that `advance` past the end is a
  no-op so no rule can run off the token vector, and that `match` returns the
  advanced state on success and nil - consuming nothing - on failure
* **every production of section 6.1** - all three literal forms plus the three
  keyword literals (asserting the *values* `true`/`false`/nil, not the keyword
  names), grouping and nested grouping, both unary operators, and all ten
  binary operators driven from the `binary-levels` table so a missing one
  cannot pass unnoticed
* **associativity** - the book's own `5 - 3 - 1` example; every binary level
  leaning left; the book's `a == b == c == d == e` diagram reproduced; mixed
  operators at one level (`1 + 2 - 3`); and unary nesting right-associatively
  (`!!true`, `---1`)
* **precedence** - the book's `6 / 3 - 1` ambiguity example now having exactly
  one parse; each adjacent pair of levels tested in both orders (`1 + 2 * 3`
  *and* `1 * 2 + 3`); one expression touching every level at once; and
  grouping overriding all of it
* **the precedence table as data** - that `precedence-order` runs loosest to
  tightest and that each level lists exactly the book's operators
* **syntax errors** - both of the chapter's messages ("Expect ')' after
  expression.", "Expect expression."), reported against the right token and the
  right *line*, with `at end` used at EOF where there is no lexeme to show
* **the parser's hard requirements** - a table of 30 malformed inputs
  (`"("`, `"))))"`, `"* * *"`, `"1.2.3"`, `"\"unterminated"`, ...) asserting
  that none throws or hangs; that a `ParseError` never escapes `parse`; and
  that a non-parse exception *is* allowed to propagate rather than being
  swallowed by the catch
* **synchronization** - stopping after a semicolon, stopping before each of the
  eight statement keywords, running to `:eof` when nothing matches, and the
  termination property: it always consumes at least one token, even when
  already sitting on a keyword
* **the challenges** - comma parsing, left-associativity and lowest precedence;
  the ternary's right-associativity, its precedence relative to `==` and `,`,
  its full-expression middle operand, and its three distinct error cases; both
  defaulting to *off*; the error productions firing for all nine applicable
  operators, `-` correctly exempt, exactly one error for `* 1 + 2`, and the
  operand being parsed at the right precedence level
* **structural invariants** - that every node the parser builds passes chapter
  5's own field checking, walked over the whole tree; that operator tokens
  arrive in the tree unmodified (so chapter 7 can report their lines); and that
  parsing is pure - same tokens in, same tree out, token vector untouched
* **depth and length** - 100 nested groupings and a 200-operator chain,
  neither overflowing nor mis-associating
* **the wiring of section 6.4** - that `run` prints the tree, prints *nothing*
  when the parser errored, prints nothing when the *scanner* errored, and that
  `--tokens` still produces chapter 4's dump
* **documentation drift** - that the grammar recorded in `lox.parser/grammar`
  quotes exactly the operators the `binary-levels` table implements, so the two
  cannot be changed apart
* **chapters 5 and 6 together** - that the tree chapter 5 had to hand-build is
  now derived from the source text `-123 * (45.67)`

### Chapter 7 - 36 test cases / 489 assertions

Covering:

* **the value representation of section 7.1** - that each of the four Lox
  types is recognised, that nothing else counts as a Lox value, and
  specifically that a Lox number is a `Double` and a Clojure `Long`, `Float`,
  `BigDecimal` or ratio is *not* one; plus that the scanner already produced
  the value the evaluator hands back, so a literal needs no conversion
* **every node type** - literals (all six forms), grouping and 4-deep nesting,
  both unary operators, all ten binary operators, and the ternary; driven in
  part off `ast/expr-node-types`, so a node type added later cannot slip
  through unevaluated
* **arithmetic** - precedence and associativity through the evaluator rather
  than the printer (`1 + 2 * 3` is 7, `(1 + 2) * 3` is 9, `10 - 5 - 2` is 3),
  double division (`1 / 2` is `0.5`, not 0), and IEEE rounding
  (`0.1 + 0.2` is `0.30000000000000004`)
* **truthiness (7.2.4)** - that only `false` and `nil` are falsey, asserted
  against the four languages the book contrasts: `0` is truthy here but falsey
  in C and Python, `""` is truthy here but falsey in JS and Python, and `"0"`
  is truthy here but falsey in PHP
* **equality (7.2.5)** - like types, every mixed pair being unequal *without*
  erroring (which is what distinguishes `==` from `<`), nil handling with no
  NullPointerException, and reflexivity and symmetry checked over a 9-value
  cross product
* **the `equals`-versus-`==` corner the book flags** - that `NaN == NaN` is
  **true** and `-0.0 == 0.0` is **false**, because `isEqual` uses `.equals`;
  and that both are the opposite of Clojure's own `=`, so the implementation
  provably cannot just delegate to it
* **IEEE ordering** - that every comparison involving NaN is false, including
  `NaN <= NaN`, and that `-0.0` compares *equal* to `0.0`. These are the
  mirror image of the equality cases above, and they are what catch the
  tempting `compare`-based implementation: `Double.compareTo` orders NaN above
  everything, so it would make `NaN > 1` true
* **evaluation order** - a tracing harness that records each node as it
  finishes, proving the traversal is post-order and left-to-right at every
  level (`1 + 2 * 3` traces `1, 2, 3, binary, binary`); plus the book's two
  semantic commitments checked independently through runtime errors as
  observable effects: the *left* operand's error is the one reported, and
  *both* operands are evaluated before either is type-checked
* **`stringify` (7.4)** - `nil`; the `.0` trimming for integer-valued doubles
  (`1.0` → `1`, and `-0.0` → `-0`); fractions preserved; `Infinity`,
  `-Infinity` and `NaN`; and that it deliberately differs from chapter 5's
  `literal->string`, which keeps the `.0` because it shows the tree
* **runtime errors (7.3)** - all three of the chapter's messages across 24
  operand combinations; the reported line taken from the *operator* token
  (checked with the operator on lines 1, 2 and 3); the error tagged `:runtime`
  rather than `:static`, and a parse error tagged the other way round and never
  reaching the interpreter; the book's `2 * (3 / -"muffin")` unwinding all
  three operators to produce exactly *one* error; the same through 40 levels of
  nesting; and that no host `ClassCastException` or `NullPointerException` ever
  escapes
* **the wiring of section 7.4** - that `interpret` prints the stringified value
  and prints *nothing* when evaluation fails; that `lox.core/run` now evaluates
  while chapters 4 and 6 stay reachable by flag; and the exit codes 0, 65 and
  70 driven through the real `run-file` over a temp file, including that a
  static error wins over a runtime one because nothing ran
* **the REPL surviving errors** - a driven session asserting that a runtime
  error, and then a syntax error, each leave the session alive and the next
  line working, with no error state leaking between lines
* **unreachable operators** - that an operator the evaluator has no rule for is
  *reported*, not silently turned into nil as the book's `// Unreachable.`
  `switch` would
* **depth** - 101 nested `!`, 100 nested groupings and a 100-operator chain,
  evaluating correctly without overflowing
* **the challenges** - string ordering with mixed types still refused and the
  error message tracking the widened rule; string-coercing `+` in both operand
  orders, across all four value types, converting via `stringify` so no stray
  `.0` appears, leaving the book's two cases untouched, and its
  non-associativity (`"a" + 1 + 2` vs `"a" + (1 + 2)`); divide-by-zero errors
  including `-0.0`, reported at the `/` token's line; and each challenge
  asserted *inert* while its switch is off
* **chapter 6's challenge nodes, now evaluable** - the ternary choosing a
  branch by truthiness, chaining right-associatively, and provably **not**
  evaluating the untaken branch (`true ? "safe" : -nil` succeeds, and the trace
  shows only two operands); the comma operator discarding its left operand
  while still evaluating it

Run a single namespace with:

```bash
./scripts/test.sh lox.chapter-04-scanning-test
./scripts/test.sh lox.chapter-05-ast-test
./scripts/test.sh lox.chapter-06-parsing-test
```
## Note on AI Usage:

AI has been used to re-organize code I have written by hand and help me with documentation and comment insertion. Unit tests, interpreter logic writing, and decisions were solely made by me.