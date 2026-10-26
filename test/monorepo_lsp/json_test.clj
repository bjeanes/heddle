(ns monorepo-lsp.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.json :as json]))

(deftest reads-values
  (is (= {"a" [1 2.5 nil true false "x"] "b" {}}
         (json/read-str " {\"a\": [1, 2.5, null, true, false, \"x\"], \"b\": {}} ")))
  (is (= [] (json/read-str "[]")))
  (is (= -12 (json/read-str "-12")))
  (is (= 1.0E-5 (json/read-str "1e-5"))))

(deftest reads-escapes
  (is (= "\"\\/\b\f\n\r\t" (json/read-str "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")))
  (is (= "é" (json/read-str "\"\\u00e9\""))))

(deftest surrogate-pairs
  (testing "an escaped pair becomes one code point"
    (is (= "a😀b" (json/read-str "\"a\\ud83d\\ude00b\"")))
    (is (= 3 (count (json/read-str "\"a\\ud83d\\ude00b\"")))))
  (testing "a lone surrogate becomes U+FFFD"
    (is (= "\uFFFD" (json/read-str "\"\\ud83d\"")))
    (is (= "\uFFFDx" (json/read-str "\"\\ude00x\"")))
    (is (= "\uFFFDA" (json/read-str "\"\\ud83d\\u0041\"")) "high surrogate followed by a non-surrogate escape")))

(deftest writes-raw-utf8
  (is (= "{\"t\":\"a😀b é\"}" (json/write-str {"t" "a😀b é"})))
  (is (= "\"\\u0001\\n\\\"\"" (json/write-str "\u0001\n\""))))

(deftest round-trips
  (let [v {"jsonrpc" "2.0" "id" 1 "params" {"uri" "file:///x/😀.rb" "items" [{} [] nil 0.5]}}]
    (is (= v (json/read-str (json/write-str v))))))

(deftest rejects-invalid-input
  (doseq [bad ["" "{" "[1,]" "{\"a\" 1}" "\"abc" "tru" "1 2" "\"\\x\""]]
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str bad)) bad)))
