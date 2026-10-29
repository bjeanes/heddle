;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.trace
  "Tracing appends every message between the client, the proxy and the
  backends to a file, as one JSON object per line. Each entry has the \"time\"
  in milliseconds since the epoch, the \"message\", and who it came \"from\" or
  went \"to\". That's either \"client\" or a backend's ID. A backend's entries
  also have its \"serverType\" and \"scope\"."
  (:require [heddle.backend :as backend]
            [heddle.json :as json]
            [heddle.log :as log]))

(def ^:private client "client")

(defn tracer
  "A function that appends an entry to the file at `path` for a message and
  its party, such as {\"from\" \"client\"}. Safe to call from several threads.
  If it can't write to the file, it logs why once and traces nothing more."
  [path]
  (let [lock (Object.)
        failed (atom false)]
    (fn [party msg]
      (when-not @failed
        (try
          (let [line (str (json/write-str (merge {"time" (System/currentTimeMillis)} party {"message" msg})) "\n")]
            (locking lock
              (spit path line :append true)))
          (catch Exception e
            (when (compare-and-set! failed false true)
              (log/info "couldn't write the trace to" path "-" (ex-message e)))))))))

(defn switchable
  "A function that traces with the tracer in the atom `current`, or traces
  nothing while it holds nil."
  [current]
  (fn [party msg]
    (when-let [trace @current]
      (trace party msg))))

(defn from-client
  "Traces `msg` as received from the client."
  [trace msg]
  (trace {"from" client} msg))

(defn- to-client [send trace]
  (fn [msg]
    (trace {"to" client} msg)
    (send msg)))

(defn- connector [connect trace]
  (fn [b on-message]
    (let [[server-type scope] (backend/slot b)
          party (fn [direction] {direction (:backend-id b) "serverType" server-type "scope" scope})
          conn (connect b (fn [msg]
                            (when msg
                              (trace (party "from") msg))
                            (on-message msg)))]
      (update conn :send (fn [send]
                           (fn [msg]
                             (trace (party "to") msg)
                             (send msg)))))))

(defn traced
  "The proxy's `opts` (see heddle.proxy/create) with their :send and :connect
  tracing each message they send the client, send a backend or receive from
  one. Messages from the client are left to `from-client`."
  [opts trace]
  (-> opts
      (update :send to-client trace)
      (update :connect connector trace)))
