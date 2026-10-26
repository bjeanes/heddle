(ns monorepo-lsp.fake-backend
  "In-process stand-ins for backends, so routing can be tested without
  starting processes."
  (:require [monorepo-lsp.lsp :as lsp])
  (:import (java.util.concurrent LinkedBlockingQueue)))

(defn eventually
  "Polls `f` until it returns something truthy, for up to two seconds. Returns
  what `f` last returned."
  [f]
  (loop [tries 200]
    (let [v (f)]
      (if (or v (zero? tries))
        v
        (do (Thread/sleep 10)
            (recur (dec tries)))))))

(defn answering
  "A `respond` function for `connector` that answers each request with the
  result of the handler for its method, called with its params, and ignores
  notifications. Requests without a handler get a null result."
  [handlers]
  (fn [_ msg]
    (when (contains? msg "id")
      (let [handler (get handlers (get msg "method") (constantly nil))]
        [(lsp/response (get msg "id") (handler (get msg "params")))]))))

(def hang-up
  "A reply that stops the fake backend, as though its process had exited,
  after it sends the replies before this one."
  ::hang-up)

(defn connector
  "A connector for fake backends. Each one handles the messages sent to it in
  order, on a thread of its own. It passes each to (respond k msg), where `k`
  is its [server-type scope], and sends whatever messages that returns back to
  the proxy. Every message a backend receives is added to the vector under its
  `k` in the atom `received`."
  [received respond]
  (fn [backend on-message]
    (let [k [(:server-type backend) (:scope backend)]
          inbox (LinkedBlockingQueue.)]
      (future
        (loop []
          (let [msg (.take inbox)]
            (when-not (= ::closed msg)
              (swap! received update k (fnil conj []) msg)
              (let [[replies hung-up] (split-with #(not= hang-up %) (respond k msg))]
                (run! on-message replies)
                (when (empty? hung-up)
                  (recur))))))
        (on-message nil))
      {:send #(.put inbox %)
       :close #(.put inbox ::closed)
       :wait (constantly 0)})))

(defn refusing
  "The connector `connect`, except that connecting a backend whose
  [server-type scope] is a key of `problems` throws, with the value as the
  exception's message."
  [connect problems]
  (fn [backend on-message]
    (if-let [problem (get problems [(:server-type backend) (:scope backend)])]
      (throw (ex-info problem {}))
      (connect backend on-message))))
