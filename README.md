# Clojure Lox

A tree-walking interpreter for the **Lox** programming language, written in
**Clojure**.

[Crafting Interpreters]: https://craftinginterpreters.com/

**Current state: Chapter 5 — Representing Code.**

| Chapter | Topic | Status |
| ------- | ----- | ------ |
| 4  | Scanning                | ✅ complete |
| 5  | Representing Code       | ✅ complete |
| 6  | Parsing Expressions     | not started |
| 7  | Evaluating Expressions  | not started |
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
./scripts/lox.sh examples/ch04_scanning.lox
./scripts/lox.sh                         # interactive REPL (Ctrl-D to exit)
./scripts/astdemo.sh                     # chapter 5: print a syntax tree
```

On Windows PowerShell, use the `.ps1` equivalents:

```bash
powershell -File scripts/test.ps1
powershell -File scripts/lox.ps1 examples/ch04_scanning.lox
powershell -File scripts/astdemo.ps1
```

The first run downloads `clojure`, `spec.alpha` and `core.specs.alpha` from
Maven Central into `lib/` (about 4.7 MB) and caches them there. `lib/` is
git-ignored.

### Option B — Leiningen

```bash
lein test
lein run examples/ch04_scanning.lox
lein run                                 # REPL
lein run -m lox.ast-printer              # chapter 5 demo
lein uberjar
```

### Option C — Clojure CLI (tools.deps)

```bash
clojure -M:test
clojure -M -m lox.core examples/ch04_scanning.lox
clojure -M -m lox.core                   # REPL
clojure -M -m lox.ast-printer            # chapter 5 demo
```

### Exit codes

Following the book's use of the UNIX `sysexits.h` conventions:

| Code | Meaning |
| ---- | ------- |
| 0    | success |
| 64   | bad command line usage |
| 65   | the source had a static (scan/parse) error |

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

test/lox/
  test_util.clj                 shared fixtures and helpers
  test_runner.clj               dependency-free runner (`-m lox.test-runner`)
  chapter_04_scanning_test.clj  Chapter 4 tests
  chapter_05_ast_test.clj       Chapter 5 tests

examples/
  ch04_scanning.lox             sample input exercising the lexical grammar

scripts/
  bootstrap.{sh,ps1}  fetch the Clojure jars into lib/
  test.{sh,ps1}       run the test suite
  lox.{sh,ps1}        run a script or the REPL
  astdemo.{sh,ps1}    run the chapter 5 syntax-tree demo

docs/
  chapter05-challenges.md   written answers to challenges 5.1 and 5.2
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

The AST printer's numbers deserve a note: it prints what is in the tree, so a
`NUMBER` token scanned from the source `123` shows as `123.0`. Trimming that
trailing `.0` is chapter 7's `stringify`, not the printer's job - the printer
exists to show the tree exactly as it is.

Everything else matches the book, including the intentional edge cases:
`.5` scans as `DOT` then `NUMBER`, `5.` scans as `NUMBER` then `DOT`, `-123` is
two tokens, Lox strings are multi-line, and Lox strings have no escape
sequences (so `"a\nb"` is six characters, backslash included).

---

## Testing

`./scripts/test.sh` runs the whole suite: **55 test cases / 339 assertions**
across chapters 4 and 5.

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

### Chapter 5 - 32 test cases / 202 assertions

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

Run a single namespace with:

```bash
./scripts/test.sh lox.chapter-04-scanning-test
./scripts/test.sh lox.chapter-05-ast-test
```
## Note on AI Usage:

AI has been used to re-organize code I have written by hand and help me with documentation and comment insertion. Unit tests, interpreter logic writing, and decisions were solely made by me