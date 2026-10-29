;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.licence-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private mpl-notice
  (str "This Source Code Form is subject to the terms of the Mozilla Public "
       "License, v. 2.0. If a copy of the MPL was not distributed with this "
       "file, You can obtain one at https://mozilla.org/MPL/2.0/."))

(defn- collapse-whitespace [s]
  (str/join " " (remove str/blank? (str/split s #"\s+"))))

(defn- mit-notice
  "LICENSE-MIT's text, without its title, after an SPDX identifier."
  []
  (str "SPDX-License-Identifier: MIT "
       (collapse-whitespace (str/replace-first (slurp "LICENSE-MIT") "MIT License" ""))))

(defn- expected-notice [path]
  (if (= "lua/heddle.lua" path) (mit-notice) mpl-notice))

(def ^:private source-extensions #{"clj" "edn" "lua" "nix" "yml"})

(def ^:private skipped-dirs
  "examples/ holds sample projects for users to copy. out/ and tmp/ hold build
  output and scratch work. The search also skips hidden directories other than
  .github, since they hold tools' caches and state."
  #{"examples" "out" "tmp"})

(defn- searched? [dir]
  (let [name (.getName dir)]
    (not (or (contains? skipped-dirs name)
             (and (str/starts-with? name ".") (not= ".github" name))))))

(defn- source? [file]
  (contains? source-extensions (second (re-find #"\.([a-z]+)$" (.getName file)))))

(defn- source-files []
  (->> (.listFiles (io/file "."))
       (mapcat #(tree-seq (fn [f] (and (.isDirectory f) (searched? f)))
                          (fn [dir] (seq (.listFiles dir)))
                          %))
       (filter source?)
       (map #(str/replace-first (.getPath %) "./" ""))
       sort))

(defn- leading-comment
  "The comment lines at the top of `path`, without their comment markers or
  line breaks."
  [path]
  (->> (str/split-lines (slurp path))
       (take-while #(re-find #"^\s*$|^#|^;|^--" %))
       (map #(str/replace % #"^(#+|;+|--+)" ""))
       (str/join " ")
       collapse-whitespace))

(defn- has-its-notice? [path]
  (str/includes? (leading-comment path) (expected-notice path)))

(deftest every-source-file-has-its-licence-notice
  (let [files (source-files)]
    (is (some #{"test/heddle/licence_test.clj"} files))
    (is (= [] (remove has-its-notice? files)))))
