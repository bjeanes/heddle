(ns heddle.merge
  "Combines the answers several backends give to one request. See \"Requests
  about one file\" in DESIGN.md."
  (:require [heddle.methods :as methods]))

(defn- completions [lists]
  {"isIncomplete" (boolean (some #(get % "isIncomplete") lists))
   "items" (vec (mapcat #(get % "items") lists))})

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
  server type and its backend's result, in priority order. Each result has
  been through heddle.normalise. Nil if every result is."
  [method answers]
  (let [results (keep second answers)]
    (when (seq results)
      (case (get-in methods/table [method :combine])
        :completions (completions results)
        :code-actions (code-actions answers)
        :diagnostic-reports (diagnostic-reports results)
        :concat (vec (apply concat results))))))
