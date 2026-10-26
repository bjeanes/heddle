(ns heddle.trace-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heddle.json :as json]
            [heddle.log :as log]
            [heddle.lsp :as lsp]
            [heddle.main :as main]
            [heddle.proxy :as proxy]
            [heddle.trace :as trace])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private ping (lsp/request 1 "ping" nil))
(def ^:private pong (lsp/response 1 "pong"))
(def ^:private ruby-in-api {:backend-id "heddle-1" :spec {:slot ["ruby" "api"]}})

(defn- temp-dir []
  (str (Files/createTempDirectory "heddle-test" (into-array FileAttribute []))))

(defn- answering-pong
  "A connector to a backend that answers every message with `pong`, then
  stops."
  [_ on-message]
  {:send (fn [_]
           (on-message pong)
           (on-message nil))})

(defn- proxying
  "Proxy options, traced with `trace`, that add what they send the client to
  the atom `sent`, and connect to backends that answer `pong`."
  [trace sent]
  (trace/traced {:send #(swap! sent conj %) :connect answering-pong} trace))

(deftest tracing-messages
  (let [path (str (temp-dir) "/trace.jsonl")
        trace (trace/tracer path)
        sent (atom [])
        {:keys [send connect]} (proxying trace sent)
        conn (connect ruby-in-api #(swap! sent conj %))]
    (trace/from-client trace ping)
    ((:send conn) ping)
    (send pong)
    (let [entries (map json/read-str (str/split-lines (slurp path)))
          backend {"serverType" "ruby" "scope" "api"}]
      (testing "gives who each message came from or went to, in order"
        (is (= [[{"from" "client"} ping]
                [(assoc backend "to" "heddle-1") ping]
                [(assoc backend "from" "heddle-1") pong]
                [{"to" "client"} pong]]
               (map (juxt #(dissoc % "time" "message") #(get % "message")) entries))))
      (testing "gives every entry a time"
        (is (every? #(number? (get % "time")) entries))))
    (testing "passes every message on, including the end of a backend's"
      (is (= [pong nil pong] @sent)))))

(deftest tracing-to-a-file-that-cant-be-written
  (let [logged (atom [])
        previous-writer (log/set-writer! #(swap! logged conj %))
        sent (atom [])
        {:keys [send]} (proxying (trace/tracer (str (temp-dir) "/missing/trace.jsonl")) sent)]
    (try
      (send ping)
      (send pong)
      (is (= [ping pong] @sent) "sends as usual")
      (is (= 1 (count (filter #(str/includes? % "couldn't write the trace") @logged))) "says so once")
      (finally
        (log/set-writer! previous-writer)))))

(defn- traced-messages [path]
  (for [entry (map json/read-str (str/split-lines (slurp path)))]
    [(dissoc entry "time" "message") (get entry "message")]))

(defn- repo-tracing-to-trace-jsonl []
  (let [repo (temp-dir)]
    (spit (io/file repo ".heddle.toml") "trace = { path = 'trace.jsonl' }\n")
    repo))

(defn- initialize-then-hover!
  "Has `heddle serve`'s proxy for `repo`, traced from the atom `tracing`,
  handle `initialize` and then `hover` from the client. Returns what it sent
  the client after each one."
  [repo tracing initialize hover]
  (let [sent (atom [])
        proxy (proxy/create (assoc (proxying (trace/switchable tracing) sent) :cwd repo))]
    (main/serve-message! proxy tracing initialize)
    (let [answered (vec @sent)]
      (main/serve-message! proxy tracing hover)
      [answered (subvec @sent (count answered))])))

(deftest tracing-from-the-config
  (let [initialize (lsp/request 0 "initialize" {"capabilities" {}})
        hover (lsp/request 1 "textDocument/hover" {})]
    (testing "traces the client's initialize first, then everything after it, in order"
      (let [repo (repo-tracing-to-trace-jsonl)
            [answered answered-hover] (initialize-then-hover! repo (atom nil) initialize hover)]
        (is (= (concat [[{"from" "client"} initialize]]
                       (for [msg answered] [{"to" "client"} msg])
                       [[{"from" "client"} hover]]
                       (for [msg answered-hover] [{"to" "client"} msg]))
               (traced-messages (str repo "/trace.jsonl"))))))
    (testing "gives way to --trace"
      (let [repo (repo-tracing-to-trace-jsonl)
            path (str (temp-dir) "/trace.jsonl")]
        (initialize-then-hover! repo (atom (trace/tracer path)) initialize hover)
        (is (= [initialize hover] (map second (filter #(= {"from" "client"} (first %)) (traced-messages path)))))
        (is (not (.exists (io/file repo "trace.jsonl"))))))))
