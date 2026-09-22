# Clojure Lox

A tree-walking interpreter for the **Lox** programming language, written in
**Clojure**.

[Crafting Interpreters]: https://craftinginterpreters.com/

**Current state: Chapter 4 — Scanning.**

| Chapter | Topic | Status |
| ------- | ----- | ------ |
| 4  | Scanning                | ✅ complete |
| 5  | Representing Code       | not started |
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
```

On Windows PowerShell, use the `.ps1` equivalents:

```bash
powershell -File scripts/test.ps1
powershell -File scripts/lox.ps1 examples/ch04_scanning.lox
```

The first run downloads `clojure`, `spec.alpha` and `core.specs.alpha` from
Maven Central into `lib/` (about 4.7 MB) and caches them there. `lib/` is
git-ignored.

### Option B — Leiningen

```bash
lein test
lein run examples/ch04_scanning.lox
lein run                                 # REPL
lein uberjar
```

### Option C — Clojure CLI (tools.deps)

```bash
clojure -M:test
clojure -M -m lox.core examples/ch04_scanning.lox
clojure -M -m lox.core                   # REPL
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

test/lox/
  test_util.clj                 shared fixtures and helpers
  test_runner.clj               dependency-free runner (`-m lox.test-runner`)
  chapter_04_scanning_test.clj  Chapter 4 tests

examples/
  ch04_scanning.lox             sample input exercising the lexical grammar

scripts/
  bootstrap.{sh,ps1}  fetch the Clojure jars into lib/
  test.{sh,ps1}       run the test suite
  lox.{sh,ps1}        run a script or the REPL
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

## Deviations from the book

One deliberate addition, from the chapter's own challenge list:

* **Challenge 4.4 — block comments.** `/* ... */` comments are supported, and
  they **nest**. Newlines inside them still increment the line counter, and an
  unterminated block comment is a scan error. This is a strict superset of the
  book's lexical grammar: no program valid under the book's scanner scans
  differently here.

Everything else matches the book, including the intentional edge cases:
`.5` scans as `DOT` then `NUMBER`, `5.` scans as `NUMBER` then `DOT`, `-123` is
two tokens, Lox strings are multi-line, and Lox strings have no escape
sequences (so `"a\nb"` is six characters, backslash included).

---

## Testing

`./scripts/test.sh` runs **23 test cases / 137 assertions** for chapter 4,
covering:

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

Run a single namespace with:

```bash
./scripts/test.sh lox.chapter-04-scanning-test
```
## Note on AI Usage:

AI has been used to re-organize code I have written by hand and help me with documentation and comment insertion. Unit tests, interpreter logic writing, and decisions were solely made by me