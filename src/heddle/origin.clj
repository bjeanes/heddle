;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.origin
  "Marks the items in backends' answers, and qualifies the commands that
  backends offer wherever they appear, so that requests following up on one
  reach the backend that made it. See \"Requests about one file\" in
  DESIGN.md."
  (:require [clojure.string :as str]
            [heddle.methods :as methods]))

(defn- qualify-command
  "A command ID that a backend of `server-type` chose, made unique among all
  server types."
  [server-type command]
  (str server-type ":" command))

(defn qualify-command-ids
  "`v` with the list of command IDs at `path` in it, if any, qualified with
  `server-type`. Server capabilities and registrations list the commands a
  backend runs this way."
  [server-type v path]
  (cond-> v
    (get-in v path) (update-in path #(mapv (partial qualify-command server-type) %))))

(defn qualify-commands
  "`v` with the ID of every command in it qualified with `server-type`."
  [server-type v]
  (cond
    (map? v)
    (let [m (into {} (map (fn [[k x]] [k (qualify-commands server-type x)])) v)]
      (cond-> m
        (and (string? (get m "title")) (string? (get m "command")))
        (update "command" #(qualify-command server-type %))))

    (sequential? v)
    (mapv #(qualify-commands server-type %) v)

    :else v))

(defn command-origin
  "The server type that the qualified `command` belongs to, among
  `server-types`, and the command ID that server type chose. Nil if the
  command isn't qualified with any of them."
  [server-types command]
  (first (for [server-type (sort-by count > server-types)
               :let [prefix (qualify-command server-type "")]
               :when (str/starts-with? command prefix)]
           [server-type (subs command (count prefix))])))

(def ^:private backend-key "heddle/backend")

(defn mark
  "`item` with its `data` wrapped with the ID of the backend that made it."
  [backend-id item]
  (assoc item "data" (cond-> {backend-key backend-id}
                       (contains? item "data") (assoc "data" (get item "data")))))

(defn unmark
  "The ID of the backend that made `item`, and the item as that backend made
  it. Nil if the item isn't marked."
  [item]
  (let [wrapper (get item "data")]
    (when (and (map? wrapper) (contains? wrapper backend-key))
      [(get wrapper backend-key)
       (if (contains? wrapper "data")
         (assoc item "data" (get wrapper "data"))
         (dissoc item "data"))])))

(defn- command?
  "Whether `v` is a bare command, as a code action request may answer with.
  Since a bare command can't be resolved, it's left unmarked."
  [v]
  (string? (get v "command")))

(defn- update-items
  "`v` with `f` applied to each item at `path`, as heddle.methods describes
  it."
  [v path f]
  (cond
    (nil? v) nil
    (empty? path) (f v)
    (= :each (first path)) (mapv #(update-items % (rest path) f) v)
    :else (update v (first path) update-items (rest path) f)))

(defn mark-result
  "The result of the request `method` from the backend `backend-id`, after
  heddle.normalise, with every item in it that a client can resolve or follow
  up on later marked."
  [method backend-id result]
  (if-let [path (get-in methods/table [method :items])]
    (update-items result path #(if (command? %) % (mark backend-id %)))
    result))
