;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.lsp.text-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.lsp.text :as text]))

(defn- position [line character]
  {"line" line "character" character})

(defn- ranged [start-line start-character end-line end-character text]
  {"range" {"start" (position start-line start-character) "end" (position end-line end-character)}
   "text" text})

(defn- replacing
  "A change of the whole of a text that ends at `end-line` and
  `end-character`."
  [end-line end-character text]
  (ranged 0 0 end-line end-character text))

(deftest content-changes-for-an-incremental-server
  (let [incremental #(text/content-changes 2 %1 (mapv (fn [text] {"text" text}) %&))]
    (testing "replace the whole of the previous text"
      (is (= [(replacing 0 0 "a")] (incremental "" "a")) "when it was empty")
      (is (= [(replacing 1 0 "a")] (incremental "x = 1\n" "a")) "up to the line after a trailing newline")
      (is (= [(replacing 2 1 "a")] (incremental "x\r\ny\rz" "a")) "with lines ending in CRLF or CR")
      (is (= [(replacing 0 4 "a")] (incremental "a😀b" "a")) "counting a character above U+FFFF as two units"))
    (testing "each full change replaces the text before it"
      (is (= [(replacing 0 1 "bb") (replacing 0 2 "c")] (incremental "x" "bb" "c"))))
    (testing "ranged changes pass through"
      (is (= [(ranged 0 0 0 0 "x") (replacing 0 3 "ab") (ranged 0 2 0 2 "c")]
             (text/content-changes 2 "😀" [(ranged 0 0 0 0 "x") {"text" "ab"} (ranged 0 2 0 2 "c")])))
      (is (= [(ranged 0 0 0 0 "x")]
             (text/content-changes 2 "y" [(ranged 0 0 0 0 "x")]))))))

(deftest content-changes-for-other-servers
  (testing "a server that syncs in full gets the full text unchanged"
    (is (= [{"text" "b"}] (text/content-changes 1 "a" [{"text" "b"}]))))
  (testing "a server that syncs in full gets ranged changes applied to the text"
    (is (= [{"text" "a😀\r\nxyz"}]
           (text/content-changes 1 "a😀\r\nxz" [(ranged 1 1 1 1 "y")])))
    (is (= [{"text" "a😀!"}]
           (text/content-changes 1 "a😀b" [(ranged 0 3 0 4 "!")]))))
  (testing "a server that wants no changes gets none"
    (is (nil? (text/content-changes 0 "a" [{"text" "b"}])))))
