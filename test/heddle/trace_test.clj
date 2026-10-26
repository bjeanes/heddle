(ns heddle.trace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heddle.json :as json]
            [heddle.log :as log]
            [heddle.lsp :as lsp]
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
