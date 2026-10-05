# Chapter 7 — Evaluating Expressions: challenges

All three challenges are implemented in `src/lox/interpreter.clj` and tested in
`test/lox/chapter_07_evaluating_test.clj` (section 7, "Challenges").

All three are **off by default**, and each is exercised by the test suite in
both positions. The reason is the one the book itself keeps repeating in this
chapter — "to ensure that jlox and clox work the same", "Users rely on these
details … if the implementations aren't consistent, their program will break
when they run it on different interpreters." Every one of these three changes
the meaning of a program that is **already valid** under the book's semantics,
so switching any of them on by default would make this interpreter quietly
disagree with every other Lox. Chapter 6 used the same convention for its
grammar challenges.

| Challenge | Switch | Default |
| --------- | ------ | ------- |
| 7.1 comparisons on non-numbers | `lox.interpreter/*compare-strings?*` | off |
| 7.2 string-coercing `+` | `lox.interpreter/*string-coercing-plus?*` | off |
| 7.3 division by zero is an error | `lox.interpreter/*error-on-division-by-zero?*` | off |

Turn one on by binding it:

```clojure
(binding [lox.interpreter/*string-coercing-plus?* true]
  (lox.interpreter/stringify (lox.interpreter/evaluate (lox.parser/parse-source "\"scone\" + 4"))))
;; => "scone4"
```

---

## Challenge 7.1 — comparing other types

> Allowing comparisons on types other than numbers could be useful. The
> operators might have a reasonable interpretation for strings. Even
> comparisons among mixed types, like `3 < "pancake"` could be handy to enable
> things like ordered collections of heterogeneous types. Or it could simply
> lead to bugs and confusion.
>
> Would you extend Lox to support comparing other types? If so, which pairs of
> types do you allow and how do you define their ordering? Justify your
> choices and compare them to other languages.

### The answer implemented here

**Yes for two strings. No for every mixed pair, and no for booleans or nil.**

With `*compare-strings?*` on, `<` `<=` `>` `>=` accept two strings and order
them by `String.compareTo` — UTF-16 code-unit order. Everything else that was
a runtime error stays one, and the error message widens to "Operands must be
two numbers or two strings." so that it still describes the actual rule.

```
"a"  <  "b"      true
"ab" <  "abc"    true     // a prefix sorts before what extends it
"Z"  <  "a"      true     // uppercase ASCII precedes lowercase
""   <  "a"      true
1    <  "a"      runtime error: Operands must be two numbers or two strings.
true <  false    runtime error
nil  <  1        runtime error
```

### Why strings: yes

1. **The ordering is already a convention users know.** Lexicographic string
   order is what `sort` does in essentially every language, what a dictionary
   does, and what `strcmp` does. There is nothing to invent, so there is
   nothing for the user to memorise or be surprised by.
2. **It is a genuine total order**, which is what makes it safe to sort with.
   Exactly one of `a < b`, `a == b`, `a > b` holds for any two strings, and it
   is transitive. Challenge 7.1 mentions "ordered collections", and that is the
   property such a collection actually needs.
3. **It agrees with `==`.** `"a" == "a"` is already true in chapter 7, and
   `compareTo` returns 0 for exactly the pairs that `equals` calls equal, so
   `<=` and `>=` cannot contradict `==`. (A test asserts this.)

One wart worth naming: UTF-16 code-unit order is not alphabetical order in any
human language. `"Z" < "a"` because of where ASCII happens to put the cases,
and accented letters sort after all unaccented ones. Real collation is
locale-dependent and far too big to bolt onto `<`; Java needs a whole
`Collator` class for it. Code-unit order is the honest, predictable choice, and
it is the one Java's own `String` implements.

### Why mixed types: no

The challenge floats `3 < "pancake"`. Any answer to that question is made up.
Whatever order we pick — numbers before strings, strings before numbers,
compare the number's digits to the string's characters — is an arbitrary
decision the user has to learn, and it buys them nothing: no program *means*
anything by comparing a number to a string. It is nearly always a bug, usually
a variable that holds something other than what the author assumed. Reporting
it is more useful than ordering it.

The cost of guessing is also not hypothetical. **Python 2** allowed exactly
this: `3 < "pancake"` was true, because mixed types compared by type *name*.
It silently hid bugs, and sorting a heterogeneous list gave a result that
depended on the spelling of the types involved. Python 3 made it a
`TypeError`, and the 2-to-3 migration notes cite it as one of the changes that
caught real defects. **JavaScript** goes the other way and coerces: `3 < "pancake"`
is `false` because `"pancake"` becomes `NaN`, and so is `3 > "pancake"`, which
means JS's `<` is not a total order at all and `Array.sort` on mixed data is
unpredictable. **PHP 7** had `0 == "foo"` be true for the same family of
reasons, and PHP 8 changed it.

So: the one case with an ordering users already agree on gets it, and the cases
that would require inventing one are refused.

### Why booleans and nil: no

Even same-type comparison is unmotivated here. `false < true` would require
deciding that false is "less" — defensible, since C and SQL both encode false
as 0, but there is no program that wants it, and `nil < nil` has no reading at
all. Three valid pairs of types is already more surface than this operator
needs.

---

## Challenge 7.2 — string-coercing `+`

> Many languages define `+` such that if either operand is a string, the other
> is converted to a string and the results are then concatenated. For example,
> `"scone" + 4` would yield `scone4`. Extend the code in `visitBinaryExpr()` to
> support that.

### Implementation

In `eval-plus`, a third case after the book's two:

```clojure
(cond
  (and (lox-number? left) (lox-number? right)) (+ (double left) (double right))
  (and (lox-string? left) (lox-string? right)) (str left right)

  (and *string-coercing-plus?*
       (or (lox-string? left) (lox-string? right)))
  (str (stringify left) (stringify right))

  :else (err/throw-runtime-error! operator "Operands must be two numbers or two strings."))
```

Two details that are deliberate:

**It is checked third, not first.** The book's two cases keep their meaning
exactly, so `1 + 2` is still `3` and never `"12"`, and `"a" + "b"` is still
`"ab"`. Only pairs that were *previously runtime errors* can change behaviour,
which is the smallest possible blast radius for the feature.

**It converts with `stringify`, not with the host's `str`.** This is the part
that is easy to get wrong. A Lox number is a `Double`, so Java's `toString`
renders `4` as `"4.0"`:

```
"scone" + 4    ->  "scone4"      with stringify   (what the challenge asks for)
"scone" + 4    ->  "scone4.0"    with str         (wrong)
```

`stringify` is already the function that answers "how does the user see this
value", so reusing it means `+` and `print` can never disagree about how a
number is spelled.

```
"scone" + 4      scone4
4 + "scone"      4scone
"n = " + 1.5     n = 1.5
"x" + true       xtrue
"x" + nil        xnil
1 + nil          runtime error  (still — neither side is a string)
```

### A note on the design, since the challenge invites one

This is convenient and it is also the single most-complained-about overload in
mainstream languages, because it makes `+` non-associative in a way users do
not expect:

```
"a" + 1 + 2     ->  "a12"     // ("a" + 1) + 2
"a" + (1 + 2)   ->  "a3"
```

Both are tested. The behaviour follows inevitably from `+` being
left-associative, and it is exactly the JavaScript footgun where `1 + 2 + "3"`
is `"33"` but `"1" + 2 + 3` is `"123"`. That is part of why it stays off by
default here: it is a real answer to the challenge, not an improvement to Lox.

---

## Challenge 7.3 — division by zero

> What happens right now if you divide a number by zero? What do you think
> should happen? Justify your choice. How do other languages you know handle
> division by zero, and why do they make the choices they do?
>
> Change the implementation in `visitBinaryExpr()` to detect and report a
> runtime error for this case.

### What happens right now

Lox numbers are doubles, so the IEEE 754 answers come through unchanged:

```
 1 / 0      Infinity
-1 / 0      -Infinity
 1 / -0     -Infinity
 0 / 0      NaN
```

No error, no crash. And because `NaN` propagates through every operation that
touches it, a zero divisor deep inside a computation shows up as a `NaN` at the
very end, arbitrarily far from the mistake that produced it — with nothing to
say which operation was responsible.

#### A Clojure-specific trap found while implementing this

This one is worth recording, because the obvious Clojure translation of the
book's code is **wrong**, and it is wrong in a way that only a divide-by-zero
test reveals:

```clojure
(/ 1.0 0.0)                                   ;=> ##Inf        (literals)
(/ (Double/valueOf 1.0) (Double/valueOf 0.0)) ;=> throws ArithmeticException
```

Clojure's `/` on *boxed* `Double` objects goes through
`clojure.lang.Numbers`, which checks for a zero divisor and throws
`ArithmeticException: Divide by zero`. On *primitive* doubles it compiles to
the JVM's `ddiv` and yields `Infinity`. The interpreter's values are always
boxed — they arrive as `Object`s out of a map — so the book's
`(double)left / (double)right` has to be written out as
`(/ (double left) (double right))` rather than `(/ left right)`, or `1 / 0`
raises a host exception that is not a Lox error at all.

The same split shows up in comparison, in the opposite direction:
Clojure's `compare` on two Doubles uses `Double.compareTo`, which orders `NaN`
above everything, so `NaN > 1` would come out **true**. The book compares
primitive doubles, where every NaN comparison is false. `eval-comparison`
therefore uses primitive `>` / `<` and not `compare`. Both behaviours are
pinned by tests (`division-by-zero-is-ieee-by-default`,
`comparisons-use-ieee-ordering-for-nan`), because both are invisible until
exactly the case that breaks them.

### What I think should happen, and what the switch does

With `*error-on-division-by-zero?*` on:

```
1 / 0        runtime error: Division by zero.    [line of the / token]
0 / 0        runtime error: Division by zero.
1 / -0       runtime error  (-0.0 is zero too, and divides to -Infinity)
1 / 0.5      2              (unaffected)
```

`(zero? right)` is used rather than `(== right 0.0)` so that `-0.0` is caught
as well: it is just as surely a division by zero, and it produces `-Infinity`.

**For a language like Lox, I think the error is the better default**, and the
reason is the one the chapter is built around. Chapter 7 goes to real trouble
to make every *type* error a reported runtime error with a line number, instead
of letting a bad value propagate — the whole argument of section 7.3 is that
"once you misinterpret bits in memory, all bets are off", and that a usable
language reports the failure where it happens. A silent `NaN` is precisely the
failure mode section 7.3 rejects, just with arithmetic instead of types: it
turns a mistake at a known line into a wrong answer at an unknown one. Lox has
no way to *test* for `NaN` either — no `isnan`, and `NaN == NaN` is true here
because `isEqual` uses `equals` — so a program cannot even check for it
defensively.

It stays off by default only for compatibility, per the note at the top of this
file: it changes the meaning of valid programs, and the book's Lox is the
reference.

### How other languages handle it, and why

| Language | Integer `1/0` | Float `1.0/0.0` | Why |
| --- | --- | --- | --- |
| C | undefined behaviour | `Infinity` (IEEE) | Integer division maps to a hardware instruction that traps; the standard declines to define it rather than pay to check. Floats get IEEE because the FPU gives it for free. |
| Java / C# | `ArithmeticException` | `Infinity` | Same split as C, but the integer case is defined and checked. The JVM spec mandates the throw. |
| Python | `ZeroDivisionError` | `ZeroDivisionError` | Deliberately *rejects* IEEE here. Python's priority is that errors are not silent; it raises even for floats, where the hardware would happily return `inf`. |
| JavaScript | — | `Infinity` | Only has doubles, and committed to raw IEEE semantics. `0/0` is `NaN`, and `NaN !== NaN`, so at least it is detectable. |
| Rust | panic (debug) / wraps | `inf` | Integer division by zero panics; floats follow IEEE. Same reasoning as Java, with the checks made explicit. |
| Lox (book) | — | `Infinity` | Only has doubles, implemented as Java `double`, so IEEE arrives by inheritance rather than by decision. |

The pattern across the table is that the choice tracks **where the value came
from**. Integer division has no representable answer for a zero divisor, so
every language must do *something* deliberate, and most raise. Float division
has `Infinity` sitting right there in the format, so languages that care most
about speed and numeric fidelity return it — and the one that cares most about
mistakes being loud, Python, throws anyway.

Lox's behaviour is the one case in the table that was not really chosen: it has
only doubles, those doubles are Java's, and Java's floats follow IEEE. That is
an inherited default rather than a design decision, which is exactly why the
challenge asks about it.

---

## Running the challenge tests

The whole suite, challenges included:

```bash
./scripts/test.sh
```

Just this chapter:

```bash
./scripts/test.sh lox.chapter-07-evaluating-test
```
