(ns monorepo-lsp.merge-test
  (:require [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.merge :as merge]))

(deftest combining-completions
  (testing "concatenates items, from lists or bare arrays"
    (is (= {"isIncomplete" false "items" [{"label" "a"} {"label" "b"}]}
           (merge/combine "textDocument/completion"
                          [["x" {"isIncomplete" false "items" [{"label" "a"}]}]
                           ["y" [{"label" "b"}]]]))))
  (testing "is incomplete if any list is"
    (is (true? (get (merge/combine "textDocument/completion"
                                   [["x" [{"label" "a"}]]
                                    ["y" {"isIncomplete" true "items" []}]])
                    "isIncomplete"))))
  (testing "applies each list's item defaults to its own items"
    (let [range {"start" {"line" 0 "character" 0} "end" {"line" 0 "character" 2}}]
      (is (= [{"label" "a" "data" 1 "insertTextFormat" 2
               "textEdit" {"range" range "newText" "a"}}
              {"label" "b" "data" 3 "insertTextFormat" 2 "textEditText" "bee"
               "textEdit" {"range" range "newText" "bee"}}
              {"label" "c"}]
             (get (merge/combine "textDocument/completion"
                                 [["x" {"isIncomplete" false
                                        "itemDefaults" {"data" 1 "insertTextFormat" 2 "editRange" range}
                                        "items" [{"label" "a"} {"label" "b" "data" 3 "textEditText" "bee"}]}]
                                  ["y" [{"label" "c"}]]])
                  "items"))))))

(deftest combining-other-answers
  (testing "concatenates them"
    (is (= [{"title" "a"} {"title" "b"}]
           (merge/combine "textDocument/codeLens" [["x" [{"title" "a"}]] ["y" [{"title" "b"}]]]))))
  (testing "leaves out backends with no answer"
    (is (= [{"title" "b"}]
           (merge/combine "textDocument/codeLens" [["x" nil] ["y" [{"title" "b"}]]]))))
  (testing "is nil when no backend has an answer"
    (is (nil? (merge/combine "textDocument/references" [["x" nil] ["y" nil]])))))
