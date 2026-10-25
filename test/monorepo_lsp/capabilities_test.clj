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
    (testing "always announces workspace symbol search, which goes to every backend"
      (is (= {"resolveProvider" true} (get caps "workspaceSymbolProvider"))))
    (testing "leaves out features that need a backend's options"
      (is (not (contains? caps "semanticTokensProvider")))
      (is (not (contains? caps "documentOnTypeFormattingProvider"))))))

(deftest initialize-result-with-backends-started
  (let [caps #(get (capabilities/initialize-result registers-hover %) "capabilities")
        legend (fn [& types] {"tokenTypes" (vec types) "tokenModifiers" []})]
    (testing "announces the features they offer, with their options combined"
      (let [c (caps [[{"hoverProvider" true
                       "executeCommandProvider" {"commands" ["a"]}
                       "documentOnTypeFormattingProvider" {"firstTriggerCharacter" "\n"}}
                      [{"language" "ruby"}]]
                     [{"executeCommandProvider" {"commands" ["b"]}} [{"language" "erb"}]]])]
        (is (= {} (get c "hoverProvider")))
        (is (= {"commands" ["a" "b"]} (get c "executeCommandProvider")))
        (is (= {"firstTriggerCharacter" "\n"} (get c "documentOnTypeFormattingProvider")))
        (testing "which are then never registered later"
          (is (contains? (capabilities/announced-methods c) "textDocument/hover"))
          (is (contains? (capabilities/announced-methods c) "workspace/executeCommand")))))
    (testing "announces semantic tokens only if they share one legend"
      (is (= {"legend" (legend "type")}
             (get (caps [[{"semanticTokensProvider" {"legend" (legend "type")}} []]
                         [{"semanticTokensProvider" {"legend" (legend "type")}} []]])
                  "semanticTokensProvider")))
      (is (not (contains? (caps [[{"semanticTokensProvider" {"legend" (legend "type")}} []]
                                 [{"semanticTokensProvider" {"legend" (legend "class")}} []]])
                          "semanticTokensProvider"))))))

(deftest registrations
  (let [selector [{"language" "ruby"}]
        server-caps {"hoverProvider" {"workDoneProgress" true}
                     "definitionProvider" true
                     "referencesProvider" false}
        client-caps (assoc-in registers-hover ["textDocument" "references" "dynamicRegistration"] true)]
    (is (= [{"id" "textDocument/hover"
             "method" "textDocument/hover"
             "registerOptions" {"workDoneProgress" true "documentSelector" selector}}]
           (capabilities/registrations client-caps [[server-caps selector]]))
        "only what a backend offers and the client can register")))

(deftest registrations-for-several-backends
  (let [client-caps {"textDocument" {"hover" {"dynamicRegistration" true}
                                     "completion" {"dynamicRegistration" true}}}
        options #(get-in (into {} (map (juxt (fn [r] (get r "method")) identity))
                               (capabilities/registrations client-caps %))
                         [%2 "registerOptions"])]
    (testing "one for each feature, covering every offering backend's files"
      (is (= [{"id" "textDocument/hover"
               "method" "textDocument/hover"
               "registerOptions" {"documentSelector" [{"language" "ruby"} {"language" "erb"}]}}]
             (capabilities/registrations client-caps [[{"hoverProvider" true} [{"language" "ruby"}]]
                                                      [{"hoverProvider" true} [{"language" "erb"}]]
                                                      [{} [{"language" "haml"}]]]))))
    (testing "with the trigger characters of all of them"
      (is (= ["." ":" "/"]
             (get (options [[{"completionProvider" {"triggerCharacters" ["." ":"]}} []]
                            [{"completionProvider" {"triggerCharacters" ["/" "."]}} []]]
                           "textDocument/completion")
                  "triggerCharacters"))))
    (testing "resolving items if any of them does"
      (is (true? (get (options [[{"completionProvider" {"resolveProvider" false}} []]
                                [{"completionProvider" {"resolveProvider" true}} []]]
                               "textDocument/completion")
                      "resolveProvider"))))))

(deftest command-registrations
  (let [client-caps {"workspace" {"executeCommand" {"dynamicRegistration" true}}}]
    (is (= [{"id" "workspace/executeCommand"
             "method" "workspace/executeCommand"
             "registerOptions" {"commands" ["a" "b"]}}]
           (capabilities/registrations client-caps [[{"executeCommandProvider" {"commands" ["a"]}} [{"language" "ruby"}]]
                                                    [{"executeCommandProvider" {"commands" ["a" "b"]}} [{"language" "erb"}]]]))
        "with every backend's commands once, and without a document selector")
    (is (= {"commands" []}
           (get-in (capabilities/initialize-result {}) ["capabilities" "executeCommandProvider"]))
        "announced with no commands when the client can't register them")))

(deftest limiting-a-backends-selector
  (let [scope {"language" "ruby" "pattern" {"baseUri" "file:///r/api" "pattern" "**/*"}}
        gemfile {"pattern" {"baseUri" "file:///r" "pattern" "**/Gemfile"}}
        own [scope gemfile]]
    (testing "a null selector becomes the proxy's"
      (is (= own (capabilities/limit-selector nil own))))
    (testing "a language is limited to the backend's files"
      (is (= [scope (assoc gemfile "language" "ruby")]
             (capabilities/limit-selector [{"language" "ruby"}] own)))
      (is (= [(assoc gemfile "language" "erb")]
             (capabilities/limit-selector [{"language" "erb"}] own))))
    (testing "a relative glob is moved under the scope"
      (is (= [{"language" "ruby" "scheme" "file" "pattern" {"baseUri" "file:///r/api" "pattern" "**/*.rb"}}]
             (capabilities/limit-selector [{"scheme" "file" "pattern" "**/*.rb"}] own))))
    (testing "an absolute glob is kept only inside the scope"
      (is (= [{"language" "ruby" "pattern" "/r/api/lib/**"}]
             (capabilities/limit-selector [{"pattern" "/r/api/lib/**"} {"pattern" "/r/web/**"}] own))))))

(deftest features-by-method
  (is (= "hoverProvider" (:provider (capabilities/feature "textDocument/hover"))))
  (is (= "semanticTokensProvider" (:provider (capabilities/feature "textDocument/semanticTokens/full"))))
  (is (nil? (capabilities/feature "rubyLsp/workspace/dependencies")))
  (testing "offered unless absent or false"
    (let [hover (capabilities/feature "textDocument/hover")]
      (is (capabilities/offers? {"hoverProvider" {}} hover))
      (is (not (capabilities/offers? {"hoverProvider" false} hover)))
      (is (not (capabilities/offers? {} hover))))))

(deftest combined-requests
  (is (capabilities/combined? "textDocument/completion"))
  (is (not (capabilities/combined? "textDocument/hover"))))

(deftest empty-results
  (is (nil? (capabilities/empty-result "textDocument/hover")))
  (is (= {"kind" "full" "items" []} (capabilities/empty-result "textDocument/diagnostic"))))
