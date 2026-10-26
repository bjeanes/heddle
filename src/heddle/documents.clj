(ns heddle.documents
  "The documents the client has open, as a value of the shape
  {uri {:language :version :text}}. Each holds the text the client last sent,
  so that the proxy can open the document in a backend that starts later.
  heddle.registry keeps track of which backends have been sent each one.

  Positions count UTF-16 code units, the only encoding the proxy offers. Since
  a character here is a code point, one above U+FFFF counts as two units."
  (:require [clojure.string :as str]))

(def ^:private line-break #"\r\n|\r|\n")

(def ^:private sync-kinds {1 :full
                           2 :incremental})

(defn opened
  "`documents` with the one that the client opened with the `didOpen` `params`."
  [documents {:strs [textDocument]}]
  (let [{:strs [uri languageId version text]} textDocument]
    (assoc documents uri {:language languageId :version version :text text})))

(defn open-params
  "The params of a `didOpen` for the document `doc` at `uri`."
  [uri {:keys [language version text]}]
  {"textDocument" {"uri" uri "languageId" language "version" version "text" text}})

(defn- utf-16-units [c]
  (if (< 0xFFFF (int c)) 2 1))

(defn- lines [text]
  (str/split text line-break -1))

(defn- end-position [text]
  (let [all (lines text)]
    {"line" (dec (count all))
     "character" (reduce + (map utf-16-units (last all)))}))

(defn- offset
  "The index in `text` of the character at `position`. A position past the end
  of its line or of the text is at that end."
  [text {:strs [line character]}]
  (let [all (lines text)]
    (if (<= (count all) line)
      (count text)
      (let [line-start (reduce + (map count (concat (take line all)
                                                    (take line (re-seq line-break text)))))
            within (count (take-while #(<= % character)
                                      (rest (reductions + 0 (map utf-16-units (nth all line))))))]
        (+ line-start within)))))

(defn- applied
  "`text` with the content change `change` made to it."
  [text {new-text "text" range "range"}]
  (if range
    (str (subs text 0 (offset text (get range "start")))
         new-text
         (subs text (offset text (get range "end"))))
    new-text))

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
                          :text (reduce applied (:text %) contentChanges))))))

(defn- ranged
  "`change` as a ranged change to `text`, the text it applies to."
  [text change]
  (if (contains? change "range")
    change
    (assoc change "range" {"start" {"line" 0 "character" 0}
                           "end" (end-position text)})))

(defn content-changes
  "The `contentChanges` of a `didChange` for a backend whose sync kind,
  `TextDocumentSyncKind`, is `kind`, given the client's `changes` to a
  document whose text was `text`. A backend that syncs in full gets the new
  text. One that syncs incrementally gets each change as a ranged one, with a
  full change replacing the whole of the text before it. Returns nil for a
  backend that wants no changes."
  [kind text changes]
  (case (get sync-kinds kind)
    :full [{"text" (reduce applied text changes)}]
    :incremental (mapv ranged (reductions applied text changes) changes)
    nil))
