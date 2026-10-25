(ns monorepo-lsp.fetched-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [monorepo-lsp.config :as config]
            [monorepo-lsp.fetched :as fetched])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private config
  (config/parse "
[servers.s]
languages = ['x']

[runners.fake]
start = ['{cfg:command}']
read = ['cat', '{read:file}']

[runners.missing]
start = ['{cfg:command}']
read = ['monorepo-lsp-no-such-program', '{read:file}']

[scopes.'.']
s = { runner = 'fake', command = 's', paths = { '.' = '/src' } }

[scopes.host]
s = { runner = 'host', command = 's', paths = { 'host' = '/host' } }

[scopes.missing]
s = { runner = 'missing', command = 's', paths = { 'missing' = '/missing' } }
"))

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

(defn- backend [scope]
  {:config config :server-type "s" :scope scope :repo "/r" :backend-id "monorepo-lsp-1"
   :cache (temp-dir)})

(deftest cache-dirs
  (let [dir (fetched/cache-dir "monorepo-lsp-1")]
    (is (str/ends-with? dir "/monorepo-lsp/monorepo-lsp-1"))
    (is (not (str/includes? dir "//")))))

(deftest fetching
  (let [server (temp-dir)
        file (str server "/lib/my gem/x.rb")
        b (backend ".")
        copy (io/file (str (:cache b) file))]
    (io/make-parents file)
    (spit file "puts '😀'\n")
    (testing "copies the file with the runner's read, read-only"
      (is (= {} (fetched/fetch! b [file])))
      (is (= "puts '😀'\n" (slurp copy)))
      (is (not (.canWrite copy))))
    (testing "leaves a file that's already cached"
      (spit file "changed\n")
      (is (= {} (fetched/fetch! b [file])))
      (is (= "puts '😀'\n" (slurp copy))))
    (testing "reports a failed read and leaves nothing in the cache"
      (let [gone (str server "/gone.rb")
            failures (fetched/fetch! b [gone file])]
        (is (= #{gone} (set (keys failures))))
        (is (str/includes? (get failures gone) "exited with 1"))
        (is (str/includes? (get failures gone) "No such file"))
        (is (= ["lib"] (vec (.list (io/file (str (:cache b) server)))))
            "no copy of gone.rb, partial or otherwise")))))

(deftest fetching-without-a-usable-read
  (testing "a runner without a read template"
    (is (= {"/usr/x.rb" "the runner has no read template"}
           (fetched/fetch! (backend "host") ["/usr/x.rb"]))))
  (testing "a read command that can't start"
    (let [b (backend "missing")
          failures (fetched/fetch! b ["/usr/x.rb"])]
      (is (str/includes? (get failures "/usr/x.rb") "monorepo-lsp-no-such-program"))
      (is (not (.exists (io/file (str (:cache b) "/usr/x.rb"))))))))
