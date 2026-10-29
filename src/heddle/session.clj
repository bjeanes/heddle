;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.session
  "Finds the repo and config that the proxy serves a client from, and what to
  tell the user if there's nothing to serve. The proxy looks for them when the
  client sends `initialize`. `heddle serve` also looks when it's run by hand.
  Serving them is up to the proxy."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.config :as config]
            [heddle.fetched :as fetched]
            [heddle.lsp.uri :as uri]))

(defn config-roots
  "Where to look for a config: the workspace roots the client named in
  `initialize`, then `cwd`."
  [params cwd]
  (distinct (concat (keep uri/uri->path (cons (get params "rootUri")
                                          (map #(get % "uri") (get params "workspaceFolders"))))
                    [cwd])))

(def ^:private no-config {"servers" {} "scopes" {} "runners" {}})

(defn load-config
  "The repo and its config. With a `config-file`, that's the first of `roots`
  and the config in that file. Otherwise, it's the first of `roots` with a
  config, and that config. If there's no such root, or no such `config-file`,
  there's no :repo. The proxy then fails `initialize`. Without a config that
  has some server type, the proxy serves nothing. :problem then has the type
  of a message for the user, what's wrong, and what would have to change.
  Otherwise, :source is the config's file, :cache-root is where to cache
  fetched files, :trace-path is the file to trace messages to, if any, and
  :warnings has the text of any messages warning about the config."
  [roots config-file]
  (if-let [[repo source] (if config-file
                           (when (.exists (io/file config-file))
                             [(first roots) config-file])
                           (first (for [root roots
                                        :let [f (io/file root config/file-name)]
                                        :when (.exists f)]
                                    [root (str f)])))]
    (try
      (let [dir (.getParent (io/file source))
            c (config/read-file source)
            unused (config/warnings c)
            {:keys [root warning]} (fetched/cache-root c dir repo #(System/getenv %))]
        (cond-> {:repo repo
                 :source source
                 :config c
                 :cache-root root
                 :trace-path (config/trace-path c dir)
                 :warnings (cond-> []
                             (seq unused) (conj (config/warnings-message source unused))
                             warning (conj warning))}
          (empty? (get c "servers"))
          (assoc :problem [:warning (str "There's no server type in " source ".") "the config has one"])))
      (catch Exception e
        {:repo repo
         :config no-config
         :problem [:error (ex-message e) "the config is fixed"]}))
    {:config no-config
     :problem [:warning (if config-file
                          (str "There's no config file " config-file ".")
                          (str "There's no " config/file-name " in " (str/join " or " roots) "."))
               "there is one"]}))
