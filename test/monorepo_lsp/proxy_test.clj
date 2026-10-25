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
ruby = { runner = 'host', command = 'ruby', paths = { 'api' = '/app' } }
")

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

(def ^:private offers-hover
  (fake/answering {"initialize" (constantly {"capabilities" {"hoverProvider" true}})}))

(defn- start
  "A proxy for a repo with `config`, with fake backends that answer with
  `respond`. Returns the proxy and its repo, a queue of what it sends the
  client, and an atom of what each backend receives."
  [& {:keys [respond server-types]}]
  (let [repo (temp-dir)
        out (LinkedBlockingQueue.)
        received (atom {})]
    (spit (io/file repo ".monorepo-lsp.toml") config)
    {:repo repo
     :out out
     :received received
     :proxy (proxy/create {:send #(.put out %)
                           :connect (fake/connector received (or respond offers-hover))
                           :server-types server-types
                           :cwd (temp-dir)})}))

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
