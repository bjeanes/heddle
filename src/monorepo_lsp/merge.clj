(ns monorepo-lsp.merge
  "Combines the answers several backends give to one request. See \"Requests
  about one file\" in DESIGN.md.")

(defn- with-defaults
  "A completion item with the `itemDefaults` of its list applied, since a
  combined list can't have one set of defaults for every backend's items."
  [{:strs [editRange] :as defaults} item]
  (cond-> (merge (select-keys defaults ["commitCharacters" "insertTextFormat" "insertTextMode" "data"])
                 item)
    (and editRange (not (contains? item "textEdit")))
    (assoc "textEdit" (assoc (if (contains? editRange "insert") editRange {"range" editRange})
                             "newText" (get item "textEditText" (get item "label"))))))

(defn completion-list
  "A completion result as a list, with its item defaults applied to each item.
  A backend may answer with a bare array of items instead."
  [result]
  (if (map? result)
    (-> result
        (dissoc "itemDefaults")
        (update "items" #(mapv (partial with-defaults (get result "itemDefaults")) %)))
    {"isIncomplete" false "items" (vec result)}))

(defn- completions [results]
  (let [lists (map completion-list results)]
    {"isIncomplete" (boolean (some #(get % "isIncomplete") lists))
     "items" (vec (mapcat #(get % "items") lists))}))

(defn combine
  "One answer to the request `method` from `answers`, which are pairs of a
  server type and its backend's result, in priority order. Nil if every
  result is."
  [method answers]
  (let [results (keep second answers)]
    (when (seq results)
      (case method
        "textDocument/completion" (completions results)
        (vec (apply concat results))))))
