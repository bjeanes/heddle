(ns heddle.proxy-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heddle.backend :as backend]
            [heddle.fake-backend :as fake :refer [eventually]]
            [heddle.log :as log]
            [heddle.lsp :as lsp]
            [heddle.proxy :as proxy])
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

(def ^:private ruby-in-two-scopes
  (str/replace config "[scopes.'.']\n" "[scopes.'.']\nruby = { runner = 'host', command = 'ruby' }\n"))

(defn- temp-dir []
  (str (Files/createTempDirectory "heddle-test" (into-array FileAttribute []))))

(def ^:private offers-hover
  (fake/answering {"initialize" (constantly {"capabilities" {"textDocumentSync" 1 "hoverProvider" true}})}))

(defn- start
  "A proxy for a repo with `config` (or the `config` option), with fake
  backends that answer with `respond`. Connecting to the backends that are
  keys of `refuse` fails, as described by the value. Returns the proxy and its
  repo, a queue of what it sends the client, an atom of what each backend
  receives, and an atom of the proxy's clock in milliseconds."
  [& {:keys [respond refuse server-types eager eager-timeout-ms start-timeout-ms] :as opts}]
  (let [repo (temp-dir)
        out (LinkedBlockingQueue.)
        received (atom {})
        exit-code (promise)
        now (atom 0)]
    (spit (io/file repo ".heddle.toml") (get opts :config config))
    (.mkdirs (io/file repo "api"))
    {:repo repo
     :out out
     :received received
     :exit-code exit-code
     :now now
     :proxy (proxy/create {:send #(.put out %)
                           :connect (fake/refusing (fake/connector received (or respond offers-hover)) refuse)
                           :server-types server-types
                           :eager eager
                           :eager-timeout-ms eager-timeout-ms
                           :start-timeout-ms start-timeout-ms
                           :cwd (temp-dir)
                           :exit #(deliver exit-code %)
                           :clock #(deref now)})}))

(defn- from-proxy
  "The next message the proxy sends the client, waiting up to two seconds."
  [{:keys [out]}]
  (.poll out 2 TimeUnit/SECONDS))

(defn- client! [t msg]
  (proxy/handle-client! (:proxy t) msg))

(defn- uri [t path]
  (str "file://" (:repo t) "/" path))

(defn- backend-of
  "The live backend `k`, a [server-type scope]."
  [t k]
  (get-in @(:state (:proxy t)) [:registry :backends k :backend]))

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

(defn- shown [type message]
  (lsp/notification "window/showMessage" {"type" type "message" message}))

(defn- failure
  "The message for a backend that failed as `what-happened` says."
  [what-happened]
  (str what-happened ". Opening one of its files will start it again, once 30 s have passed."))

(deftest initializing
  (testing "answers with full sync, and finds the config at the client's root"
    (let [t (start)]
      (is (= {"openClose" true "change" 1 "save" true}
             (get (initialize! t {}) "textDocumentSync")))))
  (testing "falls back to the working directory"
    (let [t (start)
          cwd (get-in t [:proxy :cwd])]
      (spit (io/file cwd ".heddle.toml") config)
      (client! t (lsp/request 0 "initialize" {"rootUri" "file:///nowhere" "capabilities" {}}))
      (is (contains? (from-proxy t) "result"))))
  (testing "refuses other requests until initialized"
    (let [t (start)]
      (client! t (lsp/request 1 "textDocument/hover" {}))
      (is (= -32002 (get-in (from-proxy t) ["error" "code"]))))))

(defn- serves-nothing? [t]
  (open! t "api/a.rb" "ruby")
  (client! t (lsp/request 1 "textDocument/hover" {"textDocument" {"uri" (uri t "api/a.rb")}}))
  (and (= (lsp/response 1 nil) (from-proxy t))
       (= {} @(:received t))))

(deftest without-a-usable-config
  (testing "fails `initialize` if there's no config, saying so on stderr, and sends nothing else"
    (let [t (start)
          cwd (get-in t [:proxy :cwd])
          reason (str "There's no .heddle.toml in /nowhere or " cwd ".")
          logged (atom [])
          previous-writer (log/set-writer! #(swap! logged conj %))]
      (try
        (client! t (lsp/request 0 "initialize" {"rootUri" "file:///nowhere" "capabilities" {}}))
        (finally
          (log/set-writer! previous-writer)))
      (is (= (lsp/error-response 0 -32803 reason {"retry" false}) (from-proxy t)))
      (is (= [(str "heddle: couldn't initialize - " reason)] @logged))
      (open! t "api/a.rb" "ruby")
      (client! t (lsp/request 1 "textDocument/hover" {"textDocument" {"uri" (uri t "api/a.rb")}}))
      (is (= -32002 (get-in (from-proxy t) ["error" "code"])))
      (client! t (lsp/notification "exit" nil))
      (is (= 1 (deref (:exit-code t) 2000 nil)))
      (is (nil? (.poll (:out t))))
      (is (= {} @(:received t)))))
  (testing "warns that no scope starts a server type, and serves nothing"
    (let [t (start :config "[servers.ruby]\nlanguages = ['ruby']\n")]
      (initialize! t {})
      (is (= (shown 2 (str "No scope in " (:repo t) "/.heddle.toml starts a server type.\n"
                           "heddle is serving nothing until a scope starts one and it restarts."))
             (from-proxy t)))
      (is (serves-nothing? t))))
  (testing "shows the config's errors, and serves nothing"
    (let [t (start :config "[servers.ruby]\n[scopes.api]\nruby = { runner = 'host' }\n")]
      (is (= {"openClose" true "change" 1 "save" true}
             (get (initialize! t {}) "textDocumentSync")))
      (let [{:strs [method params]} (from-proxy t)]
        (is (= "window/showMessage" method))
        (is (= 1 (get params "type")))
        (is (str/includes? (get params "message") "server type \"ruby\" matches no files")))
      (is (serves-nothing? t)))))

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

(defn- change! [t path version text]
  (client! t (lsp/notification "textDocument/didChange"
                               {"textDocument" {"uri" (uri t path) "version" version}
                                "contentChanges" [{"text" text}]})))

(defn- close! [t path]
  (client! t (lsp/notification "textDocument/didClose" {"textDocument" {"uri" (uri t path)}})))

(deftest closing-a-file
  (let [t (start :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (close! t "api/a.rb")
    (change! t "api/a.rb" 2 "")
    (open! t "api/a.rb" "ruby")
    (is (eventually #(= ["initialize" "initialized" "textDocument/didOpen" "textDocument/didClose"
                         "textDocument/didOpen"]
                        (methods-received t ["ruby" "api"])))
        "stops the backend hearing about it until it opens again")))

(deftest notifying-a-starting-backend
  (let [started (promise)
        respond (fn [_ {:strs [id method]}]
                  (when (= "initialize" method)
                    @started
                    [(lsp/response id {"capabilities" {"textDocumentSync" 1}})]))
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
        caps (initialize! t {"textDocument" {"hover" {"dynamicRegistration" true}}})
        selector #(get-in % ["params" "registrations" 0 "registerOptions" "documentSelector"])]
    (is (not (contains? caps "hoverProvider")))
    (open! t "Gemfile" "plaintext")
    (let [lint (from-proxy t)]
      (testing "once a backend that offers them has started, for the files it handles"
        (is (= "client/registerCapability" (get lint "method")))
        (is (= [["textDocument/hover" "textDocument/hover"]]
               (map (juxt #(get % "id") #(get % "method")) (get-in lint ["params" "registrations"]))))
        (is (some #{{"pattern" {"baseUri" (str "file://" (:repo t)) "pattern" "**/Gemfile"}}} (selector lint))
            "such as those matching its globs"))
      (testing "once for every backend, replacing the registration when another starts"
        (open! t "api/a.rb" "ruby")
        (let [unregister (from-proxy t)
              register (from-proxy t)]
          (is (= (lsp/request (get unregister "id") "client/unregisterCapability"
                              {"unregisterations" [{"id" "textDocument/hover" "method" "textDocument/hover"}]})
                 unregister))
          (is (= "client/registerCapability" (get register "method")))
          (is (= ["textDocument/hover"] (map #(get % "id") (get-in register ["params" "registrations"]))))
          (testing "covering both backends' files"
            (is (= (selector lint) (take (count (selector lint)) (selector register))))
            (is (some #{{"language" "ruby" "pattern" {"baseUri" (uri t "api") "pattern" "**/*"}}}
                      (selector register)))))))))

(def ^:private registers-hover-and-commands
  {"textDocument" {"hover" {"dynamicRegistration" true}
                   "definition" {"dynamicRegistration" true}}
   "workspace" {"executeCommand" {"dynamicRegistration" true}}})

(defn- nothing-more? [t]
  (nil? (.poll (:out t) 200 TimeUnit/MILLISECONDS)))

(deftest starting-backends-eagerly
  (let [respond (fn [_ {:strs [id method]}]
                  (when (= "initialize" method)
                    [(lsp/response id {"capabilities" {"hoverProvider" true
                                                       "executeCommandProvider" {"commands" ["fix"]}}})]))
        t (start :respond respond :eager true)
        caps (initialize! t registers-hover-and-commands)]
    (testing "starts every backend before answering initialize"
      (is (eventually #(= ["initialize" "initialized"] (methods-received t ["ruby" "api"]))))
      (is (eventually #(= ["initialize" "initialized"] (methods-received t ["lint" "."])))))
    (testing "announces their features in the answer, with every command qualified"
      (is (= {} (get caps "hoverProvider")))
      (is (= {"commands" ["lint:fix" "ruby:fix"]} (get caps "executeCommandProvider"))))
    (testing "and doesn't register those features again"
      (is (nothing-more? t)))))

(deftest starting-backends-eagerly-with-a-slow-backend
  (let [started (promise)
        respond (fn [[server-type] {:strs [id method]}]
                  (when (= "initialize" method)
                    (if (= "ruby" server-type)
                      (do @started
                          [(lsp/response id {"capabilities" {"hoverProvider" true "definitionProvider" true}})])
                      [(lsp/response id {"capabilities" {"hoverProvider" true}})])))
        t (start :respond respond :eager true :eager-timeout-ms 100)
        caps (initialize! t registers-hover-and-commands)]
    (testing "answers initialize once the timeout runs out, with the backends that have started"
      (is (contains? caps "hoverProvider"))
      (is (not (contains? caps "definitionProvider"))))
    (testing "registers the features only a late backend offers once it starts"
      (deliver started true)
      (let [register (from-proxy t)]
        (is (= "client/registerCapability" (get register "method")))
        (is (= ["textDocument/definition"] (map #(get % "method") (get-in register ["params" "registrations"])))))
      (is (nothing-more? t)))))

(deftest starting-backends-eagerly-with-a-hung-backend
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (when (and (= "initialize" method) (= "lint" server-type))
                    [(lsp/response id {"capabilities" {"hoverProvider" true}})]))
        t (start :respond respond :eager true :eager-timeout-ms 100 :start-timeout-ms 300)
        caps (initialize! t {})]
    (testing "answers initialize once the eager wait runs out"
      (is (contains? caps "hoverProvider")))
    (testing "then fails the hung backend once its own time runs out"
      (is (= (shown 1 (failure "ruby in api failed to start: it didn't answer initialize within 300 ms"))
             (from-proxy t))))))

(deftest starting-backends-eagerly-holds-back-their-messages
  (let [log (fn [message] (lsp/notification "window/logMessage" {"type" 3 "message" message}))
        respond (fn [[server-type] {:strs [id method]}]
                  (when (= "initialize" method)
                    (cond-> [(lsp/response id {"capabilities" {}})]
                      (= "ruby" server-type) (->> (into [(log "one") (log "two")])))))
        t (start :respond respond :eager true)]
    (client! t (lsp/request 0 "initialize" {"rootUri" (str "file://" (:repo t)) "capabilities" {}}))
    (testing "until the proxy has answered initialize, then sends them in order"
      (is (= 0 (get (from-proxy t) "id")))
      (is (= [(log "one") (log "two")] [(from-proxy t) (from-proxy t)]))
      (is (nothing-more? t)))))

(deftest failing-to-answer-initialize
  (let [log (lsp/notification "window/logMessage" {"type" 3 "message" "one"})
        respond (fn [_ {:strs [id method]}]
                  (when (= "initialize" method)
                    [log (lsp/response id {"capabilities" {"executeCommandProvider" {"commands" 5}}})]))
        t (start :respond respond :eager true :server-types #{"ruby"})]
    (client! t (lsp/request 0 "initialize" {"rootUri" (str "file://" (:repo t)) "capabilities" {}}))
    (testing "answers with an error, then sends what the backends sent meanwhile"
      (is (= -32603 (get-in (from-proxy t) ["error" "code"])))
      (is (= log (from-proxy t)))
      (is (nothing-more? t)))))

(def ^:private config-with-unused-value
  (str/replace config "command = 'ruby'," "command = 'ruby', image = 'unused',"))

(defn- unused-value-warning [t]
  (shown 2 (str "unused values in " (:repo t) "/.heddle.toml:\n"
                "  scope \"api\", server type \"ruby\": \"image\" isn't used by runner \"local\"")))

(deftest with-config-warnings
  (let [t (start :config config-with-unused-value)]
    (initialize! t {})
    (testing "shows them once, after answering initialize"
      (is (= (unused-value-warning t) (from-proxy t)))
      (is (nothing-more? t)))
    (testing "and still serves"
      (open! t "api/a.rb" "ruby")
      (is (eventually #(seq (methods-received t ["ruby" "api"])))))))

(deftest serving-server-types-that-arent-in-the-config
  (let [t (start :server-types #{"lint" "nosuch" "other"})]
    (initialize! t {})
    (testing "warns once, after answering initialize"
      (is (= (shown 2 (str "There's no server type \"nosuch\" or \"other\" in " (:repo t) "/.heddle.toml."))
             (from-proxy t)))
      (is (nothing-more? t)))
    (testing "and serves the rest"
      (open! t "api/a.rb" "ruby")
      (is (eventually #(seq (methods-received t ["lint" "."])))))))

(deftest with-config-warnings-starting-backends-eagerly
  (let [log (lsp/notification "window/logMessage" {"type" 3 "message" "one"})
        respond (fn [[server-type] {:strs [id method]}]
                  (when (= "initialize" method)
                    (cond-> [(lsp/response id {"capabilities" {}})]
                      (= "ruby" server-type) (->> (cons log)))))
        t (start :config config-with-unused-value :respond respond :eager true)]
    (client! t (lsp/request 0 "initialize" {"rootUri" (str "file://" (:repo t)) "capabilities" {}}))
    (testing "shows them after the answer and the backends' held messages"
      (is (= 0 (get (from-proxy t) "id")))
      (is (= log (from-proxy t)))
      (is (= (unused-value-warning t) (from-proxy t)))
      (is (nothing-more? t)))))

(defn- cache-of [t k]
  (get-in (backend-of t k) [:spec :cache]))

(deftest choosing-the-cache-root
  (testing "a path relative to the config's directory"
    (let [t (start :config (str "cache = { path = '.cache' }\n" config))]
      (initialize! t {})
      (open! t "api/a.rb" "ruby")
      (is (eventually #(cache-of t ["ruby" "api"])))
      (is (str/starts-with? (cache-of t ["ruby" "api"]) (str (:repo t) "/.cache/heddle/")))))
  (testing "an unset environment variable, which warns once after initialize and falls back"
    (let [t (start :config (str "cache = { env = 'HEDDLE_TEST_UNSET' }\n" config))]
      (initialize! t {})
      (is (= (shown 2 (str "The config's cache names $HEDDLE_TEST_UNSET, which isn't set. "
                           "Fetched files are cached in " (System/getProperty "java.io.tmpdir") " instead."))
             (from-proxy t)))
      (is (nothing-more? t))
      (open! t "api/a.rb" "ruby")
      (is (eventually #(cache-of t ["ruby" "api"])))
      (is (str/starts-with? (cache-of t ["ruby" "api"]) (System/getProperty "java.io.tmpdir"))))))

(deftest starting-backends-eagerly-with-a-failed-backend
  (let [ruby-starts (atom 0)
        respond (fn [[server-type] {:strs [id method]}]
                  (when (= "initialize" method)
                    (if (and (= "ruby" server-type) (= 1 (swap! ruby-starts inc)))
                      [(lsp/error-response id -32603 "no Gemfile")]
                      [(lsp/response id {"capabilities" {"hoverProvider" true}})])))
        t (start :respond respond :eager true)
        caps (initialize! t {})]
    (testing "answers initialize with the backends that did start"
      (is (= {} (get caps "hoverProvider"))))
    (testing "then tells the user as usual"
      (is (= (shown 1 (failure "ruby in api failed to start: initialize failed: no Gemfile")) (from-proxy t))))
    (testing "and starts it again only when one of its files opens"
      (swap! (:now t) + 30000)
      (is (nothing-more? t))
      (is (= 1 @ruby-starts))
      (open! t "api/a.rb" "ruby")
      (is (eventually #(= 2 @ruby-starts))))))

(defn- received [t k method]
  (filterv #(= method (get % "method")) (get @(:received t) k)))

(defn- ready? [t k]
  (some-> (backend-of t k) backend/ready?))

(defn- wait-until-ready
  "Waits until every live backend has started, since combined requests go
  only to those that have."
  [t]
  (is (eventually #(every? (partial ready? t) (keys (get-in @(:state (:proxy t)) [:registry :backends]))))))

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
            cache (cache-of t ["ruby" "api"])]
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

(deftest opening-a-file-after-starting-backends-eagerly
  (let [respond (fn [_ {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"hoverProvider" true}})]
                    "textDocument/hover" [(lsp/response id {"contents" "Rainbow"})]
                    nil))
        t (start :respond respond :eager true :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (testing "tells the running backend about it"
      (is (eventually #(= ["initialize" "initialized" "textDocument/didOpen"]
                          (methods-received t ["ruby" "api"])))))
    (testing "and sends it requests about it"
      (client! t (request-about t 1 "textDocument/hover" "api/a.rb"))
      (is (= (lsp/response 1 {"contents" "Rainbow"}) (from-proxy t))))))

(deftest requests-for-partial-results
  (let [t (start :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (assoc-in (request-about t 1 "textDocument/hover" "api/a.rb")
                         ["params" "partialResultToken"] "p"))
    (client! t (assoc-in (request-about t 2 "textDocument/hover" "api/a.rb")
                         ["params" "workDoneToken"] "w"))
    (is (= [(lsp/response 1 nil) (lsp/response 2 nil)] [(from-proxy t) (from-proxy t)]))
    (let [[in-parts work-done] (map #(get % "params") (received t ["ruby" "api"] "textDocument/hover"))]
      (is (not (contains? in-parts "partialResultToken"))
          "are answered in full, so that the proxy can process the answer")
      (is (= "w" (get work-done "workDoneToken")) "keep their progress token"))))

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
    (wait-until-ready t)
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

(defn- completing-once-started
  "Fake backends that offer completion, answering with their server type. The
  ruby backend answers initialize only once `started` is delivered."
  [started]
  (fn [[server-type] {:strs [id method]}]
    (case method
      "initialize" (do (when (= "ruby" server-type) @started)
                       [(lsp/response id {"capabilities" {"textDocumentSync" 1 "completionProvider" {}}})])
      "textDocument/completion" [(lsp/response id [{"label" server-type}])]
      nil)))

(defn- labels [completion-response]
  (map #(get % "label") (get-in completion-response ["result" "items"])))

(deftest combining-answers-in-order-with-changes
  (let [started (promise)
        t (start :respond (completing-once-started started))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (is (eventually #(ready? t ["lint" "."])))
    (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
    (change! t "api/a.rb" 2 "x")
    (is (eventually #(= ["textDocument/completion" "textDocument/didChange"]
                        (take-last 2 (methods-received t ["lint" "."]))))
        "reaches a ready backend before a change the client sends after it")
    (deliver started true)))

(deftest combining-answers-while-a-backend-starts
  (let [started (promise)
        t (start :respond (completing-once-started started))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (is (eventually #(ready? t ["lint" "."])))
    (testing "only from the backends that are ready, without waiting for the rest"
      (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
      (is (= ["lint"] (labels (from-proxy t))))
      (deliver started true)
      (is (eventually #(ready? t ["ruby" "api"])))
      (is (= [] (received t ["ruby" "api"] "textDocument/completion"))))
    (testing "and from a backend that has started since, from the next request on"
      (client! t (request-about t 2 "textDocument/completion" "api/a.rb"))
      (is (= ["lint" "ruby"] (labels (from-proxy t)))))))

(deftest combining-answers-while-every-backend-starts
  (let [started (promise)
        t (start :respond (completing-once-started started) :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
    (testing "waits for them to start"
      (is (nothing-more? t)))
    (testing "then answers"
      (deliver started true)
      (is (= ["ruby"] (labels (from-proxy t)))))))

(defn- backend-id [t k]
  (:backend-id (backend-of t k)))

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
    (wait-until-ready t)
    (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
    (let [items (into {} (for [item (get-in (from-proxy t) ["result" "items"])]
                           [(get item "label") item]))]
      (testing "wraps each item's data with the backend that made it"
        (is (= {"heddle/backend" (backend-id t ["lint" "."]) "data" {"n" 1}}
               (get-in items ["lint" "data"])))
        (is (= {"heddle/backend" (backend-id t ["lint" "."])}
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
        (let [item (assoc-in (get items "lint") ["data" "heddle/backend"] "heddle-gone")]
          (client! t (lsp/request 5 "completionItem/resolve" item))
          (is (= (lsp/response 5 item) (from-proxy t))))))))

(deftest resolving-items-with-data-from-their-list
  (let [respond (fn [[server-type] {:strs [id method params]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"completionProvider" {"resolveProvider" (= "lint" server-type)}}})]
                    "textDocument/completion" [(lsp/response id {"isIncomplete" false
                                                                 "itemDefaults" {"data" {"n" 1}}
                                                                 "items" [{"label" server-type}]})]
                    "completionItem/resolve" [(lsp/response id params)]
                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (wait-until-ready t)
    (client! t (request-about t 1 "textDocument/completion" "api/a.rb"))
    (let [lint (first (filter #(= "lint" (get % "label")) (get-in (from-proxy t) ["result" "items"])))]
      (client! t (lsp/request 2 "completionItem/resolve" lint))
      (from-proxy t)
      (is (= {"label" "lint" "data" {"n" 1}}
             (get-in (received t ["lint" "."] "completionItem/resolve") [0 "params"]))))))

(deftest following-hierarchies
  (let [respond (fn [[server-type] {:strs [id method params]}]
                  (when id
                    [(lsp/response
                      id
                      (case [server-type method]
                        ["ruby" "initialize"] {"capabilities" {"callHierarchyProvider" true
                                                               "typeHierarchyProvider" true}}
                        ["lint" "initialize"] {"capabilities" {}}
                        (["ruby" "textDocument/prepareCallHierarchy"] ["ruby" "textDocument/prepareTypeHierarchy"])
                        [{"name" "a" "uri" (get-in params ["textDocument" "uri"]) "data" {"n" 1}}]
                        ["ruby" "callHierarchy/incomingCalls"]
                        [{"from" (update (get params "item") "name" #(str "caller of " %)) "fromRanges" []}]
                        ["ruby" "typeHierarchy/supertypes"]
                        [(update (get params "item") "name" #(str "super of " %))]
                        nil))]))
        t (start :respond respond)
        follow (fn [id method item]
                 (client! t (lsp/request id method {"item" item}))
                 (from-proxy t))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (request-about t 1 "textDocument/prepareCallHierarchy" "api/a.rb"))
    (let [[item] (get (from-proxy t) "result")
          ruby (backend-id t ["ruby" "api"])]
      (testing "marks prepared items with the backend that made them"
        (is (= {"name" "a" "uri" (uri t "api/a.rb") "data" {"heddle/backend" ruby "data" {"n" 1}}}
               item)))
      (testing "sends follow-ups to that backend, with the item as it made it"
        (let [[call] (get (follow 2 "callHierarchy/incomingCalls" item) "result")]
          (is (= {"name" "a" "uri" "file:///app/a.rb" "data" {"n" 1}}
                 (get-in (received t ["ruby" "api"] "callHierarchy/incomingCalls") [0 "params" "item"])))
          (is (= "caller of a" (get-in call ["from" "name"])))
          (testing "and marks the items in their answers, so they can be followed further"
            (is (= "caller of caller of a"
                   (get-in (follow 3 "callHierarchy/incomingCalls" (get call "from")) ["result" 0 "from" "name"]))))))
      (testing "follows type hierarchies the same way"
        (client! t (request-about t 4 "textDocument/prepareTypeHierarchy" "api/a.rb"))
        (let [[item] (get (from-proxy t) "result")
              [super] (get (follow 5 "typeHierarchy/supertypes" item) "result")]
          (is (= "super of super of a"
                 (get-in (follow 6 "typeHierarchy/supertypes" super) ["result" 0 "name"])))))
      (testing "answers with null for an item no ready backend made"
        (is (= (lsp/response 7 nil) (follow 7 "callHierarchy/outgoingCalls" (dissoc item "data"))))
        (is (= (lsp/response 8 nil)
               (follow 8 "callHierarchy/outgoingCalls"
                       (assoc-in item ["data" "heddle/backend"] "heddle-gone"))))))))

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
    (open! t "Gemfile" "plaintext")
    (is (= "client/registerCapability" (get (from-proxy t) "method")))
    (open! t "api/a.rb" "ruby")
    (testing "registers every backend's commands qualified with its server type"
      (is (= "client/unregisterCapability" (get (from-proxy t) "method")))
      (is (= ["lint:fix" "ruby:fix"]
             (get-in (from-proxy t) ["params" "registrations" 0 "registerOptions" "commands"]))))
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
        t (start :respond respond :server-types #{"ruby"} :config ruby-in-two-scopes)
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

(deftest running-commands-that-one-scope-offers
  (let [respond (fn [[_ scope] {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" (if (= "api" scope)
                                                                      {"executeCommandProvider" {"commands" ["fix"]}}
                                                                      {})})]
                    "textDocument/didOpen" (when (= "." scope)
                                             [(lsp/request 1 "client/registerCapability"
                                                           {"registrations" [{"id" "commands" "method" "workspace/executeCommand"
                                                                              "registerOptions" {"commands" ["foo"]}}]})])
                    "workspace/executeCommand" [(lsp/response id scope)]
                    nil))
        t (start :respond respond :server-types #{"ruby"} :config ruby-in-two-scopes)
        execute (fn [id command & arguments]
                  (client! t (lsp/request id "workspace/executeCommand" {"command" command "arguments" (vec arguments)}))
                  (get (from-proxy t) "result"))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (open! t "b.rb" "ruby")
    (is (eventually #(seq (received t ["ruby" "api"] "textDocument/didOpen"))))
    (is (eventually #(some #{(lsp/response 1 nil)} (get @(:received t) ["ruby" "."]))))
    (testing "go to the backend that announced the command, whichever file the arguments name"
      (is (= "api" (execute 1 "ruby:fix" {"uri" (uri t "b.rb")})))
      (is (= "api" (execute 2 "ruby:fix"))))
    (testing "or to the backend that registered it"
      (is (= "." (execute 3 "ruby:foo" {"uri" (uri t "api/a.rb")}))))))

(defn- stop-backend! [t k]
  ((get-in @(:state (backend-of t k)) [:conn :close])))

(deftest publishing-diagnostics
  (let [respond (fn [[server-type] {:strs [id method params]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {}})]
                    "textDocument/didOpen" [(lsp/notification "textDocument/publishDiagnostics"
                                                              {"uri" (get-in params ["textDocument" "uri"])
                                                               "diagnostics" [{"message" server-type}]})]
                    nil))
        t (start :respond respond)
        messages (fn [msg] (set (map #(get % "message") (get-in msg ["params" "diagnostics"]))))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (testing "publishes every backend's latest diagnostics for a file together"
      (let [published [(from-proxy t) (from-proxy t)]]
        (is (= #{(uri t "api/a.rb")} (set (map #(get-in % ["params" "uri"]) published))))
        (is (= #{"ruby" "lint"} (messages (peek published))))))
    (testing "withdraws a backend's diagnostics once it stops"
      (stop-backend! t ["lint" "."])
      (is (= #{"ruby"} (messages (from-proxy t)))))))

(deftest publishing-diagnostics-about-fetched-files
  (let [gem (str (temp-dir) "/gems/rainbow.rb")
        respond (fn [_ {:strs [id method params]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {}})]
                    "textDocument/didOpen" [(lsp/notification "textDocument/publishDiagnostics"
                                                              {"uri" (get-in params ["textDocument" "uri"])
                                                               "diagnostics" [{"message" "m"
                                                                               "relatedInformation" [{"location" {"uri" (str "file://" gem)}
                                                                                                      "message" "defined here"}]}]})]
                    nil))
        t (start :respond respond :server-types #{"ruby"})]
    (io/make-parents gem)
    (spit gem "module Rainbow; end\n")
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (let [published (from-proxy t)
          cache (cache-of t ["ruby" "api"])]
      (is (= (str "file://" cache gem)
             (get-in published ["params" "diagnostics" 0 "relatedInformation" 0 "location" "uri"])))
      (is (= "module Rainbow; end\n" (slurp (str cache gem)))
          "fetches the files that diagnostics refer to"))))

(deftest pulling-diagnostics
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"diagnosticProvider" {}}})]
                    "textDocument/diagnostic" [(lsp/response id {"kind" "full" "resultId" server-type
                                                                 "items" [{"message" server-type}]})]
                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (wait-until-ready t)
    (client! t (lsp/request 1 "textDocument/diagnostic" {"textDocument" {"uri" (uri t "api/a.rb")}}))
    (is (= (lsp/response 1 {"kind" "full" "items" [{"message" "lint"} {"message" "ruby"}]})
           (from-proxy t)))))

(deftest searching-the-workspace
  (let [respond (fn [[server-type] {:strs [id method params]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"workspaceSymbolProvider" {"resolveProvider" true}}})]
                    "workspace/symbol" [(lsp/response id [{"name" server-type}])]
                    "workspaceSymbol/resolve" [(lsp/response id (assoc params "containerName" server-type))]
                    nil))
        t (start :respond respond)]
    (initialize! t {})
    (client! t (lsp/request 1 "workspace/symbol" {"query" "x"}))
    (testing "answers with nothing before any backend has started"
      (is (= (lsp/response 1 nil) (from-proxy t))))
    (open! t "api/a.rb" "ruby")
    (wait-until-ready t)
    (client! t (lsp/request 2 "workspace/symbol" {"query" "x"}))
    (let [symbols (get (from-proxy t) "result")]
      (testing "combines every live backend's symbols"
        (is (= ["lint" "ruby"] (map #(get % "name") symbols))))
      (testing "resolves a symbol with the backend that found it"
        (client! t (lsp/request 3 "workspaceSymbol/resolve" (second symbols)))
        (is (= "ruby" (get-in (from-proxy t) ["result" "containerName"])))))))

(deftest workspace-notifications
  (let [t (start)
        received-params (fn [k method] (mapv #(get % "params") (received t k method)))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (testing "configuration changes go to every live backend"
      (client! t (lsp/notification "workspace/didChangeConfiguration" {"settings" {}}))
      (is (eventually #(= [{"settings" {}}] (received-params ["ruby" "api"] "workspace/didChangeConfiguration"))))
      (is (eventually #(= [{"settings" {}}] (received-params ["lint" "."] "workspace/didChangeConfiguration")))))
    (testing "watched file changes go to the backends whose scopes contain them"
      (client! t (lsp/notification "workspace/didChangeWatchedFiles"
                                   {"changes" [{"uri" (uri t "api/b.rb") "type" 1}
                                               {"uri" (uri t "Gemfile") "type" 2}]}))
      (is (eventually #(= [{"changes" [{"uri" (uri t "api/b.rb") "type" 1}
                                       {"uri" (uri t "Gemfile") "type" 2}]}]
                          (received-params ["lint" "."] "workspace/didChangeWatchedFiles"))))
      (is (eventually #(= [{"changes" [{"uri" "file:///app/b.rb" "type" 1}]}]
                          (received-params ["ruby" "api"] "workspace/didChangeWatchedFiles")))))))

(deftest requests-with-a-failed-backend
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (case [server-type method]
                    ["lint" "initialize"] [(lsp/error-response id -32603 "no config")]
                    ["ruby" "initialize"] [(lsp/response id {"capabilities" {"definitionProvider" true}})]
                    ["ruby" "textDocument/definition"] [(lsp/response id "from ruby")]
                    nil))
        t (start :respond respond)
        lint-failed (failure "lint in the repo root failed to start: initialize failed: no config")]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (open! t "Gemfile" "plaintext")
    (is (= (shown 1 lint-failed) (from-proxy t)))
    (testing "go to another backend that can answer"
      (client! t (request-about t 1 "textDocument/definition" "api/a.rb"))
      (is (= (lsp/response 1 "from ruby") (from-proxy t))))
    (testing "get an empty result while another backend for the file is running"
      (client! t (request-about t 2 "textDocument/hover" "api/a.rb"))
      (is (= (lsp/response 2 nil) (from-proxy t))))
    (testing "get an error saying why when no backend for the file is running"
      (client! t (request-about t 3 "textDocument/hover" "Gemfile"))
      (is (= (lsp/error-response 3 -32803 lint-failed) (from-proxy t)))
      (client! t (request-about t 4 "textDocument/completion" "Gemfile"))
      (is (= (lsp/error-response 4 -32803 lint-failed) (from-proxy t))))
    (testing "get an error saying why for a command whose backends have failed"
      (client! t (lsp/request 5 "workspace/executeCommand" {"command" "lint:fix"}))
      (is (= (lsp/error-response 5 -32803 lint-failed) (from-proxy t))))))

(deftest requests-with-every-backend-failed
  (let [t (start :refuse {["ruby" "api"] "ruby: command not found"
                          ["lint" "."] "lint: command not found"})
        ruby-failed (failure "ruby in api failed to start: ruby: command not found")
        lint-failed (failure "lint in the repo root failed to start: lint: command not found")]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (is (= #{(shown 1 ruby-failed) (shown 1 lint-failed)} (set [(from-proxy t) (from-proxy t)])))
    (client! t (request-about t 1 "textDocument/hover" "api/a.rb"))
    (is (= (lsp/error-response 1 -32803 (str lint-failed "\n" ruby-failed)) (from-proxy t)))))

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

(deftest backends-own-registrations
  (let [respond (fn [_ {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {}})]
                    "textDocument/didOpen" [(lsp/request 1 "client/registerCapability"
                                                         {"registrations" [{"id" "f" "method" "textDocument/formatting"
                                                                            "registerOptions" {"documentSelector" [{"language" "ruby"}]}}]})]
                    nil))
        t (start :respond respond :server-types #{"ruby"})]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (is (some #{{"language" "ruby" "pattern" {"baseUri" (uri t "api") "pattern" "**/*"}}}
              (get-in (from-proxy t) ["params" "registrations" 0 "registerOptions" "documentSelector"]))
        "are limited to the backend's files")))

(defn- registered-methods
  "The methods of the registrations in the client/registerCapability `msg`."
  [msg]
  (map #(get % "method") (get-in msg ["params" "registrations"])))

(deftest withdrawing-registrations-when-a-backend-stops
  (let [watch {"id" "watch" "method" "workspace/didChangeWatchedFiles"}
        respond (fn [_ {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {"textDocumentSync" 1 "hoverProvider" true}})]
                    "textDocument/didOpen" [(lsp/request 1 "client/registerCapability" {"registrations" [watch]})]
                    "textDocument/didChange" [(lsp/request 2 "client/unregisterCapability" {"unregisterations" [watch]})]
                    nil))
        t (start :respond respond :server-types #{"ruby"} :config ruby-in-two-scopes)
        root-files {"language" "ruby" "pattern" {"baseUri" (str "file://" (:repo t)) "pattern" "**/*"}}
        api-files {"language" "ruby" "pattern" {"baseUri" (uri t "api") "pattern" "**/*"}}
        hover-selector (fn [msgs]
                         (some #(when (= ["textDocument/hover"] (registered-methods %))
                                  (get-in % ["params" "registrations" 0 "registerOptions" "documentSelector"]))
                               msgs))
        unregistered (fn [msg]
                       (is (= "client/unregisterCapability" (get msg "method")))
                       (get-in msg ["params" "unregisterations"]))]
    (initialize! t {"textDocument" {"hover" {"dynamicRegistration" true}}})
    (open! t "api/a.rb" "ruby")
    (is (= #{["textDocument/hover"] ["workspace/didChangeWatchedFiles"]}
           (set (map registered-methods [(from-proxy t) (from-proxy t)]))))
    (open! t "b.rb" "ruby")
    (let [msgs [(from-proxy t) (from-proxy t) (from-proxy t)]]
      (is (= [[{"id" "textDocument/hover" "method" "textDocument/hover"}]]
             (keep #(get-in % ["params" "unregisterations"]) msgs)))
      (is (some #{root-files} (hover-selector msgs))))
    (testing "a backend that stops has what it registered itself withdrawn"
      (let [qualified-watch (update watch "id" #(str (backend-id t ["ruby" "."]) "/" %))]
        (stop-backend! t ["ruby" "."])
        (is (= [qualified-watch] (unregistered (from-proxy t))))))
    (testing "and the proxy's registrations are registered again without it"
      (is (= [{"id" "textDocument/hover" "method" "textDocument/hover"}] (unregistered (from-proxy t))))
      (let [selector (hover-selector [(from-proxy t)])]
        (is (some #{api-files} selector))
        (is (not-any? #{root-files} selector))))
    (is (= 2 (get-in (from-proxy t) ["params" "type"])) "before telling the user")
    (testing "a backend that unregisters what it registered has nothing left to withdraw"
      (change! t "api/a.rb" 2 "")
      (is (= 1 (count (unregistered (from-proxy t)))))
      (stop-backend! t ["ruby" "api"])
      (testing "while the proxy's registration of a feature no backend offers any more is withdrawn"
        (is (= [{"id" "textDocument/hover" "method" "textDocument/hover"}] (unregistered (from-proxy t)))))
      (is (= 2 (get-in (from-proxy t) ["params" "type"])))
      (is (nothing-more? t)))))

(defn- registers-commands
  "Fake backends that register `commands` themselves once a file opens, and
  unregister them once it changes."
  [commands]
  (let [registration {"id" "commands" "method" "workspace/executeCommand"}]
    (fn [_ {:strs [id method]}]
      (case method
        "initialize" [(lsp/response id {"capabilities" {"textDocumentSync" 1 "executeCommandProvider" {"commands" ["fix"]}}})]
        "textDocument/didOpen" [(lsp/request 1 "client/registerCapability"
                                             {"registrations" [(assoc registration "registerOptions" {"commands" commands})]})]
        "textDocument/didChange" [(lsp/request 2 "client/unregisterCapability" {"unregisterations" [registration]})]
        nil))))

(defn- answered? [t k id]
  (eventually #(some #{(lsp/response id nil)} (get @(:received t) k))))

(deftest merging-commands-that-backends-register
  (let [t (start :respond (registers-commands ["foo"]) :server-types #{"ruby"} :config ruby-in-two-scopes)
        commands-registration (fn [commands]
                                {"registrations" [{"id" "workspace/executeCommand" "method" "workspace/executeCommand"
                                                   "registerOptions" {"commands" commands}}]})
        unregistered {"unregisterations" [{"id" "workspace/executeCommand" "method" "workspace/executeCommand"}]}]
    (initialize! t {"workspace" {"executeCommand" {"dynamicRegistration" true}}})
    (open! t "api/a.rb" "ruby")
    (is (= (commands-registration ["ruby:fix"]) (get (from-proxy t) "params")))
    (testing "into the proxy's own registration, answering the backend"
      (is (= unregistered (get (from-proxy t) "params")))
      (is (= (commands-registration ["ruby:fix" "ruby:foo"]) (get (from-proxy t) "params")))
      (is (answered? t ["ruby" "api"] 1)))
    (testing "leaving out those the backend unregisters"
      (change! t "api/a.rb" 2 "")
      (is (= unregistered (get (from-proxy t) "params")))
      (is (= (commands-registration ["ruby:fix"]) (get (from-proxy t) "params")))
      (is (answered? t ["ruby" "api"] 2))
      (open! t "api/b.rb" "ruby")
      (is (= unregistered (get (from-proxy t) "params")))
      (is (= (commands-registration ["ruby:fix" "ruby:foo"]) (get (from-proxy t) "params"))))
    (testing "so the client sees a command once, however many backends register it"
      (open! t "b.rb" "ruby")
      (is (answered? t ["ruby" "."] 1))
      (is (nothing-more? t)))
    (testing "and keeps it while any backend that registered it is running"
      (stop-backend! t ["ruby" "."])
      (is (= 2 (get-in (from-proxy t) ["params" "type"])))
      (is (nothing-more? t)))
    (testing "until the last one stops"
      (stop-backend! t ["ruby" "api"])
      (is (= unregistered (get (from-proxy t) "params")))
      (is (= 2 (get-in (from-proxy t) ["params" "type"])))
      (is (nothing-more? t)))))

(deftest merging-commands-that-backends-register-after-announcing-commands
  (let [t (start :respond (registers-commands ["fix" "foo"]) :eager true)]
    (is (= {"commands" ["lint:fix" "ruby:fix"]}
           (get (initialize! t {"workspace" {"executeCommand" {"dynamicRegistration" true}}}) "executeCommandProvider")))
    (open! t "Gemfile" "plaintext")
    (is (= [{"id" "workspace/executeCommand" "method" "workspace/executeCommand"
             "registerOptions" {"commands" ["lint:foo"]}}]
           (get-in (from-proxy t) ["params" "registrations"]))
        "registers only the commands the answer to initialize didn't list")
    (is (nothing-more? t))))

(deftest passing-on-the-rest-of-a-request-that-registers-commands
  (let [respond (fn [_ {:strs [id method]}]
                  (case method
                    "initialize" [(lsp/response id {"capabilities" {}})]
                    "textDocument/didOpen" [(lsp/request 1 "client/registerCapability"
                                                         {"registrations" [{"id" "commands" "method" "workspace/executeCommand"
                                                                            "registerOptions" {"commands" ["foo"]}}
                                                                           {"id" "watch" "method" "workspace/didChangeWatchedFiles"}]})]
                    nil))
        t (start :respond respond :server-types #{"ruby"})]
    (initialize! t {"workspace" {"executeCommand" {"dynamicRegistration" true}}})
    (open! t "api/a.rb" "ruby")
    (let [merged (from-proxy t)
          passed (from-proxy t)]
      (is (= [["workspace/executeCommand"] ["workspace/didChangeWatchedFiles"]]
             (map registered-methods [merged passed])))
      (client! t (lsp/response (get passed "id") nil))
      (is (answered? t ["ruby" "api"] 1) "with the client's answer to the backend"))))

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

(deftest shutting-down-a-backend-that-never-answers
  (let [respond (fn [_ {:strs [id method]}]
                  (when (= "initialize" method)
                    [(lsp/response id {"capabilities" {}})]))
        t (start :respond respond :server-types #{"ruby"})
        ;; each reading of the clock is the whole shutdown timeout later
        t (update t :proxy assoc :clock #(swap! (:now t) + 10000))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (client! t (lsp/request 9 "shutdown" nil))
    (is (= (lsp/response 9 nil) (from-proxy t)) "answers once the proxy's clock passes the deadline")))

(deftest exiting-without-shutting-down
  (let [t (start)]
    (client! t (lsp/notification "exit" nil))
    (is (= 1 (deref (:exit-code t) 2000 :timeout)))))

(deftest exiting-when-stopping-a-backend-fails
  (let [logged (atom [])
        previous-writer (log/set-writer! #(swap! logged conj %))
        t (start :server-types #{"ruby"})
        connect (:connect (:proxy t))
        t (update t :proxy assoc :connect
                  (fn [b on-message]
                    (assoc (connect b on-message) :wait (fn [_] (throw (ex-info "can't wait" {}))))))]
    (try
      (initialize! t {})
      (open! t "api/a.rb" "ruby")
      (is (eventually #(seq (received t ["ruby" "api"] "textDocument/didOpen"))))
      (client! t (lsp/notification "exit" nil))
      (is (= 1 (deref (:exit-code t) 2000 :timeout)))
      (is (some #(str/includes? % "can't wait") @logged))
      (finally
        (log/set-writer! previous-writer)))))

(defn- start-ruby!
  "Initializes the proxy `t` and opens a file that only ruby in api handles."
  [t]
  (initialize! t {})
  (open! t "api/a.rb" "ruby"))

(deftest telling-the-user-about-failures
  (testing "when a backend can't be connected to"
    (let [t (start :server-types #{"ruby"} :refuse {["ruby" "api"] "ruby: command not found"})]
      (start-ruby! t)
      (is (= (shown 1 (failure "ruby in api failed to start: ruby: command not found")) (from-proxy t)))))
  (testing "when a backend answers initialize with an error"
    (let [t (start :server-types #{"ruby"}
                   :respond (fn [_ {:strs [id]}] [(lsp/error-response id -32603 "no Gemfile")]))]
      (start-ruby! t)
      (is (= (shown 1 (failure "ruby in api failed to start: initialize failed: no Gemfile")) (from-proxy t)))))
  (testing "when a backend exits before answering initialize"
    (let [t (start :server-types #{"ruby"} :respond (constantly [fake/hang-up]))]
      (start-ruby! t)
      (is (= (shown 1 (failure "ruby in api failed to start: it exited with 0 before answering initialize"))
             (from-proxy t)))))
  (testing "when a backend doesn't answer initialize in time, answering the requests that waited for it"
    (let [t (start :server-types #{"ruby"} :start-timeout-ms 100 :respond (constantly nil))
          ruby-failed (failure "ruby in api failed to start: it didn't answer initialize within 100 ms")]
      (start-ruby! t)
      (client! t (request-about t 1 "textDocument/hover" "api/a.rb"))
      (is (= #{(shown 1 ruby-failed) (lsp/error-response 1 -32803 ruby-failed)}
             (set [(from-proxy t) (from-proxy t)])))))
  (testing "when a running backend stops"
    (let [t (start :server-types #{"ruby"})]
      (start-ruby! t)
      (is (eventually #(seq (received t ["ruby" "api"] "textDocument/didOpen"))))
      (stop-backend! t ["ruby" "api"])
      (is (= (shown 2 (failure "ruby in api stopped with exit code 0")) (from-proxy t)))))
  (testing "naming the repo root for a scope at the root"
    (let [t (start :server-types #{"lint"} :refuse {["lint" "."] "lint: command not found"})]
      (initialize! t {})
      (open! t "Gemfile" "plaintext")
      (is (= (shown 1 (failure "lint in the repo root failed to start: lint: command not found"))
             (from-proxy t))))))

(def ^:private fails-initialize
  (fn [_ {:strs [id method]}]
    (when (= "initialize" method)
      [(lsp/error-response id -32603 "no Gemfile")])))

(deftest telling-a-backend-that-failed-initialize-to-exit
  (let [t (start :server-types #{"ruby"} :respond fails-initialize)]
    (start-ruby! t)
    (is (eventually #(seq (received t ["ruby" "api"] "exit"))))))

(deftest retrying-failed-backends
  (testing "that failed to start, on a didOpen at least 30 s later"
    (let [t (start :server-types #{"ruby"} :respond fails-initialize)
          initializes #(count (received t ["ruby" "api"] "initialize"))]
      (start-ruby! t)
      (is (= 1 (get-in (from-proxy t) ["params" "type"])))
      (swap! (:now t) + 29999)
      (open! t "api/a.rb" "ruby")
      (is (= 1 (initializes)))
      (swap! (:now t) inc)
      (open! t "api/a.rb" "ruby")
      (is (eventually #(= 2 (initializes))))))
  (testing "that stopped, on a didOpen at least 30 s later"
    (let [t (start :server-types #{"ruby"})
          opens #(count (received t ["ruby" "api"] "textDocument/didOpen"))]
      (start-ruby! t)
      (is (eventually #(= 1 (opens))))
      (stop-backend! t ["ruby" "api"])
      (is (= 2 (get-in (from-proxy t) ["params" "type"])))
      (swap! (:now t) + 29999)
      (open! t "api/a.rb" "ruby")
      (is (= 1 (opens)))
      (swap! (:now t) inc)
      (open! t "api/a.rb" "ruby")
      (is (eventually #(= 2 (opens)))))))

(defn- opened-documents
  "Each document the backend `k` received `didOpen` for, by URI."
  [t k]
  (into {} (for [msg (received t k "textDocument/didOpen")
                 :let [document (get-in msg ["params" "textDocument"])]]
             [(get document "uri") document])))

(deftest files-opened-before-a-backend-started
  (testing "are opened in a backend that restarted, as last changed, before anything else"
    (let [t (start :server-types #{"ruby"})]
      (start-ruby! t)
      (change! t "api/a.rb" 2 "changed")
      (is (eventually #(seq (received t ["ruby" "api"] "textDocument/didChange"))))
      (stop-backend! t ["ruby" "api"])
      (is (= 2 (get-in (from-proxy t) ["params" "type"])))
      (reset! (:received t) {})
      (swap! (:now t) + 30000)
      (open! t "api/b.rb" "ruby")
      (client! t (request-about t 1 "textDocument/hover" "api/a.rb"))
      (is (= (lsp/response 1 nil) (from-proxy t)))
      (is (= ["initialize" "initialized" "textDocument/didOpen" "textDocument/didOpen" "textDocument/hover"]
             (methods-received t ["ruby" "api"])))
      (is (= {"file:///app/a.rb" {"uri" "file:///app/a.rb" "languageId" "ruby" "version" 2 "text" "changed"}
              "file:///app/b.rb" {"uri" "file:///app/b.rb" "languageId" "ruby" "version" 1 "text" ""}}
             (opened-documents t ["ruby" "api"])))))
  (testing "are opened in a backend that another file starts after it failed"
    (let [starts (atom 0)
          respond (fn [_ {:strs [id method]}]
                    (when (= "initialize" method)
                      (if (= 1 (swap! starts inc))
                        [(lsp/error-response id -32603 "no Gemfile")]
                        [(lsp/response id {"capabilities" {"hoverProvider" true}})])))
          t (start :server-types #{"ruby"} :respond respond)]
      (start-ruby! t)
      (is (= 1 (get-in (from-proxy t) ["params" "type"])))
      (open! t "api/b.rb" "ruby")
      (swap! (:now t) + 30000)
      (open! t "api/c.rb" "ruby")
      (is (eventually #(= #{"file:///app/a.rb" "file:///app/b.rb" "file:///app/c.rb"}
                          (set (keys (opened-documents t ["ruby" "api"]))))))
      (is (= 3 (count (received t ["ruby" "api"] "textDocument/didOpen"))) "once each"))))

(deftest changing-a-file
  (let [respond (fn [[server-type] {:strs [id method]}]
                  (when (= "initialize" method)
                    [(lsp/response id {"capabilities" {"textDocumentSync" (if (= "ruby" server-type)
                                                                            {"openClose" true "change" 2}
                                                                            1)}})]))
        t (start :respond respond)
        changes (fn [k] (map #(get-in % ["params" "contentChanges"]) (received t k "textDocument/didChange")))]
    (initialize! t {})
    (open! t "api/a.rb" "ruby")
    (change! t "api/a.rb" 2 "x = 1\n")
    (change! t "api/a.rb" 3 "x = 2")
    (testing "sends a backend that syncs incrementally a change of the whole of the previous text"
      (is (eventually #(= [[{"range" {"start" {"line" 0 "character" 0} "end" {"line" 0 "character" 0}} "text" "x = 1\n"}]
                           [{"range" {"start" {"line" 0 "character" 0} "end" {"line" 1 "character" 0}} "text" "x = 2"}]]
                          (changes ["ruby" "api"])))))
    (testing "sends a backend that syncs in full the whole text"
      (is (eventually #(= [[{"text" "x = 1\n"}] [{"text" "x = 2"}]]
                          (changes ["lint" "."])))))))

(deftest telling-the-user-about-a-backend-at-most-once-a-minute
  (let [t (start :server-types #{"ruby"})
        logged (atom [])
        previous-writer (log/set-writer! #(swap! logged conj %))
        stops (fn [] (count (filter #(str/includes? % "ruby in api stopped") @logged)))
        opens #(count (received t ["ruby" "api"] "textDocument/didOpen"))
        start-and-stop! (fn []
                          (let [opened (opens)
                                stopped (stops)]
                            (open! t "api/a.rb" "ruby")
                            (is (eventually #(= (inc opened) (opens))))
                            (stop-backend! t ["ruby" "api"])
                            (is (eventually #(= (inc stopped) (stops))))))]
    (try
      (initialize! t {})
      (start-and-stop!)
      (is (= 2 (get-in (from-proxy t) ["params" "type"])))
      (testing "logs without showing a message within the minute"
        (swap! (:now t) + 30000)
        (start-and-stop!)
        (is (nil? (.poll (:out t) 0 TimeUnit/MILLISECONDS))))
      (testing "shows one once the minute has passed"
        (swap! (:now t) + 30000)
        (start-and-stop!)
        (is (= 2 (get-in (from-proxy t) ["params" "type"]))))
      (finally
        (log/set-writer! previous-writer)))))
