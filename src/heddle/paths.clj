;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.paths
  "Directories relative to the repo root, as used for scopes and the keys of
  `paths`, and translation between client and server paths. The root itself is
  `.`. Client and server paths are absolute."
  (:require [clojure.string :as str])
  (:import (java.net URI)))

(defn relative-dir? [s]
  (and (string? s)
       (or (= "." s)
           (not-any? #{"" "." ".."} (str/split s #"/" -1)))))

(defn absolute-dir? [s]
  (and (string? s)
       (or (= "/" s)
           (and (str/starts-with? s "/")
                (not-any? #{"" "." ".."} (rest (str/split s #"/" -1)))))))

(defn within?
  "Whether the repo-relative `path` is `dir` or under it."
  [dir path]
  (or (= "." dir)
      (= dir path)
      (str/starts-with? path (str dir "/"))))

(defn under?
  "Whether the absolute `path` is `dir` or under it. Not if either is nil."
  [dir path]
  (and dir path
       (or (= dir path)
           (str/starts-with? path (if (= "/" dir) dir (str dir "/"))))))

(defn uri->path
  "The path of the `file:` URI `uri`, normalised and without a trailing slash,
  or nil if it isn't one."
  [uri]
  (try
    (let [uri (URI. uri)]
      (when (= "file" (.getScheme uri))
        (let [path (str/replace (.getPath (.normalize uri)) #"/+$" "")]
          (if (= "" path) "/" path))))
    (catch Exception _ nil)))

(defn path->uri [path]
  (str (URI. "file" "" path nil)))

(defn- rebase [from to path]
  (let [suffix (str/replace-first (subs path (count from)) #"^/" "")]
    (cond (= "" suffix) to
          (= "/" to) (str "/" suffix)
          :else (str to "/" suffix))))

(defn client-path
  "The repo-relative `dir` as a client path, given the repo root `repo`."
  [repo dir]
  (if (= "." dir) repo (str repo "/" dir)))

(defn- translate [pairs path]
  (let [matches (filter (fn [[from _]] (under? from path)) pairs)]
    (when (seq matches)
      (let [[from to] (apply max-key (comp count first) matches)]
        (rebase from to path)))))

(defn to-server
  "Translates a client path through a boot config's `paths`, or returns nil if
  no entry covers it. Without `paths`, every path maps to itself."
  [paths repo path]
  (if (nil? paths)
    path
    (translate (for [[dir server] paths] [(client-path repo dir) server]) path)))

(defn to-client
  "Translates a server path back through `paths`, as `to-server` does."
  [paths repo path]
  (if (nil? paths)
    path
    (translate (for [[dir server] paths] [server (client-path repo dir)]) path)))
