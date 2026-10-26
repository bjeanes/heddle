(ns heddle.translate
  "Translates whole LSP payloads between client and server paths, for one
  backend. See \"Paths\" under \"Routing messages\" in DESIGN.md.

  The context for a backend is its spec (see heddle.spec), which has the repo
  root as a client path under :repo, its `paths` under :paths, and the
  directory holding its fetched files under :cache."
  (:require [clojure.string :as str]
            [heddle.paths :as paths])
  (:import (java.net URLDecoder)))

(def ^:private file-uri #"file://(/[^\s\"'()<>?#\[\]]*)")

(def ^:private bare-path #"/\S+")

(defn- decode
  "The path in a URI with its percent escapes decoded, or nil if they're
  malformed. A `+` is literal in a URI's path, where URLDecoder would read it as
  a space."
  [s]
  (try
    (URLDecoder/decode (str/replace s "+" "%2B") "UTF-8")
    (catch Exception _ nil)))

(defn- percent-encode [s]
  (str/join (for [b (.getBytes s "UTF-8")]
              (format "%%%02X" (bit-and b 0xFF)))))

(defn- encode [path]
  (str/join (for [c path
                  :let [s (str c)]]
              (if (re-matches #"[A-Za-z0-9/._~!$&*+,;=:@-]" s)
                s
                (percent-encode s)))))

(defn- fetched
  "`path` if it's a server path that `paths` doesn't cover, or nil. The root
  can't be a file. Paths with `.` or `..` segments are left out, since their
  copies could land outside the cache."
  [{:keys [repo paths]} path]
  (when (and (nil? (paths/to-client paths repo path))
             (not= "/" path)
             (paths/absolute-dir? path))
    path))

(defn- uri-to-server [{:keys [repo paths cache]} path]
  (if (str/starts-with? path (str cache "/"))
    (subs path (count cache))
    (paths/to-server paths repo path)))

(defn- uri-to-client [{:keys [repo paths cache] :as ctx} path]
  (or (paths/to-client paths repo path)
      (some->> (fetched ctx path) (str cache))))

(defn- rewrite
  "Translates `s` if it's a bare path, or the URIs inside it otherwise. Leaves
  whatever the translation functions return nil for as it was."
  [s translate-uri translate-bare]
  (if (re-matches bare-path s)
    (or (translate-bare s) s)
    (str/replace s file-uri
                 (fn [[uri encoded]]
                   (let [path (decode encoded)
                         translated (some-> path translate-uri)]
                     (if (and translated (not= path translated))
                       (str "file://" (encode translated))
                       uri))))))

(defn- walk [f v]
  (cond
    (string? v) (f v)
    (map? v) (into {} (map (fn [[k x]] [(walk f k) (walk f x)])) v)
    (sequential? v) (mapv #(walk f %) v)
    :else v))

(defn to-server
  "Translates every client path in the payload `v` for the backend `ctx`."
  [{:keys! [repo paths & :cache] :as ctx} v]
  (walk #(rewrite % (partial uri-to-server ctx) (partial paths/to-server paths repo)) v))

(defn to-client
  "Translates every server path in the payload `v` from the backend `ctx`.
  Fetched files in URIs become their copies in the cache, which
  `fetched-files` lists."
  [{:keys! [repo paths & :cache] :as ctx} v]
  (walk #(rewrite % (partial uri-to-client ctx) (partial paths/to-client paths repo)) v))

(defn fetched-files
  "The server paths of the fetched files that URIs in the server payload `v`
  refer to."
  [ctx v]
  (set (for [s (filter string? (tree-seq coll? seq v))
             :when (not (re-matches bare-path s))
             [_ encoded] (re-seq file-uri s)
             :let [path (some->> (decode encoded) (fetched ctx))]
             :when path]
         path)))
