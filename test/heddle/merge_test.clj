(ns heddle.merge-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.merge :as merge]))

(deftest combining-completions
  (testing "concatenates items"
    (is (= {"isIncomplete" false "items" [{"label" "a"} {"label" "b"}]}
           (merge/combine "textDocument/completion"
                          [["x" {"isIncomplete" false "items" [{"label" "a"}]}]
                           ["y" {"isIncomplete" false "items" [{"label" "b"}]}]]))))
  (testing "is incomplete if any list is"
    (is (true? (get (merge/combine "textDocument/completion"
                                   [["x" {"isIncomplete" false "items" [{"label" "a"}]}]
                                    ["y" {"isIncomplete" true "items" []}]])
                    "isIncomplete")))))

(deftest combining-code-actions
  (testing "names each action's server type when more than one offers any"
    (is (= [{"title" "[x] Fix"} {"title" "[y] Fix" "command" "y.fix"}]
           (merge/combine "textDocument/codeAction"
                          [["x" [{"title" "Fix"}]] ["y" [{"title" "Fix" "command" "y.fix"}]]]))))
  (testing "leaves titles alone when only one does"
    (is (= [{"title" "Fix"}]
           (merge/combine "textDocument/codeAction" [["x" []] ["y" [{"title" "Fix"}]] ["z" nil]])))))

(deftest combining-diagnostic-reports
  (is (= {"kind" "full"
          "items" [{"message" "a"} {"message" "b"}]
          "relatedDocuments" {"file:///r" {"kind" "full" "items" [{"message" "c"} {"message" "d"}]}
                              "file:///s" {"kind" "full" "items" [{"message" "e"}]}}}
         (merge/combine "textDocument/diagnostic"
                        [["x" {"kind" "full" "resultId" "1" "items" [{"message" "a"}]
                               "relatedDocuments" {"file:///r" {"kind" "full" "items" [{"message" "c"}]}}}]
                         ["y" {"kind" "full" "resultId" "2" "items" [{"message" "b"}]
                               "relatedDocuments" {"file:///r" {"kind" "full" "items" [{"message" "d"}]}
                                                   "file:///s" {"kind" "full" "items" [{"message" "e"}]}}}]]))
      "concatenates the items for each file, and drops the backends' result IDs"))

(deftest combining-other-answers
  (testing "concatenates them"
    (is (= [{"title" "a"} {"title" "b"}]
           (merge/combine "textDocument/codeLens" [["x" [{"title" "a"}]] ["y" [{"title" "b"}]]]))))
  (testing "leaves out backends with no answer"
    (is (= [{"title" "b"}]
           (merge/combine "textDocument/codeLens" [["x" nil] ["y" [{"title" "b"}]]]))))
  (testing "is nil when no backend has an answer"
    (is (nil? (merge/combine "textDocument/references" [["x" nil] ["y" nil]])))))
