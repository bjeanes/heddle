(ns monorepo-lsp.backend-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.backend :as backend]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.fake-backend :as fake :refer [eventually]]
            [monorepo-lsp.lsp :as lsp])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private config
  (config/parse "
[servers.s]
languages = ['x']

[runners.cat]
prepare = ['touch', 'prepared-{proxy:backend_id}']
start = ['cat']

[scopes.'.']
s = { runner = 'host', command = 's', paths = { '.' = '/src' } }

[scopes.lib]
s = { runner = 'cat' }
"))

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

(defn- methods-received [received]
  (mapv #(get % "method") (get @received ["s" "."])))

(deftest backend-ids
  (let [id (backend/new-id)]
    (is (re-matches #"monorepo-lsp-[0-9a-f]{8}" id))
    (is (not= id (backend/new-id)))))

(deftest describing-a-backend
  (let [b (backend/backend config "s" "." "/r")]
    (is (= {"." "/src"} (:paths b)))
    (is (= "/src" (:root b)))
    (is (= :starting (backend/status b)))))

(deftest starting
  (let [received (atom {})
        answer-initialize (promise)
        respond (fn [_ msg]
                  (when (= "initialize" (get msg "method"))
                    @answer-initialize
                    [(lsp/response (get msg "id") {"capabilities" {"hoverProvider" true}})]))
        b (backend/start! (backend/backend config "s" "." "/r")
                          (fake/connector received respond)
                          {"rootUri" "file:///src"}
                          (fn [_]))]
    (backend/when-ready b #(backend/notify! b "first" nil))
    (backend/when-ready b #(backend/notify! b "second" nil))
    (testing "sends only initialize until it's answered"
      (is (eventually #(= ["initialize"] (methods-received received))))
      (is (= {"rootUri" "file:///src"} (get-in @received [["s" "."] 0 "params"])))
      (is (= :starting (backend/status b))))
    (testing "then initialized and everything queued, in order"
      (deliver answer-initialize true)
      (is (eventually #(= ["initialize" "initialized" "first" "second"] (methods-received received))))
      (is (backend/ready? b))
      (is (= {"hoverProvider" true} (backend/capabilities b))))
    (testing "sends straight away once it has started"
      (backend/when-ready b #(backend/notify! b "third" nil))
      (is (eventually #(= "third" (peek (methods-received received))))))))

(deftest failing-to-start
  (let [received (atom {})
        respond (fn [_ msg]
                  [(lsp/error-response (get msg "id") -32603 "no Gemfile")])
        b (backend/backend config "s" "." "/r")
        ran (promise)]
    (backend/when-ready b #(deliver ran (backend/status b)))
    (backend/start! b (fake/connector received respond) {} (fn [_]))
    (is (= :failed (deref ran 2000 :timeout)) "queued functions still run")
    (is (= "initialize failed: no Gemfile" (:error @(:state b))))
    (is (= ["initialize"] (methods-received received)))))

(deftest requests
  (let [stopped (promise)
        respond (fn [_ msg]
                  (case (get msg "method")
                    "initialize" [(lsp/response (get msg "id") {"capabilities" {}})]
                    "echo" [(lsp/response (get msg "id") (get msg "params"))]
                    nil))
        b (backend/start! (backend/backend config "s" "." "/r")
                          (fake/connector (atom {}) respond)
                          {}
                          #(when (nil? %) (deliver stopped true)))]
    (is (eventually #(backend/ready? b)))
    (testing "a response goes to its request's callback"
      (let [response (promise)]
        (backend/request! b "echo" {"x" 1} #(deliver response %))
        (is (= {"x" 1} (get (deref response 2000 nil) "result")))))
    (testing "an open request can be found by its tag"
      (let [id (backend/request! b "unanswered" nil "client-7" (fn [_]))]
        (is (= id (backend/tagged-request b "client-7")))
        (is (nil? (backend/tagged-request b "client-8")))))
    (testing "once the backend stops, its open requests are answered with nil"
      (let [response (promise)]
        (backend/request! b "unanswered" nil #(deliver response %))
        (is (= 0 (backend/exit! b 1000)))
        (is (= true (deref stopped 2000 :timeout)))
        (is (nil? (deref response 2000 :timeout)))
        (is (= :exited (backend/status b)))))
    (testing "and so are later ones"
      (let [response (promise)]
        (backend/request! b "late" nil #(deliver response %))
        (is (nil? (deref response 0 :timeout)))))))

(deftest a-real-process
  (let [repo (temp-dir)
        lib (io/file repo "lib")
        messages (java.util.concurrent.LinkedBlockingQueue.)
        b (backend/backend config "s" "lib" repo)]
    (.mkdirs lib)
    (let [conn (backend/connect-process b #(.put messages (or % :stopped)))]
      (testing "runs prepare then start in the scope's directory"
        (is (.exists (io/file lib (str "prepared-" (:backend-id b))))))
      (testing "frames messages both ways"
        ((:send conn) {"jsonrpc" "2.0" "method" "echo" "params" ["😀"]})
        (is (= {"jsonrpc" "2.0" "method" "echo" "params" ["😀"]}
               (.poll messages 2 java.util.concurrent.TimeUnit/SECONDS))))
      (testing "stops once its input is closed"
        ((:close conn))
        (is (= :stopped (.poll messages 2 java.util.concurrent.TimeUnit/SECONDS)))
        (is (= 0 ((:wait conn) 2000)))))))
