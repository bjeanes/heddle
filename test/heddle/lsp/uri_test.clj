;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at https://mozilla.org/MPL/2.0/.

(ns heddle.lsp.uri-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.lsp.uri :as uri]))

(deftest file-uris
  (testing "become normalised paths without a trailing slash"
    (is (= "/r/a b" (uri/uri->path "file:///r/a%20b")))
    (is (= "/r/b" (uri/uri->path "file:///r/a/../b/")))
    (is (= "/" (uri/uri->path "file:///"))))
  (testing "other URIs have no path"
    (is (nil? (uri/uri->path "untitled:Untitled-1")))
    (is (nil? (uri/uri->path "file:///r/%zz")))
    (is (nil? (uri/uri->path nil))))
  (testing "a path becomes a file URI that maps back to it"
    (is (= "file:///r/a%20b" (uri/path->uri "/r/a b")))
    (is (= "/r/a b" (uri/uri->path (uri/path->uri "/r/a b"))))))

(deftest percent-encoding
  (testing "a path keeps what a URI's path allows and escapes the rest as UTF-8"
    (is (= "/r/a+b$c%20%27%28%29%F0%9F%98%80" (uri/encode "/r/a+b$c '()😀"))))
  (testing "decoding reads a + as itself"
    (is (= "/r/a+b c" (uri/decode "/r/a+b%20c"))))
  (testing "malformed escapes decode to nil"
    (is (nil? (uri/decode "/r/%zz")))))
