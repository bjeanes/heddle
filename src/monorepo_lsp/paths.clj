(ns monorepo-lsp.paths
  "Directories relative to the repo root, as used for scopes and the keys of
  `paths`. The root itself is `.`."
  (:require [clojure.string :as str]))

(defn relative-dir? [s]
  (and (string? s)
       (or (= "." s)
           (not-any? #{"" "." ".."} (str/split s #"/" -1)))))

(defn within?
  "Whether the repo-relative `path` is `dir` or under it."
  [dir path]
  (or (= "." dir)
      (= dir path)
      (str/starts-with? path (str dir "/"))))
