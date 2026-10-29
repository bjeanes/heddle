;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.documents
  "The documents the client has open, as a value of the shape
  {uri {:language :version :text}}. Each holds the text the client last sent,
  so that the proxy can open the document in a backend that starts later.
  heddle.registry keeps track of which backends have been sent each one."
  (:require [heddle.lsp.text :as text]))

(defn opened
  "`documents` with the one that the client opened with the `didOpen` `params`."
  [documents {:strs [textDocument]}]
  (let [{:strs [uri languageId version text]} textDocument]
    (assoc documents uri {:language languageId :version version :text text})))

(defn open-params
  "The params of a `didOpen` for the document `doc` at `uri`."
  [uri {:keys [language version text]}]
  {"textDocument" {"uri" uri "languageId" language "version" version "text" text}})

(defn changed
  "`documents` with the latest version and text of the one that `didChange`
  is about. The proxy asks the client for the full text on every change. It
  applies a ranged change all the same."
  [documents {:strs [textDocument contentChanges]}]
  (let [uri (get textDocument "uri")]
    (cond-> documents
      (contains? documents uri)
      (update uri #(assoc %
                          :version (get textDocument "version")
                          :text (reduce text/applied (:text %) contentChanges))))))
