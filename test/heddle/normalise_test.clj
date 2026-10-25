(ns heddle.normalise-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.normalise :as normalise]))

(deftest normalising-completions
  (testing "makes a bare array of items a list"
    (is (= {"isIncomplete" false "items" [{"label" "a"}]}
           (normalise/result "textDocument/completion" [{"label" "a"}]))))
  (testing "applies the list's item defaults to its items"
    (let [range {"start" {"line" 0 "character" 0} "end" {"line" 0 "character" 2}}]
      (is (= {"isIncomplete" false
              "items" [{"label" "a" "data" 1 "insertTextFormat" 2
                        "textEdit" {"range" range "newText" "a"}}
                       {"label" "b" "data" 3 "insertTextFormat" 2 "textEditText" "bee"
                        "textEdit" {"range" range "newText" "bee"}}]}
             (normalise/result "textDocument/completion"
                               {"isIncomplete" false
                                "itemDefaults" {"data" 1 "insertTextFormat" 2 "editRange" range}
                                "items" [{"label" "a"} {"label" "b" "data" 3 "textEditText" "bee"}]})))))
  (testing "leaves nil alone"
    (is (nil? (normalise/result "textDocument/completion" nil)))))

(deftest normalising-other-results
  (is (= {"contents" "x"} (normalise/result "textDocument/hover" {"contents" "x"}))))
