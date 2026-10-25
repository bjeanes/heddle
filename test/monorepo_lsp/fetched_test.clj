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

[runners.hung]
start = ['{cfg:command}']
read = ['sleep', '30']

[scopes.hung]
s = { runner = 'hung', command = 's', paths = { 'hung' = '/hung' } }

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
     :cache (temp-dir) :fetched (atom #{}) :read-timeout-ms 10000}))

(defn- restarted
  "A new backend in place of `b`, sharing its cache."
  [b]
  (assoc b :backend-id "monorepo-lsp-2" :fetched (atom #{})))

(defn- cache-root
  "The cache root that a config with `cache` chooses for the repo /code/shop,
  with `env` as the environment."
  [cache env]
  (fetched/cache-root (config/parse (str "cache = " cache "\n")) "/code/shop" env))

(def ^:private tmpdir (System/getProperty "java.io.tmpdir"))

(deftest cache-roots
  (testing "default to the system's temp directory"
    (is (= {:root tmpdir} (fetched/cache-root (config/parse "") "/code/shop" {}))))
  (testing "can be read from an environment variable"
    (is (= {:root "/home/me/.cache"}
           (cache-root "{ env = 'XDG_CACHE_HOME' }" {"XDG_CACHE_HOME" "/home/me/.cache"}))))
  (testing "fall back to the temp directory, with a warning, if the variable is unusable"
    (is (= {:root tmpdir
            :warning (str "The config's cache names $XDG_CACHE_HOME, which isn't set. "
                          "Fetched files are cached in " tmpdir " instead.")}
           (cache-root "{ env = 'XDG_CACHE_HOME' }" {})))
    (is (str/includes? (:warning (cache-root "{ env = 'XDG_CACHE_HOME' }" {"XDG_CACHE_HOME" ""}))
                       "which is empty"))
    (is (str/includes? (:warning (cache-root "{ env = 'XDG_CACHE_HOME' }" {"XDG_CACHE_HOME" "cache"}))
                       "which is a relative path, \"cache\"")))
  (testing "can follow the XDG spec, preferring $XDG_CACHE_HOME"
    (is (= {:root "/home/me/.xdg"}
           (cache-root "{ xdg = true }" {"XDG_CACHE_HOME" "/home/me/.xdg" "HOME" "/home/me"}))))
  (testing "can follow the XDG spec, using $HOME/.cache if $XDG_CACHE_HOME is unusable"
    (is (= {:root "/home/me/.cache"} (cache-root "{ xdg = true }" {"HOME" "/home/me"})))
    (is (= {:root "/home/me/.cache"}
           (cache-root "{ xdg = true }" {"XDG_CACHE_HOME" "cache" "HOME" "/home/me"})))
    (is (= {:root "/home/me/.cache"} (cache-root "{ xdg = true }" {"HOME" "/home/me/"}))))
  (testing "fall back to the temp directory, with a warning, if XDG finds no usable directory"
    (is (= {:root tmpdir
            :warning (str "The config's cache follows XDG, but $XDG_CACHE_HOME isn't set "
                          "and $HOME is empty. Fetched files are cached in " tmpdir " instead.")}
           (cache-root "{ xdg = true }" {"HOME" ""})))
    (is (str/includes? (:warning (cache-root "{ xdg = true }" {"HOME" "me"}))
                       "and $HOME is a relative path, \"me\"")))
  (testing "can be an absolute path"
    (is (= {:root "/var/cache"} (cache-root "{ path = '/var/cache' }" {}))))
  (testing "can be a path relative to the config's directory"
    (is (= {:root "/code/shop/.cache"} (cache-root "{ path = '.cache' }" {})))
    (is (= {:root "/code/shop"} (cache-root "{ path = '.' }" {})))))

(deftest cache-dirs
  (let [dir (fetched/cache-dir "/cache" "/code/shop" "ruby" "services/api")]
    (testing "are keyed by repo, server type and scope, under the cache root"
      (is (re-matches #"/cache/monorepo-lsp/shop-[0-9a-f]{8}/ruby/services%2Fapi" dir))
      (is (= dir (fetched/cache-dir "/cache/" "/code/shop" "ruby" "services/api"))))
    (testing "are the same every time"
      (is (= dir (fetched/cache-dir "/cache" "/code/shop" "ruby" "services/api"))))
    (testing "give the root scope a segment of its own"
      (is (str/ends-with? (fetched/cache-dir "/cache" "/code/shop" "ruby" ".") "/ruby/%2E")))
    (testing "differ for each repo, server type and scope"
      (is (= 5 (count (distinct [dir
                                 (fetched/cache-dir "/cache" "/other/shop" "ruby" "services/api")
                                 (fetched/cache-dir "/cache" "/code/shop" "rubocop" "services/api")
                                 (fetched/cache-dir "/cache" "/code/shop" "ruby" "services")
                                 (fetched/cache-dir "/cache" "/code/shop" "ruby" ".")])))))
    (testing "don't nest one scope inside another"
      (is (not (str/starts-with? dir (str (fetched/cache-dir "/cache" "/code/shop" "ruby" "services") "/")))))))

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
    (testing "doesn't read a file again once the backend has fetched it"
      (spit file "changed\n")
      (is (= {} (fetched/fetch! b [file])))
      (is (= "puts '😀'\n" (slurp copy))))
    (testing "replaces a copy left by an earlier backend the first time"
      (is (= {} (fetched/fetch! (restarted b) [file])))
      (is (= "changed\n" (slurp copy)))
      (is (not (.canWrite copy))))
    (testing "reports a failed read and leaves nothing in the cache"
      (let [gone (str server "/gone.rb")
            failures (fetched/fetch! b [gone file])]
        (is (= #{gone} (set (keys failures))))
        (is (str/includes? (get failures gone) "exited with 1"))
        (is (str/includes? (get failures gone) "No such file"))
        (is (= ["lib"] (vec (.list (io/file (str (:cache b) server)))))
            "no copy of gone.rb, partial or otherwise")))))

(deftest failing-to-refresh
  (let [server (temp-dir)
        file (str server "/x.rb")
        b (backend ".")
        copy (io/file (str (:cache b) file))]
    (spit file "old\n")
    (fetched/fetch! b [file])
    (.delete (io/file file))
    (let [b (restarted b)]
      (testing "reports the failure but keeps the earlier copy"
        (is (= #{file} (set (keys (fetched/fetch! b [file])))))
        (is (= "old\n" (slurp copy)))
        (is (= ["x.rb"] (vec (.list (.getParentFile copy))))))
      (testing "tries again next time"
        (spit file "new\n")
        (is (= {} (fetched/fetch! b [file])))
        (is (= "new\n" (slurp copy)))))))

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
      (is (not (.exists (io/file (str (:cache b) "/usr/x.rb")))))))
  (testing "a read command that hangs, which is killed once its time runs out"
    (let [b (assoc (backend "hung") :read-timeout-ms 200)
          started (System/currentTimeMillis)
          failures (fetched/fetch! b ["/usr/x.rb"])]
      (is (= {"/usr/x.rb" "[\"sleep\" \"30\"] didn't finish within 200 ms"} failures))
      (is (< (- (System/currentTimeMillis) started) 5000))
      (is (= [] (vec (.list (io/file (str (:cache b) "/usr"))))) "no copy, partial or otherwise"))))
