;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.requests-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.requests :as requests]))

(deftest request-tables
  (let [table (-> requests/empty-table
                  (requests/add :a identity)
                  (requests/add :b inc)
                  (requests/add :a dec))]
    (testing "number requests from 1"
      (is (= 3 (:last-id table))))
    (testing "find a request's callback by its ID"
      (is (= inc (requests/on-response table 2)))
      (is (nil? (requests/on-response table 4))))
    (testing "find open requests by their tag"
      (is (= #{1 3} (set (requests/tagged table :a)))))
    (testing "forget a request once it's answered, without reusing its ID"
      (let [table (requests/answered table 3)]
        (is (nil? (requests/on-response table 3)))
        (is (= [1] (requests/tagged table :a)))
        (is (= 4 (:last-id (requests/add table nil identity))))))))
