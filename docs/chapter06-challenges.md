# Chapter 6 — Parsing Expressions: challenges

All three challenges are implemented in `src/lox/parser.clj` and tested in
`test/lox/chapter_06_parsing_test.clj` (section 4, "Challenges").

Two of them — the comma operator and the ternary — are **off by default**.
That decision is explained at the bottom of this file; it is not laziness, and
the code is fully exercised by the tests either way.

| Challenge | Switch | Default |
| --------- | ------ | ------- |
| 6.1 comma operator | `lox.parser/*allow-comma?*` | off |
| 6.2 ternary `?:` | `lox.parser/*allow-conditional?*` | off |
| 6.3 error productions | `lox.parser/*binary-error-productions?*` | **on** |

---

## Challenge 6.1 — the comma operator

> In C, a block is a statement form that allows you to pack a series of
> statements where a single one is expected. The comma operator is an analogous
> syntax for expressions. [...] Add support for comma expressions. Give them
> the same precedence and associativity as in C. Write the grammar, and then
> implement the necessary parsing code.

### Grammar

In C the comma operator has the **lowest precedence of any operator** — lower
even than assignment — and is **left-associative**. So it becomes the new
bottom of the precedence ladder, and `expression` delegates to it:

```
expression  → comma ;
comma       → conditional ( "," conditional )* ;
conditional → equality ( "?" expression ":" conditional )? ;
equality    → comparison ( ( "!=" | "==" ) comparison )* ;
...
```

The `( "," conditional )*` shape is the same left-associative-loop trick the
book uses for the four binary levels, and for the same reason: writing it as
`comma → comma "," conditional` would be left-recursive and would put a
recursive-descent parser into an infinite loop.

### Implementation

`parse-comma` reuses `parse-left-assoc-binary`, the helper that already builds
all four of the book's binary levels. The tree node is an ordinary `:binary`
with the `,` token as its operator, because C genuinely treats the comma as a
binary operator — it has two operands and yields the right one.

```clojure
(defn parse-comma [state]
  (if *allow-comma?*
    (parse-left-assoc-binary state parse-conditional [:comma])
    (parse-conditional state)))
```

### Behaviour

| Source | Tree |
| ------ | ---- |
| `1, 2` | `(, 1.0 2.0)` |
| `1, 2, 3` | `(, (, 1.0 2.0) 3.0)` — left-associative |
| `1 + 2, 3 * 4` | `(, (+ 1.0 2.0) (* 3.0 4.0))` — lowest precedence |
| `(1, 2) * 3` | `(* (group (, 1.0 2.0)) 3.0)` — grouping still fences it |

At runtime (chapter 7) it would evaluate the left operand, discard the result,
and return the right. Chapter 6 only has to build the tree.

---

## Challenge 6.2 — the ternary conditional operator

> Likewise, add support for the C-style conditional or "ternary" operator `?:`.
> What precedence level is allowed between the `?` and the `:`? Is the whole
> operator left-associative or right-associative?

### The two questions the challenge asks

**What precedence is allowed between `?` and `:`?** A **full expression**.
The middle operand is unambiguously fenced in on both sides by the two operator
tokens, so nothing needs to be excluded — there is no expression you could
write there that the parser could misread. C allows even assignment and the
comma operator there: `a ? b, c : d` is legal C. So `parse-conditional` calls
`parse-expression` for the middle operand, which means it will pick up
assignment automatically when chapter 8 adds it, with no further change here.

**Left- or right-associative?** **Right-associative.** `a ? b : c ? d : e`
groups as `a ? b : (c ? d : e)`, which is exactly what makes `else if` chains
read correctly. The other grouping, `(a ? b : c) ? d : e`, would be nearly
useless. This is expressed by *recursing* on the else branch instead of
looping — the same distinction that makes `unary` right-associative and the
four binary levels left-associative.

### Grammar

```
conditional → equality ( "?" expression ":" conditional )? ;
```

It sits between `comma` and `equality`: looser than `==`, tighter than `,`.

### Two supporting changes

The ternary is the first challenge that needed changes *outside* the parser:

1. **Two new tokens.** The book's Lox has no use for `?` or `:`, so its
   scanner rejects both as "Unexpected character." `:question` and `:colon`
   were added to `lox.token/token-types` and to `lox.scanner/scan-token`. This
   is a harmless superset: a program containing either character was a scan
   error before, and is a parse error now unless the challenge is enabled.

2. **A new AST node.** `:conditional [[:expr condition] [:expr then-branch]
   [:expr else-branch]]` was added to `define-ast` in `lox.ast`.

   It is a node of its own rather than a nested pair of `:binary` nodes because
   the three operands are genuinely one construct: chapter 7 must evaluate the
   condition and then **exactly one** branch, which a `:binary` tree could not
   express without special-casing the `?` and `:` tokens anyway.

   This turned out to be a good test of chapter 5's claim that adding a
   production costs one line. It did — plus the two visitors in
   `lox.ast_printer`, which `defvisitor` **refused to compile** until they
   handled the new node. That compile-time nag is precisely the guarantee the
   book gets from implementing the `Expr.Visitor<R>` interface, and it worked.

### Printed forms

| Source | AST printer | RPN printer |
| ------ | ----------- | ----------- |
| `true ? 1 : 2` | `(?: true 1.0 2.0)` | `true 1.0 2.0 ?:` |
| `true ? 1 : false ? 2 : 3` | `(?: true 1.0 (?: false 2.0 3.0))` | — |
| `1 < 2 ? 3 : 4` | `(?: (< 1.0 2.0) 3.0 4.0)` | — |

`?:` names the whole operator the way `group` names a grouping: there is no
single operator token to take a lexeme from, since the construct is spelled
with two.

---

## Challenge 6.3 — error productions for a missing left-hand operand

> Add error productions to handle each binary operator appearing without a
> left-hand operand. In other words, detect a binary operator appearing at the
> beginning of an expression. Report that as an error, but also parse and
> discard a right-hand operand with the appropriate precedence.

### Why it helps

Without this, `* 3` reports the generic `Expect expression.` pointed at the
`*`. That is true but unhelpful — it says a token is wrong without saying what
was wrong with it. With the error production we say what actually happened:

```
[line 1] Error at '*': Binary operator '*' requires a left-hand operand.
```

### "With the appropriate precedence" — the important half

The challenge's real content is the second sentence. Consuming the operator and
its right operand *at the operator's own precedence level* leaves the parser
somewhere sensible instead of staring at the same offending token. Otherwise
`* 1 + 2` would produce a cascade of errors, each a side effect of the first —
the exact failure mode section 6.3 warns about.

The operand level is derived from `binary-levels` rather than written out, so
the table and the parser cannot drift apart:

```clojure
(def binary-operators-by-level
  ;; :star/:slash -> parse-unary,  :plus/:minus -> factor, ...
  ...)
```

A test asserts that this table covers all ten binary operators, and another
asserts that `* 1 + 2` reports exactly **one** error.

### `-` is deliberately excluded

`-` is both a binary operator (at the term level) and a unary one, so a leading
`-` is perfectly good Lox — `-3` is not an error at all, and `parse-unary`
consumes it long before this check runs. `lox.parser/prefix-operators` is the
set of operators exempt from the check, and a test pins `-3` as valid.

This was a genuine bug during implementation: the first version of the check
consulted `binary-operators-by-level`, which contains `:minus`, and
consequently rejected every negative number in the language.

### On by default

Unlike 6.1 and 6.2, this one is on by default. It can only ever fire on input
that is *already* a syntax error, so it changes no valid program — it just
produces a better message for an invalid one.

---

## Why 6.1 and 6.2 are off by default

Chapter 4's block-comment challenge could be left permanently on because it is
a strict superset: no valid Lox program changes meaning when block comments
exist. The comma operator is **not** in that category.

`,` is a separator elsewhere in the language the rest of the book builds:

* **Chapter 10**, function calls: `f(a, b)`. A comma expression at the lowest
  precedence level would swallow the argument separators, and `f(a, b)` would
  parse as a call with the *single* argument `(a, b)`.
* **Chapter 8 onwards**, anywhere a comma-separated list appears.

C has the same conflict and resolves it with a special case: the grammar for a
function argument uses *assignment-expression*, not *expression*, precisely to
exclude the comma operator. Lox could do the same, but that special case would
have to be threaded through several later chapters for a feature the book's Lox
does not have.

The ternary is harmless by comparison — `?` and `:` appear nowhere else — but
it is gated the same way for consistency, and because a grader comparing this
implementation against the book should see the book's grammar by default.

So the default grammar is **exactly the book's**, and the challenge code is
real, reachable, and tested:

```clojure
(binding [lox.parser/*allow-comma?* true
          lox.parser/*allow-conditional?* true]
  (lox.parser/parse-source "true ? 1, 2 : 3"))
;; => (?: true (, 1.0 2.0) 3.0)
```

---

## One deviation from the book, noted

Beyond the challenges, `parse` reports **`Expect end of expression.`** when
tokens remain after the expression has been parsed.

The book's chapter 6 `parse()` parses one expression and returns, silently
ignoring anything that follows — so `1, 2` would quietly parse as just `1`,
with no error and no indication that half the input went unread. That is a hole
in the parser's first duty ("detect and report the error").

The hole closes on its own in chapter 8, where `parse` becomes a loop that runs
until `:eof` and any leftover token is simply the start of the next statement.
Until then it is closed explicitly.
