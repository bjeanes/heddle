(ns heddle.session-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heddle.session :as session])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "heddle-test" (into-array FileAttribute []))))

(def ^:private config
  "cache = { path = 'cache' }\ntrace = { path = 'trace.jsonl' }\n[servers.s]\nlanguages = ['x']\nboot = { command = 's' }\n")

(deftest loading-the-repos-own-config
  (let [repo (temp-dir)]
    (spit (io/file repo ".heddle.toml") config)
    (let [loaded (session/load-config ["/nowhere" repo] nil)]
      (is (= {:repo repo
              :source (str repo "/.heddle.toml")
              :trace-path (str repo "/trace.jsonl")}
             (select-keys loaded [:repo :source :trace-path])))
      (is (str/starts-with? (:cache-root loaded) (str repo "/cache/heddle/"))))))

(deftest loading-a-config-file
  (let [repo (temp-dir)
        dir (temp-dir)
        file (str dir "/shared.toml")]
    (spit file config)
    (testing "takes the repo from the first root, whether or not it has a config"
      (is (= repo (:repo (session/load-config [repo "/elsewhere"] file)))))
    (testing "reads the file in place of the repo's own config"
      (spit (io/file repo ".heddle.toml") "[servers.other]\nlanguages = ['y']\n")
      (let [{:keys [source config problem]} (session/load-config [repo] file)]
        (is (= file source))
        (is (= #{"s"} (set (keys (get config "servers")))))
        (is (nil? problem))))
    (testing "resolves relative cache and trace paths against the file's directory"
      (let [{:keys [cache-root trace-path]} (session/load-config [repo] file)]
        (is (str/starts-with? cache-root (str dir "/cache/heddle/")))
        (is (= (str dir "/trace.jsonl") trace-path))))
    (testing "names the file in its messages"
      (spit file "[servers.s]\n")
      (is (str/includes? (second (:problem (session/load-config [repo] file)))
                         (str "invalid config in " file ":"))))))

(deftest loading-a-config-file-that-isnt-there
  (let [repo (temp-dir)
        file (str (temp-dir) "/nosuch.toml")]
    (spit (io/file repo ".heddle.toml") config)
    (let [loaded (session/load-config [repo] file)]
      (is (nil? (:repo loaded)))
      (is (= (str "There's no config file " file ".") (second (:problem loaded)))))))
