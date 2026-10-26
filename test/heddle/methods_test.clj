(ns heddle.methods-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.merge :as merge]
            [heddle.methods :as methods]))

(defn- described-with [k]
  (filter #(contains? (val %) k) methods/table))

(deftest describing-methods
  (testing "uses only the keys it documents"
    (doseq [[method description] methods/table
            k (keys description)]
      (is (contains? methods/description-keys k) (str method " has " k))))
  (testing "points only at requests that offer a feature"
    (doseq [k [:feature :follows :resolves]
            [method description] (described-with k)]
      (is (:provider (get methods/table (get description k)))
          (str method "'s " k " offers no feature"))))
  (testing "marks the items that a later request will be about"
    (doseq [k [:follows :resolves]
            [method description] (described-with k)]
      (is (:items (get methods/table (get description k)))
          (str (get description k) " doesn't mark the items " method " is about"))
      (is (:items description)
          (str method " doesn't mark its items, to be followed or resolved again"))))
  (testing "qualifies commands in a resolved item if the list it came from does"
    (doseq [[method {:keys [resolves commands?]}] (described-with :resolves)]
      (is (= (boolean commands?) (boolean (get-in methods/table [resolves :commands?])))
          method)))
  (testing "registers, announces and combines only requests that offer a feature"
    (doseq [k [:client :static :combine]
            [method description] (described-with k)]
      (is (:provider description) (str method " has " k " but offers no feature"))))
  (testing "dedupes only concatenated answers"
    (doseq [[method description] (described-with :dedupe-by)]
      (is (= :concat (:combine description)) method)))
  (testing "combines answers only in ways heddle.merge knows"
    (doseq [[method] (described-with :combine)]
      (is (some? (merge/combine method [["x" []]])) method))))
