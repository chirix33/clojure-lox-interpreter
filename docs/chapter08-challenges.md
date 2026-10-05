# Chapter 8 — Statements and State: challenges

All three challenges are implemented and tested in
`test/lox/chapter_08_statements_test.clj` (section 7, "Challenges").

| Challenge | Where | Switch | Default |
| --------- | ----- | ------ | ------- |
| 8.1 REPL takes statements *or* expressions | `lox.parser/parse-repl`, `lox.core/run-repl-line` | `lox.core/*repl-echoes-expressions?*` | **on** |
| 8.2 reading an uninitialized variable is an error | `lox.environment` | `lox.environment/*error-on-uninitialized?*` | off |
| 8.3 `var a = a + 2;` inside a block | — (a written answer, plus tests pinning the behaviour) | — | — |

Note that 8.1 is the first challenge in this project that is **on** by default.
Chapters 6 and 7 kept theirs off on a principle — "to ensure that jlox and clox
work the same" — because each of those changed the meaning of programs that
were already valid. 8.1 changes nothing about the language: `lox.core/run` is
untouched, a script behaves identically, and the only difference is whether the
*prompt* answers `1 + 2` with `3` or with a syntax error. The book's own
framing ("That's a drag") treats the chapter-8 regression as an accident of
implementation order rather than a decision. 8.2 does change what valid
programs mean, so it follows the older convention and stays off.

---

## Challenge 8.1 — a REPL that accepts both statements and expressions

> The REPL no longer supports entering a single expression and automatically
> printing its result value. That's a drag. Add support to the REPL to let
> users type in both statements and expressions. If they enter a statement,
> execute it. If they enter an expression, evaluate it and display the result
> value.

### How it is done

The interesting part is not the printing, it is the **classification**, and the
first thing to notice is that it cannot be done by looking at the leading
token. `a` begins an expression (`a + 1`) and equally begins a statement
(`a = 1;`). `(` begins both. There is no finite lookahead that settles it
either: the deciding token may be arbitrarily far along, and may be the very
last one.

What *does* settle it is a question chapter 6's entry point already answers —
**does the entire line parse as one expression?** So `parse-repl` tries exactly
that and falls back:

```clojure
(defn parse-repl [source]
  (let [tokens (scanner/scan-tokens source)
        saved  (err/error-state)
        expr   (binding [err/*print-errors?* false]
                 (parse-one-expression tokens))]
    (if (and (some? expr) (not @err/had-error))
      [:expression expr]
      (do (err/restore-error-state! saved)
          [:statements (parse tokens)]))))
```

This is the second reason chapter 6's `parse` was kept, renamed, rather than
deleted the way the book deletes it. (The first is that `clox
--ast-expression` still demonstrates chapter 6 from the command line.) Note
that `parse-one-expression` requires the expression to be *all* of the input —
chapter 6 added an explicit "Expect end of expression." check for that — which
is what makes it a usable classifier rather than a parser that happily reads a
prefix.

`lox.core/run-repl-line` then does the two different things the challenge asks
for:

| kind | handled by | prints the value? |
| ---- | ---------- | ----------------- |
| `:expression` | `lox.interpreter/interpret-expression` (chapter 7's `interpret`) | yes |
| `:statements` | `lox.interpreter/interpret` (chapter 8's) | only what the program prints |

So chapter 7's `interpret` is not dead code either: it is precisely "evaluate
one expression and display the result", which from chapter 8 onwards no
statement does.

### Backtracking has to leave *two* kinds of trace behind

Speculative parsing only works if a failed attempt is invisible, and
"invisible" turned out to mean two separate things:

1. **The recorded error state must be rolled back.** `lox.errors` already
   gathered every flag and every reported error into named atoms — a decision
   made in chapter 4 so that error reporting could be unit-tested instead of
   scraped off stderr — so `error-state` / `restore-error-state!` are three
   lines each.

2. **Nothing may be printed during the attempt.** This is the part that is
   easy to miss, and it was in fact missed here first: a rolled-back error has
   still been *written to stderr*, and the user has still read it. Typing
   `var a = 1;` printed

   ```
   > [line 1] Error at 'var': Expect expression.
   ```

   above a perfectly successful declaration. The state was clean; the session
   was not. The fix is to bind `lox.errors/*print-errors?*` to false around the
   attempt.

   A unit test asserting on `error-messages` passes in both versions, which is
   the whole lesson: the test was checking the claim that was easy to check
   rather than the claim that mattered. The suite now also captures stderr
   (`lox.test-util/capture-err`) and asserts that a statement line prints
   nothing at all.

The snapshot is deliberately taken **after** scanning, and scanning happens
outside the suppression, so a genuine scan error is reported once, printed
once, and survives the rollback:

```
> print "unterminated
[line 1] Error: Unterminated string.
[line 1] Error at end: Expect expression.
```

### What it looks like

```
> var a = 1;              // a statement: executed, nothing displayed
> a = a + 2               // an expression: evaluated and displayed
3
> print a;                // a statement that prints
3
> 1 + 2                   // an expression
3
> 1 + 2;                  // an expression *statement* - discarded
> b
Undefined variable 'b'.
[line 1]
> print "done";
done
```

The fifth and sixth lines are the ones worth staring at. `1 + 2` and `1 + 2;`
differ by one character and do genuinely different things, and that is correct
rather than a wart: an expression statement's defined behaviour is to evaluate
its expression and throw the value away. The semicolon is not noise.

### What was *not* done, and why

A tempting alternative is to leave classification alone and have the
interpreter print the value of every expression statement when a "REPL mode"
flag is set. It is less code. It is also wrong: it would make `print a;` print
twice (once for the statement, once for... no, `print` is not an expression
statement — but `f();` in chapter 10 would print `nil` on every call), and it
would mean the REPL and a script disagreed about what an expression statement
*means*. Classifying the input keeps one definition of every construct and
puts the convenience entirely in the prompt.

---

## Challenge 8.2 — make uninitialized variables an error

> Maybe you want Lox to be a little more explicit about variable
> initialization. Instead of implicitly initializing variables to nil, make it
> a runtime error to access a variable that has not been initialized or
> assigned to, as in:
>
> ```
> // No initializers.
> var a;
> var b;
>
> a = "assigned";
> print a; // OK, was assigned first.
>
> print b; // Error!
> ```

### How it is done

With `lox.environment/*error-on-uninitialized?*` on, a `var` with no
initializer binds a **sentinel** instead of nil, and `get-var` raises on
reading it:

```clojure
(def uninitialized ::uninitialized)
```

Three things make this work, and all three are load-bearing.

**The sentinel must be unforgeable.** If a Lox program could produce the
sentinel as a value, it could store it in a variable and then get an
"uninitialized" error on a perfectly legitimate read. A namespaced keyword is
unforgeable here: `lox.interpreter/lox-value?` is false for it, and no
evaluation path in the interpreter can produce one. (`::uninitialized` is also
`:lox.environment/uninitialized` in full, so it cannot collide with a keyword
from anywhere else either.) The test suite asserts both halves.

**"Declared but uninitialized" has to be distinguishable from "not declared".**
They are different mistakes and they get different messages:

```
var a; print a;    Variable 'a' is not initialized.
print a;           Undefined variable 'a'.
```

Getting this wrong — reporting "Undefined variable" for a variable that is
plainly right there on the previous line — would be a worse error message than
the nil the challenge is trying to improve on.

**A missing initializer has to be distinguishable from an explicit nil.** This
is why `lox.ast`'s `Stmt.Var` gives `initializer` the field kind `:expr-or-nil`
and the parser stores a literal Clojure nil for `var a;` rather than an
`(ast/literal nil)` node. The two parse to visibly different trees:

```
var a;        (var a)
var a = nil;  (var a nil)
```

and under this challenge they behave differently: the first errors on read, the
second is initialized, to nil, deliberately. Had the parser normalised `var a;`
into `var a = nil;` — which costs nothing in the book's semantics and would
have looked like a simplification — this challenge would have been
unimplementable without re-deriving the distinction from somewhere else.

### Behaviour

```
var a; print a;                  Variable 'a' is not initialized.
var a = nil; print a;            nil
var a; a = 1; print a;           1
var a = 1; var a; print a;       Variable 'a' is not initialized.   // redeclaration un-initializes
var a; { print a; }              Variable 'a' is not initialized.   // through the scope chain
var a; { a = 1; } print a;       1
```

### Should it be the default? No.

Section 8.3.1 considers exactly this option and declines it: "It's not a bad
idea, but most dynamically typed languages don't do that. Instead, we'll keep
it simple and say that Lox sets a variable to nil if it isn't explicitly
initialized." Since clox implements that rule, so does the default here —
otherwise a program would mean different things on the two implementations,
which is the thing chapter 7 warns about at length.

For what it is worth, the challenge's rule is the better language design. It
catches a real class of typo, it costs nothing at runtime beyond one identity
check per variable read, and the languages that have adopted something like it
since — Swift's definite initialization, Rust's — have not regretted it. But
"better" and "what Lox is" are different questions, and a book implementation
has to answer the second.

---

## Challenge 8.3 — what does this program do?

> What does the following program do?
>
> ```
> var a = 1;
> {
>   var a = a + 2;
>   print a;
> }
> ```
>
> What did you expect it to do? Is it what you think it should do? What does
> analogous code in other languages you are familiar with do? What do you think
> users will expect this to do?

### What it does

**It prints 3.**

The `:var` clause of the statement visitor evaluates the initializer *before*
calling `define!`. So at the moment `a + 2` is evaluated, the inner `a` does
not exist yet; look-up walks out of the block's environment and finds the
global `a`, which is 1. The result, 3, is then bound to a *new* `a` in the
block's scope, and the global one is left at 1:

```
var a = 1;
{ var a = a + 2; print a; }   // 3
print a;                      // 1
```

This is what jlox does too, for the same reason, so the two agree.

### Is it what it should do? No.

It is defensible — it is just "right-hand side first, then bind", which is the
rule for `var a = b;` and for assignment and for argument passing. But applied
here it produces a construct where one name means two different variables in
one line of code, and nothing in the source marks the transition. Worse, the
behaviour is *silent*: there is no error, no warning, and the program that
results is plausible.

Three reasons to think users will not expect it:

* **Some of them will expect recursion.** Readers coming from Scheme's
  `letrec`, or from any language where a local declaration is in scope within
  its own initializer, will read `var a = a + 2` as referring to the new `a`,
  and expect either a sensible answer or an error — not a silent reference to a
  different variable.

* **Some of them will expect the C answer**, which is the same as ours but for
  a very different reason, and is a notorious source of bugs rather than a
  model to follow. (In C, `int a = a + 2;` inside a block reads the *new*,
  uninitialized `a`: the name is in scope from its declarator onwards. That is
  undefined behaviour, and compilers warn about it.)

* **Nobody writes it on purpose.** When it appears in real code it is almost
  always a typo for an assignment (`a = a + 2;`) or a shadowed variable the
  author had forgotten about. A construct whose every real occurrence is a
  mistake should not quietly do something reasonable-looking.

What other languages do:

| language | `var a = 1; { var a = a + 2; }` |
| -------- | ------------------------------- |
| Lox (jlox, and here) | 3 — the initializer sees the outer `a` |
| C / C++ | undefined behaviour — reads the new, uninitialized `a` |
| Java | compile error: "variable a might not have been initialized" |
| JavaScript (`let`) | `ReferenceError` — the temporal dead zone, added in ES6 precisely to make this loud |
| Python | `UnboundLocalError` at runtime |
| Rust | 3 — but `let` shadowing is an explicit, idiomatic feature, and the new binding is a new variable by design |

The pattern is clear: the languages that have revisited this since 1990 all
made it an **error**, and the one that made it legal (Rust) did so by making
shadowing a deliberate, documented feature rather than an accident of scope
rules.

### What the book does about it

Chapter 11, "Resolving and Binding", turns exactly this into a static error —
"Can't read local variable in its own initializer." — by resolving names in a
separate pass before execution and marking a local variable as *declared but
not yet defined* while its initializer is being resolved. That is the right
fix, and it is why this challenge is posed here: the chapter wants you to
notice the problem that motivates the resolver three chapters early.

No behaviour was changed for this challenge, deliberately. The current answer
is recorded in tests instead, including the edge cases, so that when chapter 11
changes it the change is visible as a test diff rather than as a surprise:

```clojure
(is (= ["3"]  (run "var a = 1; { var a = a + 2; print a; }")))
(is (= ["3" "1"] (run "var a = 1; { var a = a + 2; print a; } print a;")))
(is (= "Undefined variable 'a'."
       (error-of "{ var a = a + 2; print a; }")))   ; no outer binding at all
```

Note the third one: with no enclosing `a`, the same program is a runtime error
rather than silently nil — which is some comfort, but only some. The dangerous
case is precisely the one where an outer binding exists.
