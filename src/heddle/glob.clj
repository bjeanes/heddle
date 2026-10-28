(ns heddle.glob
  "Globs over repo-relative paths. `*` and `?` match within one path segment,
  and `**` matches any number of whole segments, including none."
  (:require [clojure.string :as str]))

(defn- quote-char [c]
  (if (str/index-of "\\^$.|+()[]{}" c) (str "\\" c) (str c)))

(defn- translate [^String glob]
  (let [n (count glob)]
    (loop [i 0 out ""]
      (if (>= i n)
        out
        (let [c (.charAt glob i)]
          (cond
            (.startsWith glob "**/" i) (recur (+ i 3) (str out "(?:.*/)?"))
            (and (.startsWith glob "/**" i) (= n (+ i 3))) (recur n (str out "(?:/.*)?"))
            (.startsWith glob "**" i) (recur (+ i 2) (str out ".*"))
            (= \* c) (recur (inc i) (str out "[^/]*"))
            (= \? c) (recur (inc i) (str out "[^/]"))
            :else (recur (inc i) (str out (quote-char c)))))))))

(defn matches? [glob path]
  (boolean (re-matches (re-pattern (translate glob)) path)))

(defn literal
  "A glob for clients, such as VS Code and Neovim, that matches `path`. Their
  globs have no common way to match a literal `{`, `}` or `]`. In their place
  is `?`, which matches any one character."
  [path]
  (str/replace path #"[*?\[\\{}\]]" #(if (str/index-of "{}]" %) "?" (str "[" % "]"))))
