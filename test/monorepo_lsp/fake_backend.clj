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
              (doseq [reply (respond k msg)]
                (on-message reply))
              (recur))))
        (on-message nil))
      {:send #(.put inbox %)
       :close #(.put inbox ::closed)
       :wait (constantly 0)})))
