(ns heddle.session
  "Finds the repo and config that the proxy serves a client from, and what to
  tell the user if there's nothing to serve. The proxy looks for them when the
  client sends `initialize`. `heddle serve` also looks when it's run by hand.
  Serving them is up to the proxy."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.config :as config]
            [heddle.fetched :as fetched]
            [heddle.paths :as paths]))

(defn config-roots
  "Where to look for a config: the workspace roots the client named in
  `initialize`, then `cwd`."
  [params cwd]
  (distinct (concat (keep paths/uri->path (cons (get params "rootUri")
                                          (map #(get % "uri") (get params "workspaceFolders"))))
                    [cwd])))

(def ^:private no-config {"servers" {} "scopes" {} "runners" {}})

(defn load-config
  "The repo and its config, from the first of `roots` with a config. If none
  has one, there's no :repo. The proxy then fails `initialize`. Without a
  config that starts some server type, the proxy serves nothing. :problem then
  has the type of a message for the user, what's wrong, and what would have to
  change. Otherwise, :cache-root is where to cache fetched files, and
  :warnings has the text of any messages warning about the config."
  [roots]
  (if-let [repo (first (filter #(.exists (io/file % config/file-name)) roots))]
    (try
      (let [c (config/read-config repo)
            source (str (io/file repo config/file-name))
            unused (config/warnings c)
            {:keys [root warning]} (fetched/cache-root c repo #(System/getenv %))]
        (cond-> {:repo repo
                 :config c
                 :cache-root root
                 :warnings (cond-> []
                             (seq unused) (conj (config/warnings-message source unused))
                             warning (conj warning))}
          (every? empty? (vals (get c "scopes")))
          (assoc :problem [:warning (str "No scope in " source " starts a server type.") "a scope starts one"])))
      (catch Exception e
        {:repo repo
         :config no-config
         :problem [:error (ex-message e) "the config is fixed"]}))
    {:config no-config
     :problem [:warning (str "There's no " config/file-name " in " (str/join " or " roots) ".") "there is one"]}))
