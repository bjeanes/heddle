(ns monorepo-lsp.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [monorepo-lsp.main :as main])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

(deftest languages
  (is (= {:exit 0 :out "{\"languages\":[\"ruby\",\"typescript\",\"typescriptreact\"]}"}
         (main/languages "test/monorepo_lsp/fixtures/design"))))

(deftest languages-without-a-config
  (let [dir (temp-dir)
        {:keys [exit err]} (main/languages dir)]
    (is (= 1 exit))
    (is (str/includes? err dir))))

(deftest languages-with-an-invalid-config
  (let [dir (temp-dir)]
    (spit (io/file dir ".monorepo-lsp.toml") "[servers.s]\n")
    (let [{:keys [exit err]} (main/languages dir)]
      (is (= 1 exit))
      (is (str/includes? err "server type \"s\" matches no files")))))

(deftest languages-with-warnings
  (let [dir (temp-dir)]
    (spit (io/file dir ".monorepo-lsp.toml")
          "[servers.s]\nlanguages = ['x']\n[scopes.'.'.s]\nrunner = 'host'\ncommand = 's'\nimage = 'i'\n")
    (let [{:keys [exit out err]} (main/languages dir)]
      (is (= 0 exit))
      (is (= "{\"languages\":[\"x\"]}" out))
      (is (str/includes? err "\"image\" isn't used by runner \"host\"")))))
