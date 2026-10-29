;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.toml-writer-test
  (:require [clj-toml.core :as toml]
            [clojure.test :refer [are deftest is]]
            [heddle.toml-writer :as w]))

(deftest escapes-strings
  (are [s toml] (= toml (w/string s))
    "plain" "\"plain\""
    "say \"hi\"" "\"say \\\"hi\\\"\""
    "C:\\dir" "\"C:\\\\dir\""
    "\b\t\n\f\r" "\"\\b\\t\\n\\f\\r\""
    "\u0000\u0001\u001f" "\"\\u0000\\u0001\\u001F\""
    "\u007f" "\"\\u007F\""
    "é 😀 {cfg:x}" "\"é 😀 {cfg:x}\""))

(deftest quotes-keys-only-when-they-need-it
  (are [k toml] (= toml (w/key-str k))
    "ruby-lsp" "ruby-lsp"
    "A_z-09" "A_z-09"
    "." "\".\""
    "services/api" "\"services/api\""
    "a b" "\"a b\""
    "" "\"\""
    "é" "\"é\"")
  (is (= "scopes.\"services/api\".ruby-lsp" (w/dotted-key ["scopes" "services/api" "ruby-lsp"]))))

(deftest writes-values-on-one-line
  (are [v toml] (= toml (w/value-str v))
    true "true"
    false "false"
    -12 "-12"
    1.5 "1.5"
    1e20 "1.0E20"
    ##Inf "inf"
    ##-Inf "-inf"
    ##NaN "nan"
    [] "[]"
    ["a" 1 [true]] "[\"a\", 1, [true]]"
    {} "{}"
    {"." "/src" "a b" {"c" 1}} "{ \".\" = \"/src\", \"a b\" = { c = 1 } }")
  (is (thrown? clojure.lang.ExceptionInfo (w/value-str (get (toml/parse-string "d = 1979-05-27") "d")))))

(deftest writes-tables-with-aligned-comments
  (is (= (str "# above\n"
              "[a.\"b c\"]\n"
              "x = 1    # one\n"
              "# a note\n"
              "yy = 22  # two\n"
              "z = 3")
         (w/write [{:path ["a" "b c"]
                    :comments ["above"]
                    :rows [["x" 1 "one"] "a note" ["yy" 22 "two"] ["z" 3]]}])))
  (is (= "x = [\"a long value that runs past where comments line up\"]  # c"
         (w/write [{:path [] :rows [["x" ["a long value that runs past where comments line up"] "c"]]}]))))

(def ^:private values
  [{"s" "plain"}
   {"s" "\"\\\b\t\n\f\r é 😀 \u0000\u0001\u001f\u007f"}
   {"n" 0 "neg" -42 "big" 9007199254740993 "t" true "f" false}
   {"x" 1.5 "e" 1e-7 "inf" ##Inf "-inf" ##-Inf}
   {"l" [] "ls" ["a" "b"] "nested" [[1 2] ["x"]]}
   {"t" {}}
   {"a" {"b" {"c" {"d" 1}}} "top" "level"}
   {"scopes" {"." {"ruby-lsp" {"paths" {"." "/src"} "runner" "r"}}
              "services/api" {"ruby-lsp" {}}
              "a \"quoted\"\tkey" {"x" {"" ""}}}}
   {"mixed" {"v" 1 "sub" {"w" 2} "empty" {}}}])

(deftest round-trips-through-clj-toml
  (doseq [v values]
    (is (= v (toml/parse-string (w/write (w/tables v)))) (pr-str v))))
