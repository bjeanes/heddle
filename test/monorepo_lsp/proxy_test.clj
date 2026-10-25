(ns monorepo-lsp.proxy-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.fake-backend :as fake :refer [eventually]]
            [monorepo-lsp.lsp :as lsp]
            [monorepo-lsp.proxy :as proxy])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private config "
[servers.ruby]
languages = ['ruby']

[servers.lint]
languages = ['ruby']
globs = ['**/Gemfile']

[scopes.'.']
lint = { runner = 'host', command = 'lint' }

[scopes.api]
ruby = { runner = 'local', command = 'ruby', paths = { 'api' = '/app' } }

[runners.local]
start = ['{cfg:command}']
read = ['cat', '{read:file}']
")

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

(def ^:private offers-hover
  (fake/answering {"initialize" (constantly {"capabilities" {"hoverProvider" true}})}))

(defn- start
  "A proxy for a repo with `config` (or the `config` option), with fake
  backends that answer with `respond`. Returns the proxy and its repo, a queue
  of what it sends the client, and an atom of what each backend receives."
  [& {:keys [respond server-types] :as opts}]
  (let [repo (temp-dir)
        out (LinkedBlockingQueue.)
        received (atom {})
        exit-code (promise)]
    (spit (io/file repo ".monorepo-lsp.toml") (get opts :config config))
    (.mkdirs (io/file repo "api"))
    {:repo repo
     :out out
     :received received
     :exit-code exit-code
     :proxy (proxy/create {:send #(.put out %)
                           :connect (fake/connector received (or respond offers-hover))
                           :server-types server-types
                           :cwd (temp-dir)
                           :exit #(deliver exit-code %)})}))

(defn- from-proxy
  "The next message the proxy sends the client, waiting up to two seconds."
  [{:keys [out]}]
  (.poll out 2 TimeUnit/SECONDS))

(defn- client! [t msg]
  (proxy/handle-client! (:proxy t) msg))

(defn- uri [t path]
  (str "file://" (:repo t) "/" path))

(defn- initialize!
  "Initializes the proxy as a client with `capabilities` would, and returns
  the proxy's capabilities."
  [t capabilities]
  (client! t (lsp/request 0 "initialize" {"rootUri" (str "file://" (:repo t))
                                          "capabilities" capabilities}))
  (get-in (from-proxy t) ["result" "capabilities"]))

(defn- open! [t path language]
  (client! t (lsp/notification "textDocument/didOpen"
                               {"textDocument" {"uri" (uri t path) "languageId" language
                                                "version" 1 "text" ""}})))

(defn- methods-received [t k]
  (mapv #(get % "method") (get @(:received t) k)))

(deftest initializing
  (testing "answers with full sync, and finds the config at the client's root"
    (let [t (start)]
      (is (= {"openClose" true "change" 1 "save" true}
             (get (initialize! t {}) "textDocumentSync")))))
  (testing "falls back to the working directory"
    (let [t (start)
          cwd (get-in t [:proxy :cwd])]
      (spit (io/file cwd ".monorepo-lsp.toml") config)
      (client! t (lsp/request 0 "initialize" {"rootUri" "file:///nowhere" "capabilities" {}}))
      (is (contains? (from-proxy t) "result"))))
  (testing "answers with an error without a config"
    (let [t (start)]
      (client! t (lsp/request 0 "initialize" {"rootUri" "file:///nowhere" "capabilities" {}}))
      (is (str/starts-with? (get-in (from-proxy t) ["error" "message"])
                            "no .monorepo-lsp.toml in /nowhere or "))))
  (testing "refuses other requests until initialized"
    (let [t (start)]
      (client! t (lsp/request 1 "textDocument/hover" {}))
      (is (= -32002 (get-in (from-proxy t) ["error" "code"]))))))

(deftest starting-backends
  (let [t (start)]
    (initialize! t {"general" {"positionEncodings" ["utf-8" "utf-16"]}})
    (testing "only once a file of theirs opens"
      (is (= {} @(:received t)))
      (open! t "api/a.rb" "ruby")
      (is (eventually #(= ["initialize" "initialized" "textDocument/didOpen"]
                          (methods-received t ["ruby" "api"]))))
      (is (eventually #(= ["initialize" "initialized" "textDocument/didOpen"]
                          (methods-received t ["lint" "."])))))
    (testing "rooted at the scope's server path, offered only UTF-16"
      (let [params (get-in @(:received t) [["ruby" "api"] 0 "params"])]
        (is (= "file:///app" (get params "rootUri")))
        (is (= [{"uri" "file:///app" "name" "api"}] (get params "workspaceFolders")))
        (is (= ["utf-16"] (get-in params ["capabilities" "general" "positionEncodings"])))))
    (testing "with the file's URI translated"
      (is (= "file:///app/a.rb"
             (get-in @(:received t) [["ruby" "api"] 2 "params" "textDocument" "uri"]))))
    (testing "and once per server type and scope"
      (open! t "api/b.rb" "ruby")
      (is (eventually #(= 4 (count (methods-received t ["ruby" "api"])))))
      (is (= 1 (count (filter #{"initialize"} (methods-received t ["ruby" "api"]))))))))

(deftest serving-some-server-types
  (let [t (start :server-types #{"lint"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (is (eventually #(= 3 (count (methods-received t ["lint" "."])))))
    (is (= [["lint" "."]] (keys @(:received t))))))

(deftest open-files
  (let [t (start)]
    (initialize! t {})
    (open! t "Gemfile" "plaintext")
    (open! t "../elsewhere.rb" "ruby")
    (doseq [method ["textDocument/didChange" "textDocument/didSave" "textDocument/didClose"]]
      (client! t (lsp/notification method {"textDocument" {"uri" (uri t "Gemfile")}})))
    (testing "go only to the backends that handle them, by language or glob"
      (is (eventually #(= ["initialize" "initialized" "textDocument/didOpen" "textDocument/didChange"
                           "textDocument/didSave" "textDocument/didClose"]
                          (methods-received t ["lint" "."]))))
      (is (= [["lint" "."]] (keys @(:received t)))))))

(deftest notifying-a-starting-backend
  (let [started (promise)
        respond (fn [_ {:strs [id method]}]
                  (when (= "initialize" method)
                    @started
                    [(lsp/response id {"capabilities" {}})]))
        t (start :respond respond :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (lsp/notification "textDocument/didChange" {"textDocument" {"uri" (uri t "api/a.rb")}}))
    (testing "waits until it has answered initialize"
      (is (eventually #(= ["initialize"] (methods-received t ["ruby" "api"])))))
    (testing "then sends what the client sent meanwhile, in order"
      (deliver started true)
      (is (eventually #(= ["initialize" "initialized" "textDocument/didOpen" "textDocument/didChange"]
                          (methods-received t ["ruby" "api"])))))))

(deftest registering-features
  (let [t (start)
        caps (initialize! t {"textDocument" {"hover" {"dynamicRegistration" true}}})]
    (is (not (contains? caps "hoverProvider")))
    (open! t "api/a.rb" "ruby")
    (let [requests (doall (repeatedly 2 #(from-proxy t)))
          registrations (mapcat #(get-in % ["params" "registrations"]) requests)]
      (testing "once each backend has started"
        (is (= ["client/registerCapability" "client/registerCapability"] (map #(get % "method") requests)))
        (is (= ["textDocument/hover" "textDocument/hover"] (map #(get % "method") registrations)))
        (is (= 2 (count (set (map #(get % "id") registrations))))))
      (testing "for the files in its scope"
        (is (some #(= {"language" "ruby" "pattern" {"baseUri" (uri t "api") "pattern" "**/*"}}
                      (get-in % ["registerOptions" "documentSelector" 0]))
                  registrations))))))

(defn- received [t k method]
  (filterv #(= method (get % "method")) (get @(:received t) k)))

(defn- request-about [t id method path]
  (lsp/request id method {"textDocument" {"uri" (uri t path)}
                          "position" {"line" 0 "character" 0}}))

(deftest requests-about-a-file
  (let [gem (str (temp-dir) "/gems/rainbow.rb")
        respond (fn [[server-type] {:strs [id method params]}]
                  (when id
                    [(lsp/response
                      id
                      (case [server-type method]
                        ["ruby" "initialize"] {"capabilities" {"hoverProvider" true "definitionProvider" true}}
                        ["lint" "initialize"] {"capabilities" {"definitionProvider" true}}
                        ["ruby" "textDocument/hover"] {"contents" (str "[Rainbow](file://" gem "#L1) for "
                                                                       (get-in params ["textDocument" "uri"]))}
                        ["lint" "textDocument/definition"] {"uri" (get-in params ["textDocument" "uri"])}
                        nil))]))
        t (start :respond respond)]
    (io/make-parents gem)
    (spit gem "module Rainbow; end\n")
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (testing "go to the first backend that offers the feature, by name when priorities tie"
      (client! t (request-about t "h" "textDocument/hover" "api/a.rb"))
      (let [response (from-proxy t)
            cache (get-in @(:state (:proxy t)) [:backends ["ruby" "api"] :cache])]
        (testing "with the client's ID on the response, and paths translated both ways"
          (is (= "h" (get response "id")))
          (is (= (str "[Rainbow](file://" cache gem "#L1) for " (uri t "api/a.rb"))
                 (get-in response ["result" "contents"])))
          (is (number? (get-in (received t ["ruby" "api"] "textDocument/hover") [0 "id"]))))
        (testing "after fetching the files the response refers to"
          (is (= "module Rainbow; end\n" (slurp (str cache gem))))))
      (client! t (request-about t 7 "textDocument/definition" "api/a.rb"))
      (is (= (lsp/response 7 {"uri" (uri t "api/a.rb")}) (from-proxy t)))
      (is (= [] (received t ["ruby" "api"] "textDocument/definition"))))
    (testing "get an empty result when no backend offers the feature"
      (client! t (request-about t 8 "textDocument/references" "api/a.rb"))
      (is (= (lsp/response 8 nil) (from-proxy t))))
    (testing "go to the first backend when the proxy doesn't know the feature"
      (client! t (request-about t 9 "custom/request" "api/a.rb"))
      (is (= (lsp/response 9 nil) (from-proxy t)))
      (is (= 1 (count (received t ["lint" "."] "custom/request")))))))

(deftest requests-by-priority
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"definitionProvider" true}})]
                    "textDocument/definition" [(lsp/response id server-type)]
                    nil))
        t (start :respond respond
                 :config (str/replace config "[servers.ruby]\n" "[servers.ruby]\npriority = 1\n"))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (request-about t 1 "textDocument/definition" "api/a.rb"))
    (is (= (lsp/response 1 "ruby") (from-proxy t))
        "go to the highest priority backend that offers the feature")))

(deftest combining-answers
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (case [server-type method]
                    (["ruby" "initialize"] ["lint" "initialize"])
                    [(lsp/response id {"capabilities" {"completionProvider" {}
                                                       "referencesProvider" true
                                                       "codeLensProvider" {}
                                                       "codeActionProvider" true}})]
                    (["ruby" "textDocument/codeAction"] ["lint" "textDocument/codeAction"])
                    [(lsp/response id [{"title" "Fix"}])]
                    ["ruby" "textDocument/completion"] [(lsp/response id [{"label" "ruby"}])]
                    ["lint" "textDocument/completion"] [(lsp/response id {"isIncomplete" true
                                                                          "items" [{"label" "lint"}]})]
                    ["ruby" "textDocument/references"] [(lsp/response id [{"uri" "file:///app/a.rb"}])]
                    ["lint" "textDocument/references"] [(lsp/error-response id -32603 "broken")]
                    (["ruby" "textDocument/codeLens"] ["lint" "textDocument/codeLens"])
                    [(lsp/error-response id -32603 (str server-type " broke"))]
                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (testing "from every backend for the file that offers the feature, in priority order"
      (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
      (let [{:strs [id result]} (from-proxy t)]
        (is (= 1 id))
        (is (true? (get result "isIncomplete")))
        (is (= ["lint" "ruby"] (map #(get % "label") (get result "items"))))))
    (testing "with code action titles naming their server types"
      (client! t (request-about t 4 "textDocument/codeAction" "api/a.rb"))
      (is (= ["[lint] Fix" "[ruby] Fix"] (map #(get % "title") (get (from-proxy t) "result")))))
    (testing "without the backends that failed to answer"
      (client! t (request-about t 2 "textDocument/references" "api/a.rb"))
      (is (= (lsp/response 2 [{"uri" (uri t "api/a.rb")}]) (from-proxy t))))
    (testing "or with the first error if they all failed"
      (client! t (request-about t 3 "textDocument/codeLens" "api/a.rb"))
      (is (= (lsp/error-response 3 -32603 "lint broke") (from-proxy t))))))

(defn- backend-id [t k]
  (get-in @(:state (:proxy t)) [:backends k :backend-id]))

(deftest resolving-items
  (let [respond (fn [[server-type] {:strs [id method params]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"completionProvider" {"resolveProvider" (= "lint" server-type)}}})]
                    "textDocument/completion" [(lsp/response id [{"label" server-type "data" {"n" 1}}
                                                                 {"label" (str server-type "-bare")}])]
                    "completionItem/resolve" [(lsp/response id (assoc params "detail" server-type))]
                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
    (let [items (into {} (for [item (get-in (from-proxy t) ["result" "items"])]
                           [(get item "label") item]))]
      (testing "wraps each item's data with the backend that made it"
        (is (= {"monorepo-lsp/backend" (backend-id t ["lint" "."]) "data" {"n" 1}}
               (get-in items ["lint" "data"])))
        (is (= {"monorepo-lsp/backend" (backend-id t ["lint" "."])}
               (get-in items ["lint-bare" "data"]))))
      (testing "sends resolve requests to that backend, with the data it gave"
        (client! t (lsp/request 2 "completionItem/resolve" (get items "lint")))
        (is (= (lsp/response 2 (assoc (get items "lint") "detail" "lint")) (from-proxy t)))
        (is (= {"label" "lint" "data" {"n" 1}}
               (get-in (received t ["lint" "."] "completionItem/resolve") [0 "params"])))
        (client! t (lsp/request 3 "completionItem/resolve" (get items "lint-bare")))
        (is (= (lsp/response 3 (assoc (get items "lint-bare") "detail" "lint")) (from-proxy t)))
        (is (= {"label" "lint-bare"}
               (get-in (received t ["lint" "."] "completionItem/resolve") [1 "params"]))))
      (testing "answers with the item unchanged if its backend doesn't resolve items"
        (client! t (lsp/request 4 "completionItem/resolve" (get items "ruby")))
        (is (= (lsp/response 4 (get items "ruby")) (from-proxy t)))
        (is (= [] (received t ["ruby" "api"] "completionItem/resolve"))))
      (testing "or if its backend is gone"
        (let [item (assoc-in (get items "lint") ["data" "monorepo-lsp/backend"] "monorepo-lsp-gone")]
          (client! t (lsp/request 5 "completionItem/resolve" item))
          (is (= (lsp/response 5 item) (from-proxy t))))))))

(deftest running-commands
  (let [respond (fn [[server-type] {:strs [id method params]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"codeActionProvider" true
                                                                    "executeCommandProvider" {"commands" ["fix"]}}})]
                    "textDocument/codeAction" [(lsp/response id [{"title" "Fix" "command" "fix"}])]
                    "workspace/executeCommand" [(lsp/response id [server-type params])]
                    nil))
        t (start :respond respond)]
    (initialize! t {"workspace" {"executeCommand" {"dynamicRegistration" true}}})
    (open! t "api/a.rb" "ruby")
    (testing "registers each backend's commands qualified with its server type"
      (is (= #{["lint:fix"] ["ruby:fix"]}
             (set (for [msg (doall (repeatedly 2 #(from-proxy t)))]
                    (get-in msg ["params" "registrations" 0 "registerOptions" "commands"]))))))
    (testing "qualifies the commands in answers"
      (client! t (request-about t 1 "textDocument/codeAction" "api/a.rb"))
      (is (= ["lint:fix" "ruby:fix"] (map #(get % "command") (get (from-proxy t) "result")))))
    (testing "sends a command to a backend of its server type, as the backend named it"
      (client! t (lsp/request 2 "workspace/executeCommand" {"command" "ruby:fix" "arguments" [(uri t "api/a.rb")]}))
      (is (= (lsp/response 2 ["ruby" {"command" "fix" "arguments" [(uri t "api/a.rb")]}]) (from-proxy t))))
    (testing "refuses commands of no server type"
      (client! t (lsp/request 3 "workspace/executeCommand" {"command" "fix"}))
      (is (= -32602 (get-in (from-proxy t) ["error" "code"]))))))

(deftest running-commands-in-several-scopes
  (let [respond (fn [[_ scope] {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {}})]
                    "workspace/executeCommand" [(lsp/response id scope)]
                    nil))
        t (start :respond respond
                 :server-types #{"ruby"}
                 :config (str/replace config "[scopes.'.']\n" "[scopes.'.']\nruby = { runner = 'host', command = 'ruby' }\n"))
        execute (fn [id & arguments]
                  (client! t (lsp/request id "workspace/executeCommand" {"command" "ruby:x" "arguments" (vec arguments)}))
                  (get (from-proxy t) "result"))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (open! t "b.rb" "ruby")
    (is (eventually #(= 2 (count @(:received t)))))
    (testing "go to the backend whose scope contains a file in the arguments"
      (is (= "api" (execute 1 {"uri" (uri t "api/a.rb")})))
      (is (= "." (execute 2 {"uri" (uri t "b.rb")}))))
    (testing "or else to the first by scope"
      (is (= "." (execute 3))))))

(deftest requests-past-a-backend-that-failed-to-start
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (case [server-type method]
                    ["lint" "initialize"] [(lsp/error-response id -32603 "no config")]
                    ["ruby" "initialize"] [(lsp/response id {"capabilities" {"definitionProvider" true}})]
                    ["ruby" "textDocument/definition"] [(lsp/response id "from ruby")]
                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (request-about t 1 "textDocument/definition" "api/a.rb"))
    (is (= (lsp/response 1 "from ruby") (from-proxy t)))))

(deftest requests-from-backends
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (cond
                    (= "initialize" method)
                    [(lsp/response id {"capabilities" {}})]

                    (= "textDocument/didOpen" method)
                    (into [(lsp/request 1 "workspace/configuration" {"items" [{"section" server-type}]})]
                          (when (= "ruby" server-type)
                            [(lsp/request 2 "window/workDoneProgress/create" {"token" "t"})
                             (lsp/notification "$/cancelRequest" {"id" 2})
                             (lsp/notification "textDocument/publishDiagnostics"
                                               {"uri" "file:///app/a.rb" "diagnostics" []})]))))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (let [messages (doall (repeatedly 5 #(from-proxy t)))
          by-method (group-by #(get % "method") messages)
          configuration (into {} (for [r (get by-method "workspace/configuration")]
                                   [(get-in r ["params" "items" 0 "section"]) r]))
          create (first (get by-method "window/workDoneProgress/create"))]
      (testing "get IDs of the proxy's own"
        (is (= 3 (count (set (map #(get % "id") [(get configuration "ruby") (get configuration "lint") create]))))))
      (testing "and so do their cancellations"
        (is (= {"id" (get create "id")} (get-in by-method ["$/cancelRequest" 0 "params"]))))
      (testing "notifications are translated"
        (is (= (uri t "api/a.rb") (get-in by-method ["textDocument/publishDiagnostics" 0 "params" "uri"]))))
      (testing "the client's responses go back to the right backend, with its ID and paths"
        (client! t (lsp/response (get-in configuration ["ruby" "id"]) [(uri t "api")]))
        (client! t (lsp/response (get-in configuration ["lint" "id"]) ["lint"]))
        (is (eventually #(= {"id" 1 "result" ["file:///app"]}
                            (select-keys (peek (get @(:received t) ["ruby" "api"])) ["id" "result"]))))
        (is (eventually #(= {"id" 1 "result" ["lint"]}
                            (select-keys (peek (get @(:received t) ["lint" "."])) ["id" "result"]))))))))

(deftest registrations-and-progress-from-backends
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (case method
                    "initialize"
                    [(lsp/response id {"capabilities" {}})]

                    "textDocument/didOpen"
                    [(lsp/request 1 "client/registerCapability"
                                  {"registrations" [{"id" "watch" "method" "workspace/didChangeWatchedFiles"}]})
                     (lsp/request 2 "client/unregisterCapability"
                                  {"unregisterations" [{"id" "watch" "method" "workspace/didChangeWatchedFiles"}]})
                     (lsp/request 3 "window/workDoneProgress/create" {"token" "indexing"})
                     (lsp/notification "$/progress" {"token" "indexing"
                                                     "value" {"kind" "begin" "title" server-type}})
                     (lsp/notification "$/progress" {"token" "from-client" "value" {"kind" "report"}})]

                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (let [by-method (group-by #(get % "method") (doall (repeatedly 10 #(from-proxy t))))
          ids (fn [method k] (set (for [msg (get by-method method)
                                        item (get-in msg ["params" k])]
                                    (get item "id"))))
          created (set (map #(get-in % ["params" "token"]) (get by-method "window/workDoneProgress/create")))
          progress (group-by #(get-in % ["params" "value" "kind"]) (get by-method "$/progress"))
          token-of (into {} (for [msg (get progress "begin")]
                              [(get-in msg ["params" "value" "title"]) (get-in msg ["params" "token"])]))]
      (testing "registration IDs are made unique, and the same on unregistering"
        (is (= 2 (count (ids "client/registerCapability" "registrations"))))
        (is (= (ids "client/registerCapability" "registrations")
               (ids "client/unregisterCapability" "unregisterations"))))
      (testing "progress tokens a backend creates are made unique"
        (is (= 2 (count created)))
        (is (= created (set (vals token-of)))))
      (testing "progress tokens the client chose are left alone"
        (is (= ["from-client" "from-client"] (map #(get-in % ["params" "token"]) (get progress "report")))))
      (testing "the client's cancellation of progress goes to the backend that created it"
        (client! t (lsp/notification "window/workDoneProgress/cancel" {"token" (get token-of "ruby")}))
        (is (eventually #(= [{"token" "indexing"}]
                            (map (fn [msg] (get msg "params"))
                                 (received t ["ruby" "api"] "window/workDoneProgress/cancel")))))
        (is (= [] (received t ["lint" "."] "window/workDoneProgress/cancel")))))))

(deftest cancelling-a-request
  (let [respond (fn [_ {:strs [id method]}]
                  (when (= "initialize" method)
                    [(lsp/response id {"capabilities" {"hoverProvider" true}})]))
        t (start :respond respond :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (request-about t "h" "textDocument/hover" "api/a.rb"))
    (is (eventually #(seq (received t ["ruby" "api"] "textDocument/hover"))))
    (client! t (lsp/notification "$/cancelRequest" {"id" "h"}))
    (is (eventually #(= [{"id" (get-in (received t ["ruby" "api"] "textDocument/hover") [0 "id"])}]
                        (map (fn [msg] (get msg "params")) (received t ["ruby" "api"] "$/cancelRequest")))))))

(deftest shutting-down
  (let [t (start)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (lsp/request 9 "shutdown" nil))
    (testing "answers once every backend has shut down"
      (is (= (lsp/response 9 nil) (from-proxy t)))
      (is (= 1 (count (received t ["ruby" "api"] "shutdown"))))
      (is (= 1 (count (received t ["lint" "."] "shutdown")))))
    (testing "exits after telling every backend to"
      (client! t (lsp/notification "exit" nil))
      (is (= 0 (deref (:exit-code t) 2000 :timeout)))
      (is (= 1 (count (received t ["ruby" "api"] "exit"))))
      (is (= 1 (count (received t ["lint" "."] "exit")))))))

(deftest shutting-down-a-slow-backend
  (let [answer (promise)
        respond (fn [_ {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {}})]
                    "shutdown" (do @answer [(lsp/response id nil)])
                    nil))
        t (start :respond respond :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (lsp/request 9 "shutdown" nil))
    (testing "doesn't hold up the client's messages while it waits"
      (is (eventually #(seq (received t ["ruby" "api"] "shutdown"))))
      (is (nil? (.poll (:out t) 100 TimeUnit/MILLISECONDS))))
    (testing "answers once the backend does"
      (deliver answer true)
      (is (= (lsp/response 9 nil) (from-proxy t))))))

(deftest exiting-without-shutting-down
  (let [t (start)]
    (client! t (lsp/notification "exit" nil))
    (is (= 1 (deref (:exit-code t) 2000 :timeout)))))
