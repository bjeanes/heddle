(ns monorepo-lsp.origin
  "Marks the items in backends' answers, so that requests following up on an
  item reach the backend that made it. See \"Requests about one file\" in
  DESIGN.md."
  (:require [monorepo-lsp.merge :as merge]))

(def ^:private backend-key "monorepo-lsp/backend")

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

(defn- command? [v]
  (string? (get v "command")))

(defn mark-result
  "The result of the request `method` from the backend `backend-id`, with
  every item in it that a client can resolve later marked."
  [method backend-id result]
  (let [mark-all (fn [items] (mapv #(mark backend-id %) items))]
    (case method
      "textDocument/completion"
      (some-> result merge/completion-list (update "items" mark-all))

      ("textDocument/codeLens" "textDocument/documentLink" "textDocument/inlayHint")
      (some-> result mark-all)

      "textDocument/codeAction"
      (some->> result (mapv #(if (command? %) % (mark backend-id %))))

      ("completionItem/resolve" "codeAction/resolve" "codeLens/resolve"
       "documentLink/resolve" "inlayHint/resolve")
      (some->> result (mark backend-id))

      result)))
