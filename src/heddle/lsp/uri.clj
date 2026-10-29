;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.lsp.uri
  "File URIs, as LSP names documents and folders, and the paths in them."
  (:require [clojure.string :as str])
  (:import (java.net URI URLDecoder)))

(defn decode
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

(defn encode [path]
  (str/join (for [c path
                  :let [s (str c)]]
              (if (re-matches #"[A-Za-z0-9/._~!$&*+,;=:@-]" s)
                s
                (percent-encode s)))))

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
