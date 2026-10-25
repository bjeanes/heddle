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

(defn- code-actions
  "The code actions in `answers`. The spec gives a code action no ID, only a
  title, and two servers may well offer actions with the same title. So, if
  more than one server offers any, each title names its server type."
  [answers]
  (let [offered (filter (comp seq second) answers)]
    (if (< 1 (count offered))
      (vec (for [[server-type actions] offered
                 action actions]
             (update action "title" #(str "[" server-type "] " %))))
      (vec (mapcat second offered)))))

(defn- full-report [reports]
  {"kind" "full" "items" (vec (mapcat #(get % "items") reports))})

(defn- diagnostic-reports
  "One report from several backends' reports for a file, along with their
  reports for related files. The combined reports have no `resultId`, since
  one backend's ID means nothing to the others. So, the client never asks
  for only what changed, and every backend always reports in full."
  [reports]
  (let [related (apply merge-with into (for [report reports
                                             [uri related-report] (get report "relatedDocuments")]
                                         {uri [related-report]}))]
    (cond-> (full-report reports)
      (seq related) (assoc "relatedDocuments" (into {} (for [[uri rs] related]
                                                         [uri (full-report rs)]))))))

(defn combine
  "One answer to the request `method` from `answers`, which are pairs of a
  server type and its backend's result, in priority order. Nil if every
  result is."
  [method answers]
  (let [results (keep second answers)]
    (when (seq results)
      (case method
        "textDocument/completion" (completions results)
        "textDocument/codeAction" (code-actions answers)
        "textDocument/diagnostic" (diagnostic-reports results)
        (vec (apply concat results))))))
