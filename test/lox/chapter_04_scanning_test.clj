;;;; =============================================================================
;;;; Chapter 4 - Scanning : unit tests
;;;;
;;;; Exercises `lox.scanner` and `lox.token`:
;;;;   * every single- and double-character operator, including maximal munch
;;;;   * whitespace, line counting, line comments, block comments (challenge 4.4)
;;;;   * string literals (incl. empty, multi-line, unterminated)
;;;;   * number literals (incl. the ".5" / "5." edge cases)
;;;;   * identifiers vs. the 16 reserved words
;;;;   * lexical errors, and the fact that scanning continues after one
;;;; =============================================================================
(ns lox.chapter-04-scanning-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures]]
            [clojure.string :as str]
            [lox.test-util :as tu]
            [lox.errors :as err]
            [lox.token :as tok]
            [lox.scanner :as scanner]))

(use-fixtures :each tu/quiet-errors)

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- types
  "Token types produced for `src`, excluding the trailing :eof."
  [src]
  (mapv :type (butlast (scanner/scan-tokens src))))

(defn- lexemes
  [src]
  (mapv :lexeme (butlast (scanner/scan-tokens src))))

(defn- literals
  [src]
  (mapv :literal (butlast (scanner/scan-tokens src))))

(defn- lines
  [src]
  (mapv :line (butlast (scanner/scan-tokens src))))

(defn- only-token
  [src]
  (first (scanner/scan-tokens src)))

;;; ---------------------------------------------------------------------------
;;; The framework around the scanner
;;; ---------------------------------------------------------------------------

(deftest eof-token-always-appended
  (testing "an empty source still produces exactly one token: EOF"
    (let [ts (scanner/scan-tokens "")]
      (is (= 1 (count ts)))
      (is (= :eof (:type (first ts))))
      (is (= "" (:lexeme (first ts))))
      (is (nil? (:literal (first ts))))
      (is (= 1 (:line (first ts))))))

  (testing "EOF carries the final line number"
    (is (= 3 (:line (last (scanner/scan-tokens "1\n2\n3")))))
    (is (= 4 (:line (last (scanner/scan-tokens "1\n2\n3\n")))))))

(deftest whitespace-only-source
  (are [src] (= [] (types src))
    "   "
    "\t\t"
    "\r\n"
    "  \t \r\n  \n "))

;;; ---------------------------------------------------------------------------
;;; 4.5 Recognizing lexemes - single characters
;;; ---------------------------------------------------------------------------

(deftest single-character-tokens
  (is (= [:left-paren :right-paren :left-brace :right-brace
          :comma :dot :minus :plus :semicolon :star]
         (types "(){},.-+;*")))
  (testing "each on its own"
    (are [src type] (= [type] (types src))
      "(" :left-paren
      ")" :right-paren
      "{" :left-brace
      "}" :right-brace
      "," :comma
      "." :dot
      "-" :minus
      "+" :plus
      ";" :semicolon
      "*" :star
      "/" :slash)))

(deftest lexemes-are-the-exact-source-text
  (is (= ["(" ")" "{" "}"] (lexemes "(){}"))))

;;; ---------------------------------------------------------------------------
;;; 4.5.2 Operators - one or two characters
;;; ---------------------------------------------------------------------------

(deftest one-or-two-character-operators
  (are [src type] (= [type] (types src))
    "!"  :bang
    "!=" :bang-equal
    "="  :equal
    "==" :equal-equal
    "<"  :less
    "<=" :less-equal
    ">"  :greater
    ">=" :greater-equal))

(deftest operators-are-munched-maximally
  (testing "== is one token, not two ="
    (is (= [:equal-equal] (types "=="))))
  (testing "=== is == followed by ="
    (is (= [:equal-equal :equal] (types "==="))))
  (testing "!!= is ! followed by !="
    (is (= [:bang :bang-equal] (types "!!="))))
  (testing "a mixed operator soup"
    (is (= [:bang :star :plus :minus :slash :equal :less :greater
            :less-equal :equal-equal]
           (types "!*+-/=<> <= ==")))))

;;; ---------------------------------------------------------------------------
;;; 4.6 Comments
;;; ---------------------------------------------------------------------------

(deftest line-comments-are-discarded
  (is (= [] (types "// just a comment")))
  (is (= [:left-paren :right-paren] (types "(  ) // grouping stuff")))
  (is (= [:var :identifier :equal :number :semicolon]
         (types "var a = 1; // trailing")))
  (testing "a comment ends at the newline, not at end of file"
    (is (= [:print] (types "// nope\nprint")))))

(deftest slash-is-division-when-not-doubled
  (is (= [:number :slash :number] (types "6 / 2"))))

(deftest block-comments-challenge-4-4
  (testing "a simple block comment is discarded"
    (is (= [:number] (types "/* ignored */ 1"))))
  (testing "block comments may span lines and keep the line count honest"
    (is (= [2] (lines "/* a\nb */1")))
    (is (= [3] (lines "/* a\nb\n*/ 1"))))
  (testing "block comments nest"
    (is (= [:number] (types "/* outer /* inner */ still outer */ 1")))
    (is (= [:number] (types "/*/**/*/1"))))
  (testing "code on both sides survives"
    (is (= [:number :plus :number] (types "1 /* + 9 */ + 2"))))
  (testing "an unterminated block comment is an error"
    (tu/scan "/* never closed")
    (is @err/had-error)
    (is (= ["Unterminated block comment."] (err/error-messages)))))

;;; ---------------------------------------------------------------------------
;;; 4.6.1 String literals
;;; ---------------------------------------------------------------------------

(deftest string-literals
  (testing "the literal value has the quotes stripped"
    (let [t (only-token "\"hello\"")]
      (is (= :string (:type t)))
      (is (= "\"hello\"" (:lexeme t)))
      (is (= "hello" (:literal t)))))

  (testing "the empty string"
    (is (= [""] (literals "\"\""))))

  (testing "strings may contain anything but a quote"
    (is (= ["// not a comment"] (literals "\"// not a comment\"")))
    (is (= ["a + b * c"]        (literals "\"a + b * c\"")))
    (is (= ["123"]              (literals "\"123\""))))

  (testing "Lox has no escape sequences: a backslash is just a character"
    (is (= ["a\\nb"] (literals "\"a\\nb\""))))

  (testing "strings are multi-line, and the token reports the closing line"
    (let [t (only-token "\"one\ntwo\"")]
      (is (= "one\ntwo" (:literal t)))
      (is (= 2 (:line t)))))

  (testing "two adjacent strings are two tokens"
    (is (= ["a" "b"] (literals "\"a\" \"b\"")))))

(deftest unterminated-string-is-an-error
  (tu/scan "\"no end")
  (is @err/had-error)
  (is (= ["Unterminated string."] (err/error-messages)))
  (is (= ["[line 1] Error: Unterminated string."] (err/error-texts))))

(deftest unterminated-multiline-string-reports-the-last-line
  (tu/scan "\"a\nb\nc")
  (is (= ["[line 3] Error: Unterminated string."] (err/error-texts))))

;;; ---------------------------------------------------------------------------
;;; 4.6.2 Number literals
;;; ---------------------------------------------------------------------------

(deftest number-literals
  (testing "all Lox numbers are doubles"
    (is (= [1234.0] (literals "1234")))
    (is (= [12.34]  (literals "12.34")))
    (is (= [0.0]    (literals "0")))
    (is (every? double? (literals "1 2.5 300"))))

  (testing "the lexeme keeps the original spelling"
    (is (= ["12.34"] (lexemes "12.34"))))

  (testing "a leading dot is not part of the number"
    (is (= [:dot :number] (types ".1234")))
    (is (= [nil 1234.0] (literals ".1234"))))

  (testing "a trailing dot is not part of the number"
    (is (= [:number :dot] (types "1234.")))
    (is (= [1234.0 nil] (literals "1234."))))

  (testing "method-call syntax on a number literal stays parseable"
    (is (= [:number :dot :identifier :left-paren :right-paren]
           (types "123.abs()"))))

  (testing "negation is a separate token, not part of the literal"
    (is (= [:minus :number] (types "-123")))))

;;; ---------------------------------------------------------------------------
;;; 4.7 Identifiers and reserved words
;;; ---------------------------------------------------------------------------

(deftest identifiers
  (are [src] (= [:identifier] (types src))
    "a" "abc" "_" "_x" "x1" "A_B_9" "orchid" "classy" "iffy" "forth"
    "andy" "truelove" "printer" "returned" "superb" "thistle" "variable"
    "whiles" "nile" "fund" "elsewhere")
  (is (= ["orchid"] (lexemes "orchid"))))

(deftest reserved-words
  (are [src type] (= [type] (types src))
    "and"    :and
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
    "while"  :while)
  (testing "every keyword in the table is covered by the scanner"
    (is (= 16 (count tok/keywords)))
    (is (= (set (vals tok/keywords))
           (set (types (str/join " " (keys tok/keywords))))))))

(deftest keywords-are-case-sensitive
  (is (= [:identifier] (types "Var")))
  (is (= [:identifier] (types "PRINT"))))

(deftest maximal-munch-applies-to-keywords
  (testing "orchid is one identifier, not `or` + `chid`"
    (is (= [:identifier] (types "orchid"))))
  (testing "but `or chid` is a keyword and an identifier"
    (is (= [:or :identifier] (types "or chid")))))

;;; ---------------------------------------------------------------------------
;;; 4.5.1 Lexical errors
;;; ---------------------------------------------------------------------------

(deftest unexpected-characters-are-reported
  (tu/scan "@")
  (is @err/had-error)
  (is (= ["[line 1] Error: Unexpected character."] (err/error-texts))))

(deftest scanning-continues-after-a-lexical-error
  (testing "each bad character is reported separately and good ones still scan"
    (let [ts (tu/scan "@#^ var")]
      (is (= 3 (count (err/error-texts))))
      (is (= [:var] (mapv :type (butlast ts)))))))

(deftest errors-report-the-right-line
  (tu/scan "var a;\nvar b;\n@")
  (is (= ["[line 3] Error: Unexpected character."] (err/error-texts))))

;;; ---------------------------------------------------------------------------
;;; Line tracking
;;; ---------------------------------------------------------------------------

(deftest line-numbers
  (is (= [1 2 3] (lines "a\nb\nc")))
  (is (= [1 1 2] (lines "a b\nc")))
  (testing "carriage returns do not add lines"
    (is (= [1 2] (lines "a\r\nb"))))
  (testing "newlines inside strings count"
    (is (= [2 2] (lines "\"x\ny\" z"))))
  (testing "newlines inside block comments count"
    (is (= [4] (lines "/*\n\n\n*/ a")))))

;;; ---------------------------------------------------------------------------
;;; Whole-program smoke test
;;; ---------------------------------------------------------------------------

(deftest scans-a-realistic-program
  (let [src "// Compute a factorial.\nfun fact(n) {\n  if (n <= 1) return 1;\n  return n * fact(n - 1);\n}\nprint fact(5); // 120\n"
        ts  (tu/scan src)]
    (is (not @err/had-error))
    (is (= [:fun :identifier :left-paren :identifier :right-paren :left-brace
            :if :left-paren :identifier :less-equal :number :right-paren
            :return :number :semicolon
            :return :identifier :star :identifier :left-paren :identifier
            :minus :number :right-paren :semicolon
            :right-brace
            :print :identifier :left-paren :number :right-paren :semicolon
            :eof]
           (mapv :type ts)))
    (is (= 7 (:line (last ts))))))

;;; ---------------------------------------------------------------------------
;;; Token helpers
;;; ---------------------------------------------------------------------------

(deftest token-construction-and-printing
  (let [t (tok/make-token :left-paren "(" nil 1)]
    (is (tok/token? t))
    (is (= "LEFT_PAREN ( null" (tok/token->string t)))
    (is (= "LEFT_PAREN" (tok/type-name :left-paren)))
    (is (= "BANG_EQUAL" (tok/type-name :bang-equal))))
  (testing "an unknown token type is rejected"
    (is (thrown? AssertionError (tok/make-token :not-a-real-type "x" nil 1)))))
