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

[runners.where]
start = ['{cfg:command}']
read = ['pwd']

[scopes.host]
s = { runner = 'host', command = 's', paths = { 'host' = '/host' } }

[scopes.where]
s = { runner = 'where', command = 's', paths = { 'where' = '/where' } }

[scopes.missing]
s = { runner = 'missing', command = 's', paths = { 'missing' = '/missing' } }
"))

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

(defn- backend [scope]
  (let [repo (temp-dir)]
    (.mkdirs (io/file repo scope))
    {:config config :server-type "s" :scope scope :repo repo :backend-id "monorepo-lsp-1"
     :cache (temp-dir)}))

(deftest cache-dirs
  (let [dir (fetched/cache-dir "/code/shop" "ruby" "services/api")]
    (testing "are keyed by repo, server type and scope"
      (is (re-find #"/monorepo-lsp/shop-[0-9a-f]{8}/ruby/services%2Fapi$" dir))
      (is (not (str/includes? dir "//"))))
    (testing "are the same every time"
      (is (= dir (fetched/cache-dir "/code/shop" "ruby" "services/api"))))
    (testing "give the root scope a segment of its own"
      (is (str/ends-with? (fetched/cache-dir "/code/shop" "ruby" ".") "/ruby/%2E")))
    (testing "differ for each repo, server type and scope"
      (is (= 5 (count (distinct [dir
                                 (fetched/cache-dir "/other/shop" "ruby" "services/api")
                                 (fetched/cache-dir "/code/shop" "rubocop" "services/api")
                                 (fetched/cache-dir "/code/shop" "ruby" "services")
                                 (fetched/cache-dir "/code/shop" "ruby" ".")])))))
    (testing "don't nest one scope inside another"
      (is (not (str/starts-with? dir (str (fetched/cache-dir "/code/shop" "ruby" "services") "/")))))))

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

(deftest reading-from-the-scope
  (let [b (backend "where")]
    (fetched/fetch! b ["/usr/x.rb"])
    (is (= (str (.getCanonicalPath (io/file (:repo b) "where")) "\n")
           (slurp (str (:cache b) "/usr/x.rb"))))))

(deftest fetching-without-a-usable-read
  (testing "a runner without a read template"
    (is (= {"/usr/x.rb" "the runner has no read template"}
           (fetched/fetch! (backend "host") ["/usr/x.rb"]))))
  (testing "a read command that can't start"
    (let [b (backend "missing")
          failures (fetched/fetch! b ["/usr/x.rb"])]
      (is (str/includes? (get failures "/usr/x.rb") "monorepo-lsp-no-such-program"))
      (is (not (.exists (io/file (str (:cache b) "/usr/x.rb"))))))))
