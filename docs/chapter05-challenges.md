# Chapter 5 — Representing Code: challenges

The three challenges at the end of the chapter. 5.3 is implemented in code
(`src/lox/ast_printer.clj`, tested in `test/lox/chapter_05_ast_test.clj`); 5.1
and 5.2 are written answers, recorded here so the work is visible.

---

## Challenge 5.1 — remove the notational sugar

> Earlier, I said that the `|`, `*`, and `+` forms we added to our grammar
> metasyntax were just syntactic sugar. Take this grammar:
>
> ```
> expr → expr ( "(" ( expr ( "," expr )* )? ")" | "." IDENTIFIER )+
>      | IDENTIFIER
>      | NUMBER
> ```
>
> Produce a grammar that matches the same language but does not use any of
> that notational sugar.

Each piece of sugar has a mechanical translation:

* `a | b` at the top level → two separate productions with the same head.
* `( ... )` grouping → a new named nonterminal.
* `x?` → a nonterminal with two productions, one containing `x` and one empty.
* `x*` → a left-recursive nonterminal: `xs → ε | xs x`.
* `x+` → the same, but the base case is one `x`: `xs → x | xs x`.

Applying all five:

```
expr      → expr calls ;
expr      → IDENTIFIER ;
expr      → NUMBER ;

calls     → call ;
calls     → calls call ;

call      → "(" ")" ;
call      → "(" arguments ")" ;
call      → "." IDENTIFIER ;

arguments → expr ;
arguments → arguments "," expr ;
```

`calls` is the desugared `+`: one call, or a run of calls followed by one
more. `call` has three productions because `( ... | ... )` became a rule of its
own and the `?` around the argument list became the split between the first two
of them — empty parentheses, or parentheses with arguments. `arguments` is the
desugared `expr ( "," expr )*`: one expression, or a list followed by a comma
and one more.

No `|`, `*`, `+`, `?` or parentheses-for-grouping remain; every production is a
flat sequence of terminals and nonterminals, which is a context-free grammar in
its pure Backus–Naur form.

**Bonus: what does this grammar encode?**

Function calls and property accesses, chained in any order — exactly Lox's
`call` and `get` expressions, which arrive in chapters 10 and 12. It generates
things like `f()`, `f(a, b)`, `object.field`, `object.method(a)(b).other`: a
primary (an identifier or a number) followed by one or more suffixes, each
suffix being either an argument list or a dotted name. The left recursion is
what makes the chaining left-associative, so `a.b.c` groups as `(a.b).c`, which
is what you want — the `.c` applies to whatever `a.b` produced.

---

## Challenge 5.2 — the Visitor pattern's dual

> The Visitor pattern lets you emulate the functional style in an
> object-oriented language. Devise a complementary pattern for a functional
> language. It should let you bundle all of the operations on one type together
> and let you define new types easily.

The chapter's table has types for rows and operations for columns. OO languages
make rows cheap and columns expensive; the Visitor pattern buys back a column
by indirecting through an interface whose methods are the cells of that column.
A functional language has the opposite default — a function pattern-matching
over a sum type is a column, so columns are cheap and *rows* are expensive:
adding a type means revisiting every existing match.

The dual, then, indirects the other way. Instead of a value being a tag that
functions dispatch on, **a value is a record of the operations you can perform
on it** — one field per operation, each field a closure that already knows the
data it closes over. Adding a new type is defining one new record; no existing
function is touched.

In Scheme, a "type" becomes a constructor returning a dispatch closure:

```scheme
(define (make-literal value)
  (lambda (op)
    (case op
      ((print)    (lambda () (if (null? value) "nil" (->string value))))
      ((evaluate) (lambda () value)))))

(define (make-unary operator right)
  (lambda (op)
    (case op
      ((print)    (lambda () (string-append "(" operator " " (print right) ")")))
      ((evaluate) (lambda () (- ((right 'evaluate))))))))

(define (print expr)    ((expr 'print)))
(define (evaluate expr) ((expr 'evaluate)))
```

All of `make-unary`'s behaviour sits in one place, which is the "bundle all of
the operations on one type together" the challenge asks for, and a new node
type is a new `make-…` with no edits elsewhere. The cost is precisely the one
Visitor imposes in reverse: adding an *operation* now means editing every
constructor. The table is symmetric, and so is the pain.

This is the same shape as a Java interface with an implementation per type — it
is sometimes called the "closure of operations" or, given static types,
described as an existential/object encoding. It is also what Clojure's
protocols and records give directly:

```clojure
(defprotocol ExprOps
  (print-expr [this])
  (evaluate [this]))

(defrecord Literal [value]
  ExprOps
  (print-expr [_] (if (nil? value) "nil" (str value)))
  (evaluate   [_] value))
```

A new record adds a row without touching the protocol, and `extend-protocol`
adds a column without touching the records — which is why Clojure (like CLOS,
Dylan and Julia, the multimethod languages the chapter name-checks) sidesteps
the expression problem rather than trading one side of it for the other.

**Why this interpreter does not use that.** It would work, but it would make
nodes opaque. Representing nodes as plain maps keeps them printable, comparable
with `=`, usable as map keys, and traversable generically via
`lox.ast/children` — all of which the tests lean on heavily, and all of which
a closure-based node would lose. The trade is deliberate: `lox.ast/accept`
plus `defvisitor` recovers the operation-side convenience, and `define-ast`
recovers the type-side convenience, so neither column nor row is expensive
here.

---

## Challenge 5.3 — a reverse Polish notation printer

> Define a visitor class for our syntax tree classes that takes an expression,
> converts it to RPN, and returns the resulting string.

Implemented as `lox.ast-printer/print-rpn`, built with `lox.ast/defvisitor`
exactly as the AST printer is — which is the chapter's point: a second
operation over the tree required no change to any node definition.

```
(1 + 2) * (4 - 3)   ->   1 2 + 4 3 - *
```

Two things worth calling out:

**Grouping disappears.** In RPN the evaluation order is carried entirely by
operator position, so parentheses have nothing left to express. The visitor's
`:grouping` method just returns the printing of its inner expression.

**Unary minus is written `~`.** Emitting the operator's lexeme, as the AST
printer does, would make `1 2 -` mean both "2 subtracted from 1" and "1, then
negate 2". Since the entire value of an RPN rendering is that it can be fed to
a stack machine, the printer uses `~` for negation, which keeps the output
unambiguous. Unary `!` needs no such treatment — Lox has no binary `!`, so its
lexeme is already unique.

The test suite makes that concrete: `rpn-is-evaluable-left-to-right` implements
a small stack machine, runs the generated RPN through it, and asserts the
result equals the result of evaluating the tree directly, over five different
expressions including nested unary negation.
