;;;; =============================================================================
;;;; Chapter 4 - Scanning  (the interpreter framework: the book's `Lox.java`)
;;;;
;;;; The command-line entry point. Book section 4.1 "The Interpreter Framework"
;;;; defines main / runFile / runPrompt / run, plus the exit-code conventions
;;;; borrowed from the UNIX sysexits.h header:
;;;;
;;;;   64 (EX_USAGE)    - bad command line
;;;;   65 (EX_DATAERR)  - the source had a static error
;;;;
;;;; UPDATED IN CHAPTER 6 - Parsing Expressions (section 6.4, "Wiring up the
;;;; Parser"). `run` scans, parses, and prints the resulting syntax tree with
;;;; chapter 5's AST printer.
;;;;
;;;; UPDATED IN CHAPTER 7 - Evaluating Expressions (section 7.4, "Hooking Up
;;;; the Interpreter"). The pipeline is now complete end to end:
;;;;
;;;;     scan -> tokens -> parse -> expression -> evaluate -> value
;;;;
;;;; and `run` prints the *value* rather than the tree. Chapter 7 also adds a
;;;; second failure mode and a third exit code, from sysexits.h:
;;;;
;;;;   64 (EX_USAGE)       - bad command line
;;;;   65 (EX_DATAERR)     - the source had a static (scan/parse) error
;;;;   70 (EX_SOFTWARE)    - the source ran and hit a runtime error
;;;;
;;;; The distinction matters: a static error means the code never ran, while
;;;; 70 means it ran and failed partway. The REPL, by contrast, ignores both
;;;; flags - "If the user is running the REPL, we don't care about tracking
;;;; runtime errors. After they are reported, we simply loop around."
;;;;
;;;; UPDATED IN CHAPTER 8 - Statements and State (sections 8.1.3, 8.5.2). `run`
;;;; now parses a *program* and executes it:
;;;;
;;;;     scan -> tokens -> parse -> statements -> execute
;;;;
;;;; Two consequences for this file, both small and both worth naming.
;;;;
;;;; First, `run` no longer prints anything itself. Chapters 6 and 7 printed
;;;; the tree and then the value, because there was no other way to see that
;;;; anything had happened. From chapter 8 a program says what it wants to say
;;;; with `print`, and a `run` that also echoed its last expression would be
;;;; inventing output the language does not specify.
;;;;
;;;; Second, the `hadError` guard between parsing and executing stops being a
;;;; nil check and becomes a genuine check of the flag. Chapter 6's `parse`
;;;; returned nil on a syntax error; chapter 8's returns the statements that
;;;; *did* parse, which is truthy and must still not be run. The book makes
;;;; the same move, and this is the case its comment ("Stop if there was a
;;;; syntax error") was always guarding against.
;;;;
;;;; Challenge 8.1 is implemented in `run-prompt`: a line that is a complete
;;;; expression is evaluated and its value displayed, and anything else is
;;;; executed as statements. See `*repl-echoes-expressions?*`.
;;;;
;;;; The book deletes each chapter's temporary output as the next one replaces
;;;; it. We keep the old behaviours behind flags instead - `--tokens` for
;;;; chapter 4's token dump and `--ast` for the tree printer - because each
;;;; chapter is a graded submission of its own and its example script should
;;;; stay runnable.
;;;; =============================================================================
(ns lox.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [lox.ast-printer :as printer]
            [lox.errors :as err]
            [lox.interpreter :as interp]
            [lox.parser :as parser]
            [lox.scanner :as scanner]
            [lox.token :as tok]))

(defn print-tokens
  "Chapter 4's `run`: dump the token stream, one token per line.

  Superseded by parsing in chapter 6, but kept available behind `--tokens` so
  that chapter 4 can still be demonstrated."
  [source]
  (doseq [token (scanner/scan-tokens source)]
    (println (tok/token->string token))))

(defn print-ast
  "`run`, but printing the syntax tree instead of executing it.

  Chapter 6's temporary `run`, promoted to a permanent debugging aid behind
  `--ast`: seeing the tree is still the quickest way to check that precedence
  came out right, and it is the only way to see what the parser did with a
  construct whose runtime behaviour looks the same either way.

  UPDATED IN CHAPTER 8 to print a whole *program*, one statement per line,
  since from this chapter a script is a list of declarations rather than a
  single expression. `--ast-expression` keeps the exact chapter 6 behaviour
  for a single expression with no trailing semicolon."
  [source]
  (let [statements (parser/parse-program source)]
    (when-not @err/had-error
      (println (printer/print-program statements)))))

(defn print-ast-expression
  "Chapter 6's `run`, verbatim: scan, parse *one expression*, print its tree.

  Behind `--ast-expression`, so that chapter 6's submission is still runnable
  exactly as it was - `clox --ast-expression` accepts `1 + 2 * 3` with no
  semicolon and no statement around it."
  [source]
  (let [expression (parser/parse-source source)]
    (when-not @err/had-error
      (println (printer/print-ast expression)))))

(defn run
  "Book: `Lox.run`.

  Chapter 8 version: scan, parse into statements, and execute them. Anything
  the user sees was printed by the program itself.

  The `hadError` check between parsing and interpreting is the book's (\"Stop
  if there was a syntax error\"), and in this chapter it finally has to be a
  check of the *flag*: `parse` returns the statements that parsed successfully,
  so a program with a syntax error yields a non-empty, perfectly walkable
  list that must nevertheless not be run. Executing half a program whose
  meaning we have already failed to determine is how a tool produces output
  that is worse than no output."
  [source]
  (let [statements (parser/parse (scanner/scan-tokens source))]
    (when-not @err/had-error
      (interp/interpret statements))))

(def ^:dynamic *repl-echoes-expressions?*
  "Challenge 8.1. When true (the default), a REPL line that is a complete
  expression is evaluated and its value printed, rather than being rejected
  for want of a semicolon.

  On by default, unlike the chapter 6 and 7 challenge switches, and the
  difference is principled: those changed what *programs* mean, and had to
  default to the book's language so that this implementation and clox agree.
  This one changes nothing about the language at all - `run` is untouched, a
  script behaves identically, and the only difference is whether the prompt
  answers `1 + 2` with `3` or with a syntax error. The book's own framing
  (\"That's a drag\") treats the regression as an accident of implementation
  order rather than a design decision."
  true)

(defn run-repl-line
  "Execute one line of REPL input. Challenge 8.1.

  With the challenge on, `lox.parser/parse-repl` decides whether the line is
  one complete expression or a series of statements, and the two are handled
  the two different ways the challenge asks for: an expression is evaluated
  and *displayed* (chapter 7's `interpret-expression`), statements are merely
  executed. With it off, this is exactly `run`.

  Note what makes the distinction visible: `1 + 2` prints `3`, while
  `1 + 2;` - an expression *statement* - prints nothing, because that is what
  an expression statement does. The semicolon is not noise."
  [source]
  (if-not *repl-echoes-expressions?*
    (run source)
    (let [[kind parsed] (parser/parse-repl source)]
      (when-not @err/had-error
        (case kind
          :expression (interp/interpret-expression parsed)
          :statements (interp/interpret parsed))))))

(defn run-expression
  "Chapter 7's `run`: scan, parse *one expression*, evaluate it and print the
  value.

  Behind `--expression`, so chapter 7's submission stays runnable: that
  chapter's example script is a bare expression with no semicolon, which is
  not a legal chapter 8 program at all."
  [source]
  (let [expression (parser/parse-source source)]
    (when-not @err/had-error
      (interp/interpret-expression expression))))

(defn run-file
  "Book: `Lox.runFile`. Execute a script; returns the process exit status.

  A static error means we never ran the code at all, and exits 65. A runtime
  error means we ran it and it failed partway, and exits 70 (chapter 7). The
  static check comes first: if parsing failed, nothing ran, so there cannot be
  a meaningful runtime result to report."
  ([path] (run-file path run))
  ([path run-fn]
   (err/reset-errors!)
   (run-fn (slurp (io/file path)))
   (cond
     @err/had-error         65
     @err/had-runtime-error 70
     :else                  0)))

(defn run-prompt
  "Book: `Lox.runPrompt`. The interactive REPL.

  `hadError` is reset after every line: a mistake on one line should not kill
  the whole session. Control-D (EOF) makes `read-line` return nil and ends it.

  CHAPTER 8: the default `run-fn` is `run-repl-line`, not `run`, so that
  challenge 8.1's expression echoing is what you get at the prompt. Note that
  *globals* are deliberately not reset between lines - only the error flags
  are. `lox.interpreter/*environment*` keeps its root binding for the life of
  the process, which is the book's reason for making `Lox.interpreter` a
  static field: \"Those variables should persist throughout the REPL
  session.\""
  ([] (run-prompt run-repl-line))
  ([run-fn]
   (loop []
     (print "> ")
     (flush)
     (when-let [line (read-line)]
       (err/reset-errors!)
       (run-fn line)
       (recur)))))

(def stage-flags
  "Optional leading flag -> the `run` variant it selects.

  Not in the book, which deletes each stage's output as the next replaces it.
  Keeping the earlier stages reachable means every chapter's example script
  stays runnable, and makes the pipeline visible from the command line:

    clox --tokens         f.lox   ch. 4   source -> tokens
    clox --ast-expression f.lox   ch. 6   source -> one expression's tree
    clox --expression     f.lox   ch. 7   source -> one expression's value
    clox --ast            f.lox   ch. 8   source -> a program's trees
    clox                  f.lox   ch. 8   source -> run it"
  {"--tokens"         print-tokens
   "--ast"            print-ast
   "--ast-expression" print-ast-expression
   "--expression"     run-expression})

(defn -main
  "Book: `Lox.main`. With one argument, run that script; with none, start the
  REPL; with more, complain and exit 64.

  CHAPTER 8: a *script* and a *prompt* no longer want the same function. A
  script is a program, so it gets `run`. A prompt gets `run-repl-line`, which
  additionally displays the value of a line that is a bare expression
  (challenge 8.1). A stage flag overrides both, so `clox --tokens` with no
  script still gives an interactive token dump."
  [& args]
  (let [flag    (first args)
        staged? (contains? stage-flags flag)
        run-fn  (get stage-flags flag run)
        repl-fn (if staged? run-fn run-repl-line)
        args    (if staged? (rest args) args)
        status  (cond
                  (> (count args) 1)
                  (do (println (str "Usage: clox [--tokens | --ast | "
                                    "--ast-expression | --expression] [script]"))
                      64)

                  (= (count args) 1) (run-file (first args) run-fn)
                  :else              (do (run-prompt repl-fn) 0))]
    (flush)
    (System/exit status)))
