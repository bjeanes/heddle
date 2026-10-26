(ns heddle.json
  "JSON for LSP payloads. Objects read as maps with string keys, arrays as
  vectors, integers as longs and other numbers as doubles. Keys stay strings
  because the proxy forwards payloads it doesn't understand, and some keys are
  URIs.

  This exists because clojure.data.json doesn't work on Jolt (as of v0.8.23).
  It needs java.time.format.DateTimeFormatter, which Jolt's core doesn't
  provide. Jolt's strings are also made of code points, where the JVM's are
  UTF-16 code units. data.json reads an escaped surrogate pair as two halves,
  which Jolt can't represent as characters, and its default writer escapes
  characters above U+FFFF as invalid five-digit escapes.

  So, this reader combines an escaped surrogate pair into one code point and
  replaces a lone surrogate with U+FFFD. The writer leaves everything outside
  ASCII as raw UTF-8.

  It can be replaced by a library that loads on Jolt without adding runtime
  dependencies to the binary, and that reads and writes characters above U+FFFF
  correctly. These tests cover both.")

(def ^:private replacement-char (char 0xFFFD))

(defn- fail [^String s i msg]
  (throw (ex-info (str "JSON error at " i ": " msg) {:index i :input-length (count s)})))

(defn- skip-ws [^String s i]
  (let [n (count s)]
    (loop [i i]
      (if (and (< i n) (#{\space \tab \newline \return} (.charAt s i)))
        (recur (inc i))
        i))))

(defn- expect-literal [^String s i ^String lit value]
  (if (.startsWith s lit i)
    [value (+ i (count lit))]
    (fail s i (str "expected " lit))))

(defn- read-hex4 [^String s i]
  (when (> (+ i 4) (count s))
    (fail s i "truncated \\u escape"))
  (try
    (Integer/parseInt (subs s i (+ i 4)) 16)
    (catch Exception _ (fail s i "invalid \\u escape"))))

(defn- high-surrogate? [n] (<= 0xD800 n 0xDBFF))
(defn- low-surrogate? [n] (<= 0xDC00 n 0xDFFF))

(defn- read-unicode-escape
  "Reads the four hex digits after `\\u` at i. Returns [char next-index]. A lone
  surrogate can't be represented as a code point, so it becomes U+FFFD."
  [^String s i]
  (let [n (read-hex4 s i)
        after (+ i 4)]
    (cond
      (and (high-surrogate? n) (.startsWith s "\\u" after))
      (let [lo (read-hex4 s (+ after 2))]
        (if (low-surrogate? lo)
          [(char (+ 0x10000 (bit-shift-left (- n 0xD800) 10) (- lo 0xDC00))) (+ after 6)]
          [replacement-char after]))

      (or (high-surrogate? n) (low-surrogate? n)) [replacement-char after]
      :else [(char n) after])))

(defn- read-string* [^String s i]
  (let [sb (StringBuilder.)
        n (count s)]
    (loop [i i]
      (when (>= i n) (fail s i "unterminated string"))
      (let [c (.charAt s i)]
        (case c
          \" [(str sb) (inc i)]
          \\ (let [e (when (< (inc i) n) (.charAt s (inc i)))]
               (case e
                 \u (let [[ch next] (read-unicode-escape s (+ i 2))]
                      (.append sb ch)
                      (recur next))
                 (\" \\ \/) (do (.append sb e) (recur (+ i 2)))
                 \b (do (.append sb \backspace) (recur (+ i 2)))
                 \f (do (.append sb \formfeed) (recur (+ i 2)))
                 \n (do (.append sb \newline) (recur (+ i 2)))
                 \r (do (.append sb \return) (recur (+ i 2)))
                 \t (do (.append sb \tab) (recur (+ i 2)))
                 (fail s i "invalid escape")))
          (do (.append sb c) (recur (inc i))))))))

(defn- number-char? [c]
  (or (Character/isDigit c) (#{\- \+ \. \e \E} c)))

(defn- read-number [^String s i]
  (let [n (count s)
        end (loop [j i] (if (and (< j n) (number-char? (.charAt s j))) (recur (inc j)) j))
        text (subs s i end)]
    (try
      [(if (re-find #"[.eE]" text)
         (Double/parseDouble text)
         (try (Long/parseLong text) (catch NumberFormatException _ (Double/parseDouble text))))
       end]
      (catch NumberFormatException _ (fail s i (str "invalid number " text))))))

(declare read-value)

(defn- read-array [^String s i]
  (let [i (skip-ws s i)]
    (if (and (< i (count s)) (= \] (.charAt s i)))
      [[] (inc i)]
      (loop [acc (transient []) i i]
        (let [[v i] (read-value s i)
              acc (conj! acc v)
              i (skip-ws s i)]
          (case (when (< i (count s)) (.charAt s i))
            \, (recur acc (inc i))
            \] [(persistent! acc) (inc i)]
            (fail s i "expected , or ]")))))))

(defn- read-object [^String s i]
  (let [i (skip-ws s i)]
    (if (and (< i (count s)) (= \} (.charAt s i)))
      [{} (inc i)]
      (loop [acc (transient {}) i i]
        (let [i (skip-ws s i)
              _ (when-not (and (< i (count s)) (= \" (.charAt s i)))
                  (fail s i "expected a string key"))
              [k i] (read-string* s (inc i))
              i (skip-ws s i)
              _ (when-not (and (< i (count s)) (= \: (.charAt s i)))
                  (fail s i "expected :"))
              [v i] (read-value s (inc i))
              acc (assoc! acc k v)
              i (skip-ws s i)]
          (case (when (< i (count s)) (.charAt s i))
            \, (recur acc (inc i))
            \} [(persistent! acc) (inc i)]
            (fail s i "expected , or }")))))))

(defn- read-value [^String s i]
  (let [i (skip-ws s i)]
    (when (>= i (count s)) (fail s i "unexpected end of input"))
    (let [c (.charAt s i)]
      (case c
        \{ (read-object s (inc i))
        \[ (read-array s (inc i))
        \" (read-string* s (inc i))
        \t (expect-literal s i "true" true)
        \f (expect-literal s i "false" false)
        \n (expect-literal s i "null" nil)
        (if (number-char? c)
          (read-number s i)
          (fail s i (str "unexpected character " (pr-str c))))))))

(defn read-str [^String s]
  (let [[v i] (read-value s 0)
        i (skip-ws s i)]
    (when (< i (count s)) (fail s i "trailing characters"))
    v))

(defn- write-string [^StringBuilder sb ^String s]
  (.append sb \")
  (doseq [c s]
    (case c
      \" (.append sb "\\\"")
      \\ (.append sb "\\\\")
      \newline (.append sb "\\n")
      \return (.append sb "\\r")
      \tab (.append sb "\\t")
      \backspace (.append sb "\\b")
      \formfeed (.append sb "\\f")
      (if (< (int c) 0x20)
        (.append sb (format "\\u%04x" (int c)))
        (.append sb c))))
  (.append sb \"))

(defn- write-value [^StringBuilder sb v]
  (cond
    (nil? v) (.append sb "null")
    (true? v) (.append sb "true")
    (false? v) (.append sb "false")
    (string? v) (write-string sb v)
    (keyword? v) (write-string sb (name v))
    (integer? v) (.append sb (str v))
    (number? v) (let [d (double v)]
                  (when (or (Double/isNaN d) (Double/isInfinite d))
                    (throw (ex-info "JSON can't represent this number" {:value v})))
                  (.append sb (str d)))
    (map? v) (do (.append sb \{)
                 (doseq [[idx [k val]] (map-indexed vector v)]
                   (when (pos? idx) (.append sb \,))
                   (write-string sb (if (keyword? k) (name k) (str k)))
                   (.append sb \:)
                   (write-value sb val))
                 (.append sb \}))
    (sequential? v) (do (.append sb \[)
                        (doseq [[idx item] (map-indexed vector v)]
                          (when (pos? idx) (.append sb \,))
                          (write-value sb item))
                        (.append sb \]))
    :else (throw (ex-info "JSON can't represent this value" {:value v :type (type v)}))))

(defn write-str [v]
  (let [sb (StringBuilder.)]
    (write-value sb v)
    (str sb)))
