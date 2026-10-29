;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.requests
  "Tables of the requests sent to a peer that it has yet to answer, as values
  of the shape {:last-id n :open {id {:tag t :on-response f}}}. The proxy keeps
  one for the client and one for each backend. A request's `tag` says what it
  was sent for, so that it can be found again while it's open.")

(def empty-table {:last-id 0 :open {}})

(defn add
  "`table` with a request tagged `tag` added under the next ID, which is then
  its :last-id."
  [table tag on-response]
  (let [id (inc (:last-id table))]
    (-> table
        (assoc :last-id id)
        (assoc-in [:open id] {:tag tag :on-response on-response}))))

(defn on-response
  "The function to call with the response to the open request `id`, if any."
  [table id]
  (get-in table [:open id :on-response]))

(defn answered
  "`table` without the request `id`, once it has been answered."
  [table id]
  (update table :open dissoc id))

(defn tagged
  "The IDs of the open requests tagged `tag`."
  [table tag]
  (for [[id request] (:open table)
        :when (= tag (:tag request))]
    id))
