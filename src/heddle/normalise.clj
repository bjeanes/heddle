;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.normalise
  "Puts a backend's answer in the one shape the proxy handles for its request,
  before the proxy marks it or combines it with others. See \"Requests about
  one file\" in DESIGN.md."
  (:require [heddle.methods :as methods]))

(defn- with-defaults
  "A completion item with the `itemDefaults` of its list applied, since a
  combined list can't have one set of defaults for every backend's items."
  [{:strs [editRange] :as defaults} item]
  (cond-> (merge (select-keys defaults ["commitCharacters" "insertTextFormat" "insertTextMode" "data"])
                 item)
    (and editRange (not (contains? item "textEdit")))
    (assoc "textEdit" (assoc (if (contains? editRange "insert") editRange {"range" editRange})
                             "newText" (get item "textEditText" (get item "label"))))))

(defn- completion-list
  "A completion result as a list, with its item defaults applied to each item.
  A backend may answer with a bare array of items instead."
  [result]
  (if (map? result)
    (-> result
        (dissoc "itemDefaults")
        (update "items" #(mapv (partial with-defaults (get result "itemDefaults")) %)))
    {"isIncomplete" false "items" (vec result)}))

(defn result
  "The result of the request `method` from one backend, in the shape the proxy
  handles."
  [method result]
  (case (get-in methods/table [method :normalise])
    :completion-list (some-> result completion-list)
    result))
