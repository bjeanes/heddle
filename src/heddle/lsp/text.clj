;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.lsp.text
  "Positions in the text of a document, and changes to that text.

  Positions count UTF-16 code units, the `position-encoding`. Since a character
  here is a code point, one above U+FFFF counts as two units."
  (:require [clojure.string :as str]))

(def position-encoding
  "The encoding that positions count in. It's the spec's default, which every
  server must support."
  "utf-16")

(def ^:private line-break #"\r\n|\r|\n")

(def ^:private sync-kinds {1 :full
                           2 :incremental})

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

(defn applied
  "`text` with the content change `change` made to it."
  [text {new-text "text" range "range"}]
  (if range
    (str (subs text 0 (offset text (get range "start")))
         new-text
         (subs text (offset text (get range "end"))))
    new-text))

(defn- ranged
  "`change` as a ranged change to `text`, the text it applies to."
  [text change]
  (if (contains? change "range")
    change
    (assoc change "range" {"start" {"line" 0 "character" 0}
                           "end" (end-position text)})))

(defn content-changes
  "The `contentChanges` of a `didChange` for a server whose sync kind,
  `TextDocumentSyncKind`, is `kind`, given the client's `changes` to a
  document whose text was `text`. A server that syncs in full gets the new
  text. One that syncs incrementally gets each change as a ranged one, with a
  full change replacing the whole of the text before it. Returns nil for a
  server that wants no changes."
  [kind text changes]
  (case (get sync-kinds kind)
    :full [{"text" (reduce applied text changes)}]
    :incremental (mapv ranged (reductions applied text changes) changes)
    nil))
