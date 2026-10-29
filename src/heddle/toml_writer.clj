;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.toml-writer
  "Writes TOML for the values a config holds: strings, integers, floats,
  booleans, lists of those, and tables."
  (:require [clojure.string :as str]))

(def ^:private escapes {\" "\\\"", \\ "\\\\", \backspace "\\b", \tab "\\t",
                        \newline "\\n", \formfeed "\\f", \return "\\r"})

(def ^:private comment-column 40) ; where row comments line up, unless a row is longer

(defn- escape [c]
  (or (escapes c)
      (when (or (< (int c) 0x20) (= 0x7f (int c)))
        (format "\\u%04X" (int c)))
      (str c)))

(defn string
  "`s` as a TOML basic string."
  [s]
  (str "\"" (apply str (map escape s)) "\""))

(defn key-str
  "The key `k`, bare if TOML allows it, or else quoted."
  [k]
  (if (re-matches #"[A-Za-z0-9_-]+" k) k (string k)))

(defn dotted-key
  "The keys `ks` as one dotted key, as in a table's header."
  [ks]
  (str/join "." (map key-str ks)))

(defn value-str
  "The value `v` as TOML on one line, with any table in it as an inline table."
  [v]
  (cond
    (string? v) (string v)
    (boolean? v) (str v)
    (integer? v) (str v)
    (double? v) (cond (NaN? v) "nan"
                      (infinite? v) (if (pos? v) "inf" "-inf")
                      :else (str v))
    (sequential? v) (str "[" (str/join ", " (map value-str v)) "]")
    (= {} v) "{}"
    (map? v) (str "{ " (str/join ", " (for [[k x] (sort-by key v)]
                                         (str (key-str k) " = " (value-str x))))
                  " }")
    :else (throw (ex-info (str "can't write " (pr-str v) " as TOML") {:value v}))))

(defn tables
  "The tables to write the map `m` at `path` with, one for each map in it that
  holds anything other than maps, or holds nothing. Keys are sorted."
  ([m] (tables [] m))
  ([path m]
   (let [{maps true values false} (group-by #(map? (val %)) (sort-by key m))]
     (concat (when (or (seq values) (and (seq path) (empty? m)))
               [{:path path :rows values}])
             (mapcat (fn [[k v]] (tables (conj path k) v)) maps)))))

(defn- padding [n]
  (apply str (repeat n " ")))

(defn- table-str [{:keys [path comments rows]}]
  (let [lines (for [row rows]
                (if (string? row)
                  [(str "# " row)]
                  (let [[k v comment] row]
                    [(str (key-str k) " = " (value-str v)) comment])))
        width (min comment-column (reduce max 0 (for [[line comment] lines :when comment] (count line))))]
    (str/join "\n" (concat (for [c comments] (str "# " c))
                           (when (seq path) [(str "[" (dotted-key path) "]")])
                           (for [[line comment] lines]
                             (if comment
                               (str line (padding (max 2 (- (+ width 2) (count line)))) "# " comment)
                               line))))))

(defn write
  "TOML for `tables`, each a map with the :path of its header and its :rows.
  A row is [key value], or [key value comment], or a string to write as a
  comment line. Comment lines in :comments go above the header."
  [tables]
  (str/join "\n\n" (map table-str tables)))
