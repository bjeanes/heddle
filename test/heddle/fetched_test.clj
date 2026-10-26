(ns heddle.fetched-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [heddle.config :as config]
            [heddle.fetched :as fetched])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "heddle-test" (into-array FileAttribute []))))

(defn- backend
  "A backend whose runner's `read` template is `read`, or which has none if
  it's nil. The template runs in a directory of its own."
  [read]
  (let [dir (temp-dir)]
    {:spec {:slot ["s" "."]
            :repo dir
            :dir dir
            :root "/src"
            :paths {"." "/src"}
            :cache (temp-dir)
            :templates (cond-> {"start" ["s"]} read (assoc "read" read))
            :cfg {}}
     :backend-id "heddle-1"
     :fetched (atom #{})
     :read-timeout-ms 10000}))

(defn- cache-of [b]
  (get-in b [:spec :cache]))

(def ^:private cat-file ["cat" "{read:file}"])

(defn- restarted
  "A new backend in place of `b`, sharing its cache."
  [b]
  (assoc b :backend-id "heddle-2" :fetched (atom #{})))

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

(deftest fetching
  (let [server (temp-dir)
        file (str server "/lib/my gem/x.rb")
        b (backend cat-file)
        copy (io/file (str (cache-of b) file))]
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
        (is (= ["lib"] (vec (.list (io/file (str (cache-of b) server)))))
            "no copy of gone.rb, partial or otherwise")))))

(deftest failing-to-refresh
  (let [server (temp-dir)
        file (str server "/x.rb")
        b (backend cat-file)
        copy (io/file (str (cache-of b) file))]
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

(deftest translating-for-the-client
  (let [server (temp-dir)
        file (str server "/x.rb")
        b (backend cat-file)
        copy (str (cache-of b) file)]
    (spit file "x\n")
    (testing "rewrites a file only the backend can see to its copy, after fetching it"
      (is (= {"uri" (str "file://" copy)} (fetched/to-client! b {"uri" (str "file://" file)})))
      (is (= "x\n" (slurp copy))))
    (testing "translates paths as heddle.translate does"
      (is (= {"uri" (str "file://" (get-in b [:spec :repo]) "/a.rb")}
             (fetched/to-client! b {"uri" "file:///src/a.rb"}))))))

(deftest reading-from-the-scope
  (let [b (backend ["pwd" "-P"])]
    (fetched/fetch! b ["/usr/x.rb"])
    (is (= (str (.getCanonicalPath (io/file (get-in b [:spec :dir]))) "\n")
           (slurp (str (cache-of b) "/usr/x.rb"))))))

(deftest fetching-without-a-usable-read
  (testing "a runner without a read template"
    (is (= {"/usr/x.rb" "the runner has no read template"}
           (fetched/fetch! (backend nil) ["/usr/x.rb"]))))
  (testing "a read command that can't start"
    (let [b (backend ["heddle-no-such-program" "{read:file}"])
          failures (fetched/fetch! b ["/usr/x.rb"])]
      (is (str/includes? (get failures "/usr/x.rb") "heddle-no-such-program"))
      (is (not (.exists (io/file (str (cache-of b) "/usr/x.rb")))))))
  (testing "a read command that hangs, which is killed once its time runs out"
    (let [b (assoc (backend ["sleep" "30"]) :read-timeout-ms 200)
          started (System/currentTimeMillis)
          failures (fetched/fetch! b ["/usr/x.rb"])]
      (is (= {"/usr/x.rb" "[\"sleep\" \"30\"] didn't finish within 200 ms"} failures))
      (is (< (- (System/currentTimeMillis) started) 5000))
      (is (= [] (vec (.list (io/file (str (cache-of b) "/usr"))))) "no copy, partial or otherwise"))))
