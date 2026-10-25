(ns heddle.origin-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.origin :as origin]))

(deftest marking-items
  (testing "round trips, with or without data"
    (is (= ["b1" {"title" "a" "data" [1]}] (origin/unmark (origin/mark "b1" {"title" "a" "data" [1]}))))
    (is (= ["b1" {"title" "a"}] (origin/unmark (origin/mark "b1" {"title" "a"})))))
  (testing "leaves items it didn't mark alone"
    (is (nil? (origin/unmark {"title" "a" "data" {"x" 1}})))
    (is (nil? (origin/unmark {"title" "a"})))))

(deftest qualifying-commands
  (testing "qualifies every command, wherever it is"
    (is (= [{"title" "Fix" "command" {"title" "Fix" "command" "lint:fix" "arguments" ["x"]}}
            {"title" "Run" "command" "lint:run"}]
           (origin/qualify-commands "lint" [{"title" "Fix" "command" {"title" "Fix" "command" "fix" "arguments" ["x"]}}
                                            {"title" "Run" "command" "run"}]))))
  (testing "finds the server type a command belongs to"
    (is (= ["lint" "fix"] (origin/command-origin ["ruby" "lint"] "lint:fix")))
    (is (= ["a:b" "c"] (origin/command-origin ["a" "a:b"] "a:b:c")) "by the longest prefix")
    (is (nil? (origin/command-origin ["ruby"] "lint:fix")))))

(deftest marking-results
  (testing "marks code actions but not bare commands, which can't be resolved"
    (is (= [{"title" "fix" "data" {"heddle/backend" "b1"}}
            {"title" "run" "command" "x.run"}]
           (origin/mark-result "textDocument/codeAction" "b1"
                               [{"title" "fix"} {"title" "run" "command" "x.run"}]))))
  (testing "marks the items in a completion list"
    (is (= {"isIncomplete" false
            "items" [{"label" "a" "data" {"heddle/backend" "b1" "data" 7}}]}
           (origin/mark-result "textDocument/completion" "b1"
                               {"isIncomplete" false "items" [{"label" "a" "data" 7}]}))))
  (testing "marks the hierarchy items in calls, so they can be followed further"
    (is (= [{"from" {"name" "a" "data" {"heddle/backend" "b1"}} "fromRanges" []}]
           (origin/mark-result "callHierarchy/incomingCalls" "b1" [{"from" {"name" "a"} "fromRanges" []}])))
    (is (= [{"to" {"name" "a" "data" {"heddle/backend" "b1"}} "fromRanges" []}]
           (origin/mark-result "callHierarchy/outgoingCalls" "b1" [{"to" {"name" "a"} "fromRanges" []}]))))
  (testing "marks a resolved item"
    (is (= {"title" "l" "data" {"heddle/backend" "b1"}}
           (origin/mark-result "codeLens/resolve" "b1" {"title" "l"}))))
  (testing "leaves nil and other results alone"
    (is (nil? (origin/mark-result "textDocument/codeLens" "b1" nil)))
    (is (= {"contents" "x"} (origin/mark-result "textDocument/hover" "b1" {"contents" "x"})))))
