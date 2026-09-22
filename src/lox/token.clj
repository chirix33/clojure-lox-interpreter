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
