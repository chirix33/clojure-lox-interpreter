# Chapter 9 — Control Flow: challenges

All three challenges are addressed. 9.1 and 9.2 are written answers — they ask
you to *show* something about a language Lox does not have yet, not to change
the interpreter — and 9.3 is implemented and tested in
`test/lox/chapter_09_control_flow_test.clj` (section 8, "Challenge 9.3").

| Challenge | Where | Switch | Default |
| --------- | ----- | ------ | ------- |
| 9.1 conditional execution from functions and dynamic dispatch | written answer below; forward-referenced by chapters 10 and 12 | — | — |
| 9.2 looping the same way, and the optimization it needs | written answer below | — | — |
| 9.3 `break` statements | `lox.token`, `lox.parser`, `lox.interpreter` | `lox.token/*allow-break?*` | off |

9.3 is **off** by default, and the reason is different from the earlier
switched-off challenges. Challenges 6.1 and 6.2 added *operators*, which was a
superset: a program that did not use `?:` was unaffected. Reserving the word
`break` is not a superset. It takes a name away from every program that used
`break` as an identifier, and `var break = 1;` is a perfectly legal Lox program
in the book's grammar — so with the switch off, that program still runs, and
with it on, it is a syntax error. The switch lives in `lox.token` rather than
beside the parser's other grammar switches because reserving a word is a
*scanning* decision, and it has to be made before the parser ever sees a token.

---

## Challenge 9.1 — conditional execution without `if`

> A few chapters from now, when Lox supports first-class functions and dynamic
> dispatch, we technically won't need branching statements built into the
> language. Show how conditional execution can be implemented in terms of
> those. Name a language that uses this technique for its control flow.

### The answer

The trick is to make the *condition itself* choose which of two pieces of code
runs, by making it a value that knows the answer. Two routes, both of which
Lox will be able to express by chapter 12.

**With first-class functions (Church booleans).** Represent `true` and `false`
not as data to be tested but as two-argument functions that select an argument:

```lox
fun loxTrue(then, otherwise)  { return then(); }
fun loxFalse(then, otherwise) { return otherwise(); }
```

Then `if (c) a(); else b();` becomes `c(a, b)` — the branching has moved out of
the language and into the data. Note that the branches have to be wrapped in
functions (`then()`, not `then`) rather than passed as computed values. That is
not incidental: it is the only way to delay them, and delaying them is the
entire point. Passing `a()` and `b()` already-evaluated would evaluate *both*,
which is exactly the user-visible difference that makes `if` control flow
rather than a function in the first place.

**With dynamic dispatch (the Smalltalk way).** Give the two boolean values a
method each, and let method lookup do the selecting:

```lox
class True  { ifTrue(block) { return block(); } }
class False { ifTrue(block) { return nil; } }
```

`c.ifTrue(body)` runs the body only when `c` is a `True`, because `False` has
a different method body under the same name. No conditional appears anywhere —
the single-dispatch mechanism *is* the conditional.

### A language that does this

**Smalltalk.** `ifTrue:ifFalse:` is an ordinary message sent to a `Boolean`,
implemented once on `True` and once on `False`, with the branches passed as
blocks (`[...]`, Smalltalk's closures) so they are not evaluated until the
receiver decides to run one. Smalltalk's grammar has no `if` at all.

The lambda-calculus version has the better pedigree: Church encodings are how
`true`, `false` and `if` are *defined* in a formalism that has nothing but
functions, which is the deeper reason the answer to this challenge exists at
all. **Haskell**'s `if` is likewise definable as a library function
(`bool :: a -> a -> Bool -> a`) because laziness supplies the delay that Lox
needs explicit functions for.

---

## Challenge 9.2 — looping without `while`

> Likewise, looping can be implemented using those same tools, provided our
> interpreter supports an important optimization. What is it, and why is it
> necessary? Name a language that uses this technique for iteration.

### The optimization

**Tail-call elimination** (tail-call optimization, proper tail calls).

A loop written with functions is recursion:

```lox
fun loop(i) {
  if (i >= 10) return nil;
  print i;
  return loop(i + 1);   // the recursive call is the last thing the body does
}
```

### Why it is necessary

Without it, every iteration is a function call that has not returned yet, so
every iteration consumes a stack frame. The frames are *useless* — nothing
remains to be done in the caller after `loop(i + 1)` returns except hand its
value straight back — but an ordinary call protocol keeps them anyway. A loop
of ten iterations is fine; a loop of a million overflows the stack. That turns
"looping" into "looping, up to some arbitrary limit set by the implementation",
which is not looping at all, and specifically not enough for the
Turing-completeness claim of §9.1: that argument needs unbounded iteration in
bounded memory.

Tail-call elimination is the observation that when a call is in *tail
position* — when its result is the caller's result, with no pending work — the
caller's frame can be **reused** instead of pushed on top of. The call becomes
a jump, the stack stops growing, and recursion costs exactly what a loop costs.

This is precisely the gap in the current implementation, and worth being honest
about: `lox.interpreter`'s `:while` clause uses Clojure's `while`, which is a
`loop`/`recur`, so **Lox loops** are already bounded by time rather than by
stack depth — the chapter 9 test suite drives one for 100,000 iterations to
pin that down. But the JVM has no tail-call elimination, Clojure's `recur` is
an explicit construct rather than an automatic optimization, and from chapter
10 a **Lox function call** will be a Clojure function call. So `loop(1000000)`
above will overflow, while the equivalent `while` will not. Fixing that would
mean trampolining Lox calls in tail position — detecting tail calls in the
resolver (chapter 11 is where the machinery to do so arrives) and returning a
thunk from `call` instead of recursing.

### A language that does this

**Scheme**, which requires it. R7RS does not describe it as an optimization
but as a semantic guarantee: an implementation "must be properly
tail-recursive", and Scheme accordingly has no iteration primitive it could
not define — `do` is a macro over tail recursion. **Erlang** and **Elixir**
rely on it just as totally, since their processes loop by tail-calling a
`receive` loop forever and must do so in constant space. **Lua** and
**OCaml** guarantee it too; **Java**, **Python** and the JVM-hosted languages
generally do not.

---

## Challenge 9.3 — `break`

> Unlike Lox, most other C-style languages also support `break` and `continue`
> statements inside loops. Add support for `break` statements.
>
> The syntax is a `break` keyword followed by a semicolon. It should be a
> syntax error to have a `break` statement appear outside of any enclosing
> loop. At runtime, a `break` statement causes execution to jump to the end of
> the nearest enclosing loop and proceeds from there. Note that the `break`
> may be nested inside other blocks and `if` statements that also need to be
> exited.

Enable it by binding the switch:

```clojure
(binding [lox.token/*allow-break?* true]
  (lox.core/run "while (true) { print 1; break; }"))
```

### The grammar

```
statement -> exprStmt | forStmt | ifStmt | printStmt | whileStmt
           | breakStmt | block ;
breakStmt -> "break" ";" ;
```

One production, one node (`:break`, carrying the `break` token), and one new
piece of parser state. The node is *always defined* — a node family cannot be
switched on and off, and every visitor must stay total, which `defvisitor`
enforces at compile time — but it is only ever *built* when the switch is on,
because with the switch off the scanner never produces a `:break` token and
`break` arrives as an ordinary identifier.

### The static half: "it should be a syntax error"

This is the more interesting requirement, because it is a claim about where
the `break` *is in the source*, not about what happens when it runs. "Outside
of any enclosing loop" is a statically decidable property, so it is decided at
parse time, by counting:

* `lox.parser/make-parser` seeds the parser state with `:loop-depth 0`;
* `parse-loop-body` — used by both `parse-while-statement` and
  `parse-for-statement` — parses the body with the depth raised, and restores
  the previous value on the way out;
* `parse-break-statement` reports `Must be inside a loop to use 'break'.`
  unless the depth is positive.

Three properties fall out of that, and each has a test:

1. **Blocks and `if`s are transparent.** They do not touch the depth, so a
   `break` three blocks deep inside a loop body is still inside the loop —
   which is the challenge's own reminder, handled by doing nothing.
2. **The depth is a count, not a flag.** `while (a) { while (b) break;
   break; }` is legal in both positions, because closing the inner loop
   returns to depth 1 rather than to 0.
3. **The depth is restored, not left raised.** `while (a) break; break;`
   reports the *second* `break` only. Threading the count through the
   immutable parser state is what makes this fall out for free: a rule that
   throws part-way leaves the caller holding the old state, so there is no
   `try`/`finally` to forget, which is the shape the mutable-`int` version in
   Java would need.

The error is reported but **not thrown**, following §8.4.1's reasoning for
`Invalid assignment target.`: the parser is not confused. It is looking at
`break` followed by `;` and knows exactly how to carry on, so panicking would
discard a perfectly parseable rest-of-statement. `had-error` guarantees the
program never runs, and parsing continues, so a second unrelated mistake is
still reported in the same pass.

Because it is a *syntax* error, nothing in the program executes — not even the
statements before the offending `break`. `print "before"; break;` prints
nothing.

### The dynamic half: jumping to the end of the loop

A `break` can be arbitrarily deep inside blocks and `if`s, so the signal has
to travel up through an unknown number of `execute` frames. Returning a value
would mean changing every statement form that runs a sequence — `:block`,
`execute-block`, `interpret` — to check for and propagate it: a change to
everything in order to support one thing. An exception already does exactly
this, so that is what is used:

```clojure
(def ^:private break-signal (ex-info "break" {:type ::break}))
```

thrown by the `:break` clause and caught by `:while`. This is the same
argument §6.3.3 makes for parse errors, and the one the book itself will make
in chapter 10, where `return` gets a `Return` exception for identical reasons.

Two details that are easy to get wrong, and so are tested directly:

* **The signal is a pre-allocated singleton.** A `break` has no payload — it
  means "stop" and nothing else — and `ex-info` fills in a stack trace
  eagerly, so allocating a fresh exception per `break` would charge a stack
  trace to every iteration of every loop that used one.
* **It is tagged, and re-thrown if the tag does not match.** Lox runtime
  errors are `ex-info` too (`lox.errors/runtime-error-ex`), so a bare
  `(catch clojure.lang.ExceptionInfo e ...)` around a loop body would swallow
  `Operands must be numbers.` and silently exit the loop instead of reporting
  it. That bug would pass every test of `break` itself, which is why
  `challenge-9-3-break-signal-does-not-swallow-real-errors` exists.

### `break` in a desugared `for` loop

`parse-for-statement` raises the loop depth around the body *before* it
desugars, so a `break` is legal in a `for` as well as a `while`. The depth
bookkeeping cannot be left to the `:while` node the desugaring produces,
because by the time that node is built the body has long since been parsed.

One behavioural consequence is worth stating, since it is a real difference
and not just a representation detail. The desugaring puts the increment at the
*end of the body*:

```
for (var i = 0; i < 9; i = i + 1) { print i; break; }
```

so a `break` skips the increment. That is correct — the loop is over, and
nothing is going to read `i` again — and the tests pin it down. But it is also
exactly why **`continue` cannot be desugared this way**: a `continue` must
jump to the increment and then re-test, and a `continue` implemented as an
unwind to the top of the generated `while` would skip the increment and spin
forever. Supporting `continue` would mean either giving `for` a real node
after all, or splitting the `:while` node into "body" and "increment" parts so
the signal can land between them. That is the standard reason real
implementations stop desugaring `for` at exactly this point, and it is a good
illustration of the limit of the technique §9.5.1 introduces: desugaring is
safe only while every construct that can observe the rewritten structure has
already been accounted for.

### Tests

`test/lox/chapter_09_control_flow_test.clj`, section 8 — five `deftest`s:

| Test | What it pins down |
| ---- | ----------------- |
| `challenge-9-3-default-is-the-books-lox` | with the switch off, `break` is an identifier and `var break = 1;` runs |
| `challenge-9-3-break-syntax` | the production, the required semicolon, the token kept for line numbers |
| `challenge-9-3-break-outside-a-loop-is-a-syntax-error` | top level, bare block, inside an `if`, *after* a loop closed; nothing executes; the line is reported; parsing continues |
| `challenge-9-3-break-is-legal-wherever-a-loop-encloses-it` | nested blocks and `if`s, outer loop after an inner one closed, and *not* in a condition |
| `challenge-9-3-break-execution` | exits the nearest loop only, skips the rest of the body, skips a `for` increment, discards the block scope, re-enters cleanly |
| `challenge-9-3-break-signal-does-not-swallow-real-errors` | a runtime error in a loop body, in a condition, and the tag check itself |
