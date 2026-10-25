(ns monorepo-lsp.origin-test
  (:require [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.origin :as origin]))

(deftest marking-items
  (testing "round trips, with or without data"
    (is (= ["b1" {"title" "a" "data" [1]}] (origin/unmark (origin/mark "b1" {"title" "a" "data" [1]}))))
    (is (= ["b1" {"title" "a"}] (origin/unmark (origin/mark "b1" {"title" "a"})))))
  (testing "leaves items it didn't mark alone"
    (is (nil? (origin/unmark {"title" "a" "data" {"x" 1}})))
    (is (nil? (origin/unmark {"title" "a"})))))

(deftest marking-results
  (testing "marks code actions but not bare commands, which can't be resolved"
    (is (= [{"title" "fix" "data" {"monorepo-lsp/backend" "b1"}}
            {"title" "run" "command" "x.run"}]
           (origin/mark-result "textDocument/codeAction" "b1"
                               [{"title" "fix"} {"title" "run" "command" "x.run"}]))))
  (testing "marks completion items with the data their list's defaults give them"
    (is (= {"isIncomplete" false
            "items" [{"label" "a" "data" {"monorepo-lsp/backend" "b1" "data" 7}}]}
           (origin/mark-result "textDocument/completion" "b1"
                               {"isIncomplete" false "itemDefaults" {"data" 7} "items" [{"label" "a"}]}))))
  (testing "marks a resolved item"
    (is (= {"title" "l" "data" {"monorepo-lsp/backend" "b1"}}
           (origin/mark-result "codeLens/resolve" "b1" {"title" "l"}))))
  (testing "leaves nil and other results alone"
    (is (nil? (origin/mark-result "textDocument/codeLens" "b1" nil)))
    (is (= {"contents" "x"} (origin/mark-result "textDocument/hover" "b1" {"contents" "x"})))))
