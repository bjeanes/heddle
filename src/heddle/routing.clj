(ns heddle.routing
  "Which backends handle a file or a command, and in what order. These
  functions take the values they route by, such as the session's config and
  specs, and the registry's live backends by slot. The proxy reads those from
  its state, waits for a backend to be ready, and sends it messages. See
  \"Routing messages\" in DESIGN.md."
  (:require [clojure.string :as str]
            [heddle.backend :as backend]
            [heddle.config :as config]
            [heddle.paths :as paths]))

(defn repo-relative [repo path]
  (when (and path (str/starts-with? path (str repo "/")))
    (subs path (inc (count repo)))))

(defn- fetched-by
  "The backend that `path` is a fetched copy for, if any."
  [backends path]
  (first (for [[k {b :backend}] backends
               :when (and path (str/starts-with? path (str (get-in b [:spec :cache]) "/")))]
           k)))

(defn handlers
  "The [server-type scope] of each backend that handles the file at `uri` when
  it's opened as `language`, in priority order. `session` has the :config,
  :repo and :specs to route by. `backends` is the registry's live backends by
  slot."
  [{:keys [config repo specs]} backends uri language]
  (let [path (paths/uri->path uri)]
    (if-let [k (fetched-by backends path)]
      [k]
      (when-let [path (repo-relative repo path)]
        (for [server-type (config/by-priority config (config/server-types config path language))
              :let [k [server-type (config/scope-for config server-type path)]]
              :when (contains? specs k)]
          k)))))

(defn prioritised
  "The registry's live `backends`, by slot, as a list in priority order."
  [config backends]
  (let [rank (zipmap (config/by-priority config (set (map first (keys backends)))) (range))]
    (map (comp :backend backends) (sort-by (fn [[server-type scope]] [(rank server-type) scope]) (keys backends)))))

(defn command-backend
  "The backend of `server-type` among `backends` to run a command with
  `arguments`. Where there are several, the one with the deepest scope that
  contains a file the arguments refer to, or else the first by scope."
  [repo backends server-type arguments]
  (let [scope (comp second backend/slot)
        live (sort-by scope (filter #(= server-type (backend/server-type %)) backends))
        files (keep #(repo-relative repo (paths/uri->path %))
                    (filter string? (tree-seq coll? seq arguments)))
        containing (filter (fn [b] (some #(paths/within? (scope b) %) files)) live)]
    (or (last (sort-by (comp count scope) containing))
        (first live))))
