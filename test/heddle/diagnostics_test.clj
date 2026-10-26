(ns heddle.diagnostics-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.diagnostics :as diagnostics]))

(def a "file:///repo/a.rb")

(deftest publishing
  (let [by-file (-> {}
                    (diagnostics/published a "ruby-1" [{"message" "old"}])
                    (diagnostics/published a "ruby-1" [{"message" "unused"}])
                    (diagnostics/published a "lint-1" [{"message" "too long"}]))]
    (testing "keeps each backend's latest, and unites them for the file"
      (is (= #{{"message" "unused"} {"message" "too long"}}
             (set (diagnostics/union-for by-file a)))))
    (testing "finds the files a backend has diagnostics for"
      (is (= [a] (diagnostics/files-from by-file "lint-1")))
      (is (empty? (diagnostics/files-from by-file "types-1"))))
    (testing "withdraws a backend's diagnostics when it publishes none"
      (let [withdrawn (diagnostics/published by-file a "lint-1" [])]
        (is (= [{"message" "unused"}] (diagnostics/union-for withdrawn a)))
        (is (empty? (diagnostics/files-from withdrawn "lint-1")))))
    (testing "forgets the file once no backend has diagnostics for it"
      (is (= {} (-> by-file
                    (diagnostics/published a "lint-1" [])
                    (diagnostics/published a "ruby-1" [])))))
    (testing "has nothing for a file no backend published for"
      (is (= [] (diagnostics/union-for by-file "file:///repo/b.rb"))))))
