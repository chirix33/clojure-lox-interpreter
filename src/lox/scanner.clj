;;;; =============================================================================
;;;; Chapter 4 - Scanning
;;;;
;;;; "The scanner takes in raw source code as a series of characters and groups
;;;;  it into a series of chunks we call tokens."
;;;;
;;;; This is a direct translation of the book's `Scanner.java`. The Java version
;;;; mutates three fields (`start`, `current`, `line`) plus a token list. Here
;;;; the scanner is a pure function: every helper takes a scanner *state map*
;;;;
;;;;     {:source "..." :tokens [...] :start 0 :current 0 :line 1}
;;;;
;;;; and returns an updated one. `scan-tokens` drives the whole thing with a
;;;; `loop`/`recur`, which is exactly the book's `while (!isAtEnd())` loop.
;;;;
;;;; Correspondence with the book:
;;;;   isAtEnd()    -> at-end?
;;;;   advance()    -> peek-char + advance
;;;;   peek()       -> peek-char
;;;;   peekNext()   -> peek-next
;;;;   match(c)     -> matches?  (a predicate; the caller does the advancing)
;;;;   addToken()   -> add-token
;;;;   string()     -> scan-string
;;;;   number()     -> scan-number
;;;;   identifier() -> scan-identifier
;;;;
;;;; Challenge 4.4 (block comments) is implemented as `scan-block-comment`,
;;;; including nesting. It is a strict superset of the book's grammar.
;;;; =============================================================================
(ns lox.scanner
  (:require [lox.token :as tok]
            [lox.errors :as err]))

;;; ---------------------------------------------------------------------------
;;; Character classification
;;;
;;; The book deliberately avoids Character.isDigit / isLetter because those
;;; accept Devanagari digits, full-width letters and other things Lox does not
;;; want. We spell out the ASCII ranges for the same reason.
;;; ---------------------------------------------------------------------------

(defn digit?
  [c]
  (and (char? c) (<= (int \0) (int c) (int \9))))

(defn alpha?
  [c]
  (and (char? c)
       (or (<= (int \a) (int c) (int \z))
           (<= (int \A) (int c) (int \Z))
           (= c \_))))

(defn alpha-numeric?
  [c]
  (or (alpha? c) (digit? c)))

;;; ---------------------------------------------------------------------------
;;; Scanner state helpers
;;; ---------------------------------------------------------------------------

(defn- new-scanner
  [source]
  {:source source :tokens [] :start 0 :current 0 :line 1})

(defn- at-end?
  "Book: isAtEnd()."
  [{:keys [current source]}]
  (>= current (count source)))

(defn- peek-char
  "Book: peek(). One character of lookahead; nil (rather than NUL) past the end."
  [{:keys [current source]}]
  (when (< current (count source))
    (.charAt ^String source (int current))))

(defn- peek-next
  "Book: peekNext(). Two characters of lookahead - and no more, on purpose."
  [{:keys [current source]}]
  (when (< (inc current) (count source))
    (.charAt ^String source (int (inc current)))))

(defn- advance
  "Book: the current++ half of advance(). Consume one character."
  [s]
  (update s :current inc))

(defn- matches?
  "Book: match(expected), minus the side effect. Callers combine it with
  `advance` so that consumption is always visible at the call site."
  [s expected]
  (and (not (at-end? s)) (= (peek-char s) expected)))

(defn- lexeme
  "Source text of the lexeme being scanned: source[start, current)."
  [{:keys [source start current]}]
  (subs source start current))

(defn- add-token
  "Book: addToken(), both overloads."
  ([s type] (add-token s type nil))
  ([s type literal]
   (update s :tokens conj (tok/make-token type (lexeme s) literal (:line s)))))

(defn- newline!
  [s]
  (update s :line inc))

;;; ---------------------------------------------------------------------------
;;; Longer lexemes
;;; ---------------------------------------------------------------------------

(defn- skip-line-comment
  "`// ...` runs to the end of the line. The newline itself is left unconsumed
  so the main loop sees it and bumps the line counter."
  [s]
  (loop [s s]
    (if (or (at-end? s) (= (peek-char s) \newline))
      s
      (recur (advance s)))))

(defn- scan-block-comment
  "Challenge 4.4: block comments delimited by slash-star / star-slash, nestable.

  We track nesting depth: an opener pushes, a closer pops. Newlines inside
  still bump the line counter. An unterminated block comment is a scan error."
  [s]
  (loop [s s depth 1]
    (cond
      (zero? depth) s

      (at-end? s)
      (do (err/error (:line s) "Unterminated block comment.") s)

      (and (= (peek-char s) \/) (= (peek-next s) \*))
      (recur (advance (advance s)) (inc depth))

      (and (= (peek-char s) \*) (= (peek-next s) \/))
      (recur (advance (advance s)) (dec depth))

      (= (peek-char s) \newline)
      (recur (advance (newline! s)) depth)

      :else
      (recur (advance s) depth))))

(defn- scan-string
  "Book: string(). Lox strings are multi-line and have no escape sequences."
  [s]
  (loop [s s]
    (cond
      (at-end? s)
      (do (err/error (:line s) "Unterminated string.") s)

      (= (peek-char s) \")
      (let [s     (advance s)                        ; the closing quote
            value (subs (:source s) (inc (:start s)) (dec (:current s)))]
        (add-token s :string value))

      :else
      (recur (advance (if (= (peek-char s) \newline) (newline! s) s))))))

(defn- consume-digits
  [s]
  (loop [s s] (if (digit? (peek-char s)) (recur (advance s)) s)))

(defn- scan-number
  "Book: number(). A number is digits, optionally a dot plus more digits.
  Neither a leading nor a trailing dot is part of a number literal."
  [s]
  (let [s (consume-digits s)
        s (if (and (= (peek-char s) \.) (digit? (peek-next s)))
            (consume-digits (advance s))             ; consume the dot
            s)]
    (add-token s :number (Double/parseDouble (lexeme s)))))

(defn- scan-identifier
  "Book: identifier(). Maximal munch first, keyword lookup second."
  [s]
  (let [s (loop [s s] (if (alpha-numeric? (peek-char s)) (recur (advance s)) s))]
    (add-token s (get tok/keywords (lexeme s) :identifier))))

;;; ---------------------------------------------------------------------------
;;; The heart of the scanner
;;; ---------------------------------------------------------------------------

(defn- scan-token
  "Book: scanToken(). Consume exactly one lexeme (or one run of whitespace /
  comment text) and, if it is meaningful, append a token."
  [s]
  (let [c (peek-char s)
        s (advance s)]                               ; always consume: no loops
    (case c
      \( (add-token s :left-paren)
      \) (add-token s :right-paren)
      \{ (add-token s :left-brace)
      \} (add-token s :right-brace)
      \, (add-token s :comma)
      \. (add-token s :dot)
      \- (add-token s :minus)
      \+ (add-token s :plus)
      \; (add-token s :semicolon)
      \* (add-token s :star)

      ;; Operators that may be one or two characters long.
      \! (if (matches? s \=) (add-token (advance s) :bang-equal)    (add-token s :bang))
      \= (if (matches? s \=) (add-token (advance s) :equal-equal)   (add-token s :equal))
      \< (if (matches? s \=) (add-token (advance s) :less-equal)    (add-token s :less))
      \> (if (matches? s \=) (add-token (advance s) :greater-equal) (add-token s :greater))

      ;; Slash is either division, a line comment, or a block comment.
      \/ (cond
           (matches? s \/) (skip-line-comment (advance s))
           (matches? s \*) (scan-block-comment (advance s))
           :else           (add-token s :slash))

      ;; Meaningless whitespace.
      (\space \return \tab) s
      \newline (newline! s)

      \" (scan-string s)

      ;; Numbers, identifiers/keywords, and anything we do not recognise.
      (cond
        (digit? c) (scan-number s)
        (alpha? c) (scan-identifier s)
        :else      (do (err/error (:line s) "Unexpected character.") s)))))

(defn scan-tokens
  "Book: scanTokens(). Scan `source` into a vector of token maps, always
  terminated by an :eof token (which keeps the parser simple).

  Errors are reported through `lox.errors`; scanning continues afterwards so
  the user sees as many problems as possible in one go."
  [source]
  (loop [s (new-scanner source)]
    (if (at-end? s)
      (conj (:tokens s) (tok/make-token :eof "" nil (:line s)))
      (recur (scan-token (assoc s :start (:current s)))))))
