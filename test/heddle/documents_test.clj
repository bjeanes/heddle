;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.documents-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.documents :as documents]))

(def a "file:///repo/a.rb")

(def did-open {"textDocument" {"uri" a "languageId" "ruby" "version" 1 "text" "x = 1"}})

(defn- did-change [uri version & texts]
  {"textDocument" {"uri" uri "version" version}
   "contentChanges" (mapv #(hash-map "text" %) texts)})

(defn- position [line character]
  {"line" line "character" character})

(defn- ranged [start-line start-character end-line end-character text]
  {"range" {"start" (position start-line start-character) "end" (position end-line end-character)}
   "text" text})

(deftest opening-and-changing
  (let [opened (documents/opened {} did-open)]
    (testing "an opened document becomes a didOpen again"
      (is (= did-open (documents/open-params a (get opened a)))))
    (testing "a change keeps the latest version and full text"
      (is (= {a {:language "ruby" :version 3 :text "x = 3"}}
             (documents/changed opened (did-change a 3 "x = 2" "x = 3")))))
    (testing "a change to a document that isn't open is ignored"
      (is (= opened (documents/changed opened (did-change "file:///repo/b.rb" 2 "y = 2")))))
    (testing "a ranged change, which the client wasn't asked for, is applied"
      (is (= "x = 42" (get-in (documents/changed opened {"textDocument" {"uri" a "version" 2}
                                                         "contentChanges" [(ranged 0 4 0 5 "42")]})
                              [a :text]))))))
