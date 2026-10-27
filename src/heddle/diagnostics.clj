(ns heddle.diagnostics
  "Each backend's latest diagnostics for each file, as a value of the shape
  {uri {backend-id diagnostics}}. The proxy keeps one of these to publish
  every backend's diagnostics for a file together. Publishing them, in order,
  is left to the proxy. See \"Diagnostics\" in DESIGN.md.")

(defn published
  "`by-file` with the `diagnostics` that the backend `backend-id` published
  for `uri` in place of those it published before."
  [by-file uri backend-id diagnostics]
  (let [for-file (if (seq diagnostics)
                   (assoc (get by-file uri) backend-id diagnostics)
                   (dissoc (get by-file uri) backend-id))]
    (if (seq for-file)
      (assoc by-file uri for-file)
      (dissoc by-file uri))))

(defn union-for
  "Every backend's diagnostics for `uri`, in one list. A diagnostic that
  several backends published appears once."
  [by-file uri]
  (vec (distinct (apply concat (vals (get by-file uri))))))

(defn files-from
  "The URIs of the files that the backend `backend-id` has diagnostics for."
  [by-file backend-id]
  (for [[uri for-file] by-file
        :when (contains? for-file backend-id)]
    uri))
