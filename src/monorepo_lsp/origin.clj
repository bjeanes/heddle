(ns monorepo-lsp.origin
  "Marks the items and commands in backends' answers, so that requests
  following up on one reach the backend that made it. See \"Requests about
  one file\" in DESIGN.md."
  (:require [clojure.string :as str]
            [monorepo-lsp.merge :as merge]))

(defn qualify-command
  "A command ID that a backend of `server-type` chose, made unique among all
  server types."
  [server-type command]
  (str server-type ":" command))

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
  every item in it that a client can resolve or follow up on later marked."
  [method backend-id result]
  (let [mark-all (fn [items] (mapv #(mark backend-id %) items))
        mark-calls (fn [k calls] (mapv #(update % k (partial mark backend-id)) calls))]
    (case method
      "textDocument/completion"
      (some-> result merge/completion-list (update "items" mark-all))

      ("textDocument/codeLens" "textDocument/documentLink" "textDocument/inlayHint" "workspace/symbol"
       "textDocument/prepareCallHierarchy" "textDocument/prepareTypeHierarchy"
       "typeHierarchy/supertypes" "typeHierarchy/subtypes")
      (some-> result mark-all)

      "callHierarchy/incomingCalls"
      (some->> result (mark-calls "from"))

      "callHierarchy/outgoingCalls"
      (some->> result (mark-calls "to"))

      "textDocument/codeAction"
      (some->> result (mapv #(if (command? %) % (mark backend-id %))))

      ("completionItem/resolve" "codeAction/resolve" "codeLens/resolve"
       "documentLink/resolve" "inlayHint/resolve" "workspaceSymbol/resolve")
      (some->> result (mark backend-id))

      result)))
