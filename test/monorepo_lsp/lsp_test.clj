(ns monorepo-lsp.lsp-test
  (:require [clojure.test :refer [deftest is]]
            [monorepo-lsp.lsp :as lsp])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

(defn- stream [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest reads-consecutive-messages
  (let [in (stream (str "Content-Length: 13\r\n\r\n{\"id\":\"😀\"}"
                        "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n"
                        "content-length: 2\r\n\r\n[]"))]
    (is (= {"id" "😀"} (lsp/read-message in)) "length counts UTF-8 bytes")
    (is (= [] (lsp/read-message in)) "header names are case-insensitive")
    (is (nil? (lsp/read-message in)))))

(deftest rejects-truncated-messages
  (is (thrown? clojure.lang.ExceptionInfo (lsp/read-message (stream "Content-Length: 10\r\n\r\n{}"))))
  (is (thrown? clojure.lang.ExceptionInfo (lsp/read-message (stream "X: 1\r\n\r\n{}")))))

(deftest writes-utf8-byte-length
  (let [out (ByteArrayOutputStream.)]
    (lsp/write-message out {"id" "😀"})
    (is (= "Content-Length: 13\r\n\r\n{\"id\":\"😀\"}" (String. (.toByteArray out) "UTF-8")))))
