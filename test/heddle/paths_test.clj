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

(deftest directories
  (is (every? paths/relative-dir? ["." "a" "a/b"]))
  (is (not-any? paths/relative-dir? ["" "/a" "a/" "./a" "a/../b" "a//b" nil]))
  (is (every? paths/absolute-dir? ["/" "/app" "/a/b"]))
  (is (not-any? paths/absolute-dir? ["" "app" "/app/" "/a/./b" "//a"])))
