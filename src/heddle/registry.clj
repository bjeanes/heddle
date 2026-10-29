;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.registry
  "The registry of backends, as a value. It holds the live backend in each
  slot with the documents it has been sent `didOpen` for, why and when each
  slot's last backend failed, and when the user was last told about each slot.
  These functions decide when a slot can start again and when the user can be
  told about it. The proxy keeps the registry in its state and does the
  starting, stopping and telling. See \"Failures\" in DESIGN.md."
  (:require [heddle.backend :as backend]))

(def retry-interval-ms 30000) ; before a failed backend can start again
(def report-interval-ms 60000) ; between messages about one slot's failures

(def empty-registry {:backends {} :failures {} :reported {}})

(defn- elapsed?
  "Whether `ms` or more have passed between `since` and `now`, or there's no
  `since`."
  [since now ms]
  (or (nil? since) (<= ms (- now since))))

(defn entry
  "The registry's entry for the backend `b`, while it's live."
  [registry b]
  (let [e (get-in registry [:backends (backend/slot b)])]
    (when (identical? b (:backend e))
      e)))

(defn started
  "`registry` with `b` as the live backend in its slot."
  [registry b]
  (assoc-in registry [:backends (backend/slot b)] {:backend b :opened #{}}))

(defn may-start?
  "Whether a backend can start for `k`, a [server-type scope], at `now`. It
  can't within `retry-interval-ms` of the last one failing."
  [registry k now]
  (elapsed? (get-in registry [:failures k :at]) now retry-interval-ms))

(defn- describe [b]
  (let [[server-type scope] (backend/slot b)]
    (str server-type " in " (if (= "." scope) "the repo root" scope))))

(defn failure-message
  "What to tell the user about the backend `b` failing.
  `what-happened` follows the backend's description."
  [b what-happened]
  (str (describe b) " " what-happened ". Opening one of its files will start it again, once "
       (quot retry-interval-ms 1000) " s have passed."))

(defn failed
  "`registry` with the backend `b`, if it's live, replaced by a record of
  `message` and the time it failed, `now`."
  [registry b message now]
  (let [k (backend/slot b)]
    (cond-> registry
      (entry registry b)
      (-> (update :backends dissoc k)
          (assoc-in [:failures k] {:message message :at now})))))

(defn failures
  "Why each of the backends `ks`, as [server-type scope], failed, for those
  in the `registry` that aren't live because they did."
  [{:keys [backends failures]} ks]
  (keep #(when-not (contains? backends %) (get-in failures [% :message])) ks))

(defn report-due?
  "Whether the user can be told about the slot `k` at `now`. They're told at
  most once every `report-interval-ms`, as a backend that keeps failing
  would otherwise flood them."
  [registry k now]
  (elapsed? (get-in registry [:reported k]) now report-interval-ms))

(defn reported
  "`registry` noting that the user was told about the slot `k` at `now`, if
  a report was due."
  [registry k now]
  (cond-> registry
    (report-due? registry k now) (assoc-in [:reported k] now)))

(defn opened?
  "Whether the backend `b` has been sent `didOpen` for the file at `uri`."
  [registry b uri]
  (contains? (:opened (entry registry b)) uri))

(defn opened
  "`registry` noting that the backend `b` has been sent `didOpen` for the
  file at `uri`, if `b` is live."
  [registry b uri]
  (cond-> registry
    (entry registry b) (update-in [:backends (backend/slot b) :opened] conj uri)))

(defn closed
  "`registry` with the file at `uri` closed in every backend."
  [registry uri]
  (update registry :backends #(into {} (for [[k e] %] [k (update e :opened disj uri)]))))
