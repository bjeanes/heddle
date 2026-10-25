(ns heddle.lsp
  "Reading and writing LSP messages: a `Content-Length` header, a blank line,
  then that many bytes of UTF-8 JSON."
  (:require [clojure.string :as str]
            [heddle.json :as json])
  (:import (java.io InputStream OutputStream)))

(defn- read-header-line
  "Reads one header line, without its CRLF. Nil at end of stream."
  [^InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.length sb)) (str sb))
          (= b 10) (str/replace (str sb) #"\r$" "")
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-headers [^InputStream in]
  (loop [headers {}]
    (when-let [line (read-header-line in)]
      (if (str/blank? line)
        headers
        (let [[k v] (str/split line #":\s*" 2)]
          (recur (assoc headers (str/lower-case k) v)))))))

(defn- read-bytes [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (when (< off n)
        (let [r (.read in buf off (- n off))]
          (when (neg? r)
            (throw (ex-info "end of stream inside an LSP message" {:expected n :read off})))
          (recur (+ off r)))))
    buf))

(defn read-message
  "Reads the next message from `in` and parses its JSON. Nil at end of stream."
  [^InputStream in]
  (when-let [headers (read-headers in)]
    (let [length (some-> (get headers "content-length") str/trim Long/parseLong)]
      (when-not length
        (throw (ex-info "LSP message has no Content-Length" {:headers headers})))
      (json/read-str (String. ^bytes (read-bytes in length) "UTF-8")))))

(defn write-message
  "Writes `msg` to `out` as one LSP message. Safe to call from several threads
  sharing one stream."
  [^OutputStream out msg]
  (let [body (.getBytes ^String (json/write-str msg) "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8")]
    (locking out
      (.write out header)
      (.write out body)
      (.flush out))))

(defn notification [method params]
  (cond-> {"jsonrpc" "2.0" "method" method}
    (some? params) (assoc "params" params)))

(defn request [id method params]
  (assoc (notification method params) "id" id))

(defn response [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn error-response [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn response?
  "Whether `msg` answers a request, as opposed to being a request or
  notification itself."
  [msg]
  (and (contains? msg "id") (not (contains? msg "method"))))
