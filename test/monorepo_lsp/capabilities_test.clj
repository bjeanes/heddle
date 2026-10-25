(ns monorepo-lsp.capabilities-test
  (:require [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.capabilities :as capabilities]))

(def ^:private registers-hover
  {"textDocument" {"hover" {"dynamicRegistration" true}
                   "definition" {"dynamicRegistration" false}}})

(deftest initialize-result
  (let [caps (get (capabilities/initialize-result registers-hover) "capabilities")]
    (testing "syncs every file in full, in UTF-16"
      (is (= {"openClose" true "change" 1 "save" true} (get caps "textDocumentSync")))
      (is (= "utf-16" (get caps "positionEncoding"))))
    (testing "announces only the features the client can't register later"
      (is (not (contains? caps "hoverProvider")))
      (is (= true (get caps "definitionProvider")))
      (is (= {} (get caps "completionProvider"))))
    (testing "leaves out features that need a backend's options"
      (is (not (contains? caps "semanticTokensProvider")))
      (is (not (contains? caps "documentOnTypeFormattingProvider"))))))

(deftest registrations
  (let [selector [{"language" "ruby"}]
        server-caps {"hoverProvider" {"workDoneProgress" true}
                     "definitionProvider" true
                     "referencesProvider" false}
        client-caps (assoc-in registers-hover ["textDocument" "references" "dynamicRegistration"] true)]
    (is (= [{"id" "b1:textDocument/hover"
             "method" "textDocument/hover"
             "registerOptions" {"workDoneProgress" true "documentSelector" selector}}]
           (capabilities/registrations client-caps server-caps selector "b1"))
        "only what the backend offers and the client can register")))

(deftest features-by-method
  (is (= "hoverProvider" (:provider (capabilities/feature "textDocument/hover"))))
  (is (= "semanticTokensProvider" (:provider (capabilities/feature "textDocument/semanticTokens/full"))))
  (is (nil? (capabilities/feature "rubyLsp/workspace/dependencies")))
  (testing "offered unless absent or false"
    (let [hover (capabilities/feature "textDocument/hover")]
      (is (capabilities/offers? {"hoverProvider" {}} hover))
      (is (not (capabilities/offers? {"hoverProvider" false} hover)))
      (is (not (capabilities/offers? {} hover))))))

(deftest empty-results
  (is (nil? (capabilities/empty-result "textDocument/hover")))
  (is (= {"kind" "full" "items" []} (capabilities/empty-result "textDocument/diagnostic"))))
