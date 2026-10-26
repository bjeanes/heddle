(ns heddle.paths-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.paths :as paths]))

(deftest server-paths
  (testing "the examples in DESIGN.md, for a scope at services/api"
    (let [scope "/r/services/api"]
      (is (= "/app" (paths/to-server {"services/api" "/app"} "/r" scope)))
      (is (= "/src/services/api" (paths/to-server {"." "/src"} "/r" scope)))
      (is (= "/app" (paths/to-server {"." "/src" "services/api" "/app"} "/r" scope)))
      (is (nil? (paths/to-server {"services/web" "/app"} "/r" scope)))))
  (testing "a directory is only covered by whole segments"
    (is (nil? (paths/to-server {"services/api" "/app"} "/r" "/r/services/apiary"))))
  (testing "files under a mapped directory"
    (is (= "/app/app/models/user.rb" (paths/to-server {"services/api" "/app"} "/r" "/r/services/api/app/models/user.rb")))
    (is (= "/x.rb" (paths/to-server {"." "/"} "/r" "/r/x.rb"))))
  (testing "without paths, every path maps to itself"
    (is (= "/anywhere" (paths/to-server nil "/r" "/anywhere")))
    (is (= "/anywhere" (paths/to-client nil "/r" "/anywhere")))))

(deftest client-paths
  (let [ps {"." "/src" "services/api" "/app"}]
    (is (= "/r/services/api/Gemfile" (paths/to-client ps "/r" "/app/Gemfile")))
    (is (= "/r/lib/x.rb" (paths/to-client ps "/r" "/src/lib/x.rb")))
    (is (= "/r" (paths/to-client ps "/r" "/src")))
    (is (nil? (paths/to-client ps "/r" "/usr/lib/ruby/x.rb")) "a fetched file")))

(deftest file-uris
  (testing "become normalised paths without a trailing slash"
    (is (= "/r/a b" (paths/uri->path "file:///r/a%20b")))
    (is (= "/r/b" (paths/uri->path "file:///r/a/../b/")))
    (is (= "/" (paths/uri->path "file:///"))))
  (testing "other URIs have no path"
    (is (nil? (paths/uri->path "untitled:Untitled-1")))
    (is (nil? (paths/uri->path "file:///r/%zz")))
    (is (nil? (paths/uri->path nil))))
  (testing "a path becomes a file URI that maps back to it"
    (is (= "file:///r/a%20b" (paths/path->uri "/r/a b")))
    (is (= "/r/a b" (paths/uri->path (paths/path->uri "/r/a b"))))))

(deftest under
  (is (paths/under? "/r" "/r"))
  (is (paths/under? "/r" "/r/a"))
  (is (paths/under? "/" "/r"))
  (is (not (paths/under? "/r" "/rails")))
  (is (not (paths/under? nil "/r")))
  (is (not (paths/under? "/r" nil))))

(deftest directories
  (is (every? paths/relative-dir? ["." "a" "a/b"]))
  (is (not-any? paths/relative-dir? ["" "/a" "a/" "./a" "a/../b" "a//b" nil]))
  (is (every? paths/absolute-dir? ["/" "/app" "/a/b"]))
  (is (not-any? paths/absolute-dir? ["" "app" "/app/" "/a/./b" "//a"])))
