;;;; =============================================================================
;;;; Chapter 4 - Scanning
;;;;
;;;; The scanner turns raw Lox source text into a flat sequence of *tokens*.
;;;; This namespace holds the two supporting data definitions the chapter
;;;; introduces: the `TokenType` enumeration and the `Token` class.
;;;;
;;;; In Clojure we do not need either a Java enum or a Java class:
;;;;   * a token *type* is a plain keyword, e.g. :left-paren, :while, :eof
;;;;   * a token is a plain immutable map with :type :lexeme :literal :line
;;;;
;;;; Keeping tokens as maps means every later phase (parser, resolver,
;;;; interpreter) can destructure them directly and compare them with `=`.
;;;; =============================================================================
(ns lox.token
  (:require [clojure.string :as str]))

(def token-types
  "Every token type in the Lox lexical grammar (book: `TokenType.java`).

  Grouped exactly as the book groups them so the two can be read side by side."
  #{;; Single-character tokens.
    :left-paren :right-paren :left-brace :right-brace
    :comma :dot :minus :plus :semicolon :slash :star

    ;; Chapter 6 (challenge 6.2) - the ternary conditional operator `?:`.
    ;; Not part of the book's lexical grammar: the book's Lox has no use for
    ;; either character, so its scanner rejects both as "Unexpected character."
    ;; Scanning them is a harmless superset - a program that contains `?` or
    ;; `:` was a scan error before and is a *parse* error now unless the
    ;; challenge grammar is switched on (see `lox.parser/*allow-conditional?*`).
    :question :colon

    ;; One or two character tokens.
    :bang :bang-equal
    :equal :equal-equal
    :greater :greater-equal
    :less :less-equal

    ;; Literals.
    :identifier :string :number

    ;; Keywords.
    :and :class :else :false :fun :for :if :nil :or
    :print :return :super :this :true :var :while

    ;; Chapter 9 (challenge 9.3) - `break`.
    ;; Not part of the book's lexical grammar: in the book's Lox `break` is an
    ;; ordinary identifier, so `var break = 1;` is a legal program. Reserving
    ;; a word is therefore *not* a harmless superset the way `?` and `:` were
    ;; in chapter 6 - it takes a name away from every program that used it -
    ;; which is why the scanner only produces this token when
    ;; `*allow-break?*` is on. See docs/chapter09-challenges.md.
    :break

    :eof})

(def keywords
  "Reserved words, mapped to their token type (book: the `keywords` HashMap).

  The scanner first reads a maximal identifier lexeme and only then consults
  this map, which implements the *maximal munch* rule: `orchid` scans as one
  identifier, not as the keyword `or` followed by `chid`."
  {"and"    :and
   "class"  :class
   "else"   :else
   "false"  :false
   "for"    :for
   "fun"    :fun
   "if"     :if
   "nil"    :nil
   "or"     :or
   "print"  :print
   "return" :return
   "super"  :super
   "this"   :this
   "true"   :true
   "var"    :var
   "while"  :while})

;;; ---------------------------------------------------------------------------
;;; Challenge 9.3 - the `break` keyword
;;; ---------------------------------------------------------------------------

(def ^:dynamic *allow-break?*
  "Challenge 9.3. When true, `break` scans as the `:break` keyword instead of
  as an identifier, which is what lets the parser recognise a break statement.

  The switch lives here, in the lexical layer, rather than beside the parser's
  other grammar switches, because reserving a word is a *scanning* decision
  and it has to be made before the parser ever sees the token. The parser's
  break production reads this same var - there is one switch, not two that
  could disagree.

  Off by default, for the reason given on `:break` in `token-types`: the
  book's Lox has no `break`, and turning the word into a keyword silently
  breaks any program that used it as a name."
  false)

(def break-keyword
  "The one entry challenge 9.3 adds to the reserved-word table."
  {"break" :break})

(defn keyword-type
  "The token type for an already-scanned identifier `lexeme`: its keyword type
  if it is a reserved word, else `:identifier`.

  Book: the `keywords.get(text)` / `if (type == null) type = IDENTIFIER`
  pair in `Scanner.identifier()`. Lifted into a function of its own in
  chapter 9 so that the reserved-word *set* can depend on a switch while the
  scanner stays a plain table lookup."
  [lexeme]
  (or (get keywords lexeme)
      (when *allow-break?* (get break-keyword lexeme))
      :identifier))

(defn make-token
  "Build a token map. Mirrors the book's `Token` constructor.

  `type`    - a keyword from `token-types`
  `lexeme`  - the exact substring of source this token was scanned from
  `literal` - the runtime value for literal tokens (Double / String), else nil
  `line`    - 1-based source line the lexeme starts on"
  [type lexeme literal line]
  {:pre [(contains? token-types type)]}
  {:type type :lexeme lexeme :literal literal :line line})

(defn token?
  "True if `x` looks like a token map."
  [x]
  (and (map? x) (contains? token-types (:type x))))

(defn type-name
  "Render a token type the way the book's Java enum prints, e.g. :left-paren
  becomes \"LEFT_PAREN\". Used only so our debug output matches the book's."
  [type]
  (-> (name type) (str/replace "-" "_") str/upper-case))

(defn token->string
  "Book: `Token.toString()` - \"TYPE lexeme literal\"."
  [{:keys [type lexeme literal]}]
  (str (type-name type) " " lexeme " " (if (nil? literal) "null" literal)))
