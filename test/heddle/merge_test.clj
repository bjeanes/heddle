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
      "concatenates the items for each file, and drops the backends' result IDs")
  (is (= {"kind" "full" "items" [{"message" "a"} {"message" "b"}]}
         (merge/combine "textDocument/diagnostic"
                        [["x" {"kind" "full" "items" [{"message" "a"}]}]
                         ["x" {"kind" "full" "items" [{"message" "a"} {"message" "b"}]}]]))
      "has a diagnostic that several backends gave once"))

(deftest combining-other-answers
  (testing "concatenates them"
    (is (= [{"title" "a"} {"title" "b"}]
           (merge/combine "textDocument/codeLens" [["x" [{"title" "a"}]] ["y" [{"title" "b"}]]]))))
  (testing "leaves out backends with no answer"
    (is (= [{"title" "b"}]
           (merge/combine "textDocument/codeLens" [["x" nil] ["y" [{"title" "b"}]]]))))
  (testing "is nil when no backend has an answer"
    (is (nil? (merge/combine "textDocument/references" [["x" nil] ["y" nil]])))))

(deftest combining-the-same-things
  (let [a {"uri" "file:///a.rb" "range" {"start" {"line" 1 "character" 0} "end" {"line" 1 "character" 3}}}
        b (assoc-in a ["range" "start" "line"] 2)]
    (testing "keeps the first of each location"
      (is (= [a b] (merge/combine "textDocument/references" [["x" [a]] ["y" [a b]]])))))
  (let [location {"uri" "file:///shared/gem.rb"
                  "range" {"start" {"line" 1 "character" 0} "end" {"line" 1 "character" 3}}}
        symbol {"name" "Gem" "kind" 5 "location" location}
        x {"heddle/backend" "x"}
        y {"heddle/backend" "y"}]
    (testing "keeps the first of each symbol, whatever its container or data"
      (is (= [(assoc symbol "containerName" "A" "data" x)]
             (merge/combine "workspace/symbol"
                            [["ruby-lsp" [(assoc symbol "containerName" "A" "data" x)]]
                             ["ruby-lsp" [(assoc symbol "containerName" "B" "data" y)]]]))))
    (testing "keeps symbols that differ in name, kind or location"
      (let [others [(assoc symbol "name" "Other")
                    (assoc symbol "kind" 12)
                    (assoc-in symbol ["location" "uri"] "file:///other.rb")
                    (assoc-in symbol ["location" "range" "start" "line"] 2)]]
        (is (= (into [symbol] others)
               (merge/combine "workspace/symbol" [["x" [symbol]] ["y" (conj others symbol)]])))))
    (testing "keeps every symbol whose location has no range"
      (let [in-file (update symbol "location" dissoc "range")]
        (is (= [in-file in-file]
               (merge/combine "workspace/symbol" [["x" [in-file]] ["y" [in-file]]])))))))
