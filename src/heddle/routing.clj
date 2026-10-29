;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.routing
  "Which backends handle a file or a command, and in what order. These
  functions take the values they route by, such as the session's config and
  specs, and the registry's live backends by slot. The proxy reads those from
  its state, waits for a backend to be ready, and sends it messages. See
  \"Routing messages\" in DESIGN.md."
  (:require [clojure.string :as str]
            [heddle.backend :as backend]
            [heddle.config :as config]
            [heddle.lsp.uri :as uri]
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
  it's opened as `language`, in priority order, then by scope. For each server
  type, those are the longest scope that contains the file and every scope
  that also serves a directory containing it. `session` has the :config,
  :repo and :specs to route by. `backends` is the registry's live backends by
  slot."
  [{:keys [config repo specs]} backends uri language]
  (let [path (uri/uri->path uri)]
    (if-let [k (fetched-by backends path)]
      [k]
      (when-let [path (repo-relative repo path)]
        (for [server-type (config/by-priority config (config/server-types config path language))
              scope (config/scopes-for config server-type path)
              :let [k [server-type scope]]
              :when (contains? specs k)]
          k)))))

(defn prioritised
  "The registry's live `backends`, by slot, as a list in priority order."
  [config backends]
  (let [rank (zipmap (config/by-priority config (set (map first (keys backends)))) (range))]
    (map (comp :backend backends) (sort-by (fn [[server-type scope]] [(rank server-type) scope]) (keys backends)))))

(defn command-backend
  "The backend of `server-type` among `backends` to run a command with
  `arguments`. `offering` holds the backends known to offer the command. If
  any of them is of `server-type`, this chooses among those. Otherwise it
  chooses among every backend of `server-type`, since one that is still
  starting may offer the command once it's ready. Where there are several, the
  one with the deepest scope that contains a file the arguments refer to, or
  else the first by scope."
  [repo backends server-type offering arguments]
  (let [scope (comp second backend/slot)
        of-type (partial filter #(= server-type (backend/server-type %)))
        candidates (sort-by scope (or (seq (of-type offering)) (of-type backends)))
        files (keep #(repo-relative repo (uri/uri->path %))
                    (filter string? (tree-seq coll? seq arguments)))
        containing (filter (fn [b] (some #(paths/within? (scope b) %) files)) candidates)]
    (or (last (sort-by (comp count scope) containing))
        (first candidates))))
