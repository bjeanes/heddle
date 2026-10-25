(ns heddle.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [heddle.main :as main])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "heddle-test" (into-array FileAttribute []))))

(deftest serve-is-the-default-command
  (is (= {:command :serve :eager? false :server-types []} (main/parse-args [])))
  (is (= {:command :serve :eager? true :server-types []} (main/parse-args ["--eager"])))
  (is (= {:command :serve :eager? false :server-types []} (main/parse-args ["serve"]))))

(deftest serve-takes-server-types-and-eager-in-any-order
  (is (= {:command :serve :eager? true :server-types ["ruby-lsp" "rubocop"]}
         (main/parse-args ["serve" "ruby-lsp" "--eager" "rubocop"])
         (main/parse-args ["--eager" "serve" "ruby-lsp" "rubocop"])
         (main/parse-args ["--eager" "serve" "--eager" "ruby-lsp" "rubocop"]))))

(deftest server-types-only-come-after-serve
  (is (= {:error "unknown command \"ruby-lsp\" (server types can only be named after serve)"}
         (main/parse-args ["--eager" "ruby-lsp"])))
  (is (= {:command :serve :eager? false :server-types ["serve" "check"]}
         (main/parse-args ["serve" "serve" "check"]))))

(deftest serve-rejects-unknown-options
  (is (= {:error "unknown option --languages"} (main/parse-args ["--languages"])))
  (is (= {:error "unknown option -x"} (main/parse-args ["serve" "-x"]))))

(deftest empty-arguments-are-errors
  (is (= {:error "empty argument"} (main/parse-args [""])))
  (is (= {:error "empty argument"} (main/parse-args ["serve" ""])))
  (is (= {:error "empty argument"} (main/parse-args ["check" ""]))))

(deftest help
  (is (= {:command :help} (main/parse-args ["help"])))
  (is (= {:command :help} (main/parse-args ["--help"])))
  (is (= {:command :help} (main/parse-args ["-h"])))
  (is (= {:command :help} (main/parse-args ["serve" "--eager" "--help"]))))

(deftest help-prints-usage-to-stdout-and-succeeds
  (let [{:keys [exit out err]} (main/outcome {:command :help})]
    (is (= 0 exit))
    (is (str/starts-with? out "usage: heddle"))
    (is (nil? err))))

(deftest usage-errors-print-to-stderr-and-exit-2
  (let [{:keys [exit out err]} (main/outcome {:error "unknown option -x"})]
    (is (= 2 exit))
    (is (nil? out))
    (is (str/starts-with? err "heddle: unknown option -x\nusage: heddle"))))

(deftest languages-and-check-take-a-directory
  (is (= {:command :languages :dir nil :server-types []} (main/parse-args ["languages"])))
  (is (= {:command :languages :dir "/repo" :server-types []} (main/parse-args ["languages" "/repo"])))
  (is (= {:command :languages :dir "/repo" :server-types ["ruby-lsp" "rubocop"]}
         (main/parse-args ["languages" "/repo" "ruby-lsp" "rubocop"])))
  (is (= {:command :check :dir nil} (main/parse-args ["check"])))
  (is (= {:command :check :dir "/repo"} (main/parse-args ["check" "/repo"])))
  (is (= {:error "check takes at most one directory"} (main/parse-args ["check" "/repo" "/other"])))
  (is (= {:error "languages takes no options, but was given --eager"}
         (main/parse-args ["--eager" "languages"]))))

(deftest languages
  (is (= {:exit 0 :out "{\"languages\":[\"ruby\",\"typescript\",\"typescriptreact\"]}"}
         (main/languages "test/heddle/fixtures/design" []))))

(deftest languages-of-named-server-types
  (is (= {:exit 0 :out "{\"languages\":[\"ruby\"]}"}
         (main/languages "test/heddle/fixtures/design" ["ruby-lsp" "rubocop"]))))

(deftest languages-of-server-types-that-arent-in-the-config
  (let [dir "test/heddle/fixtures/design"]
    (is (= {:exit 1 :err (str "There's no server type \"nosuch\" in " dir "/.heddle.toml.")}
           (main/languages dir ["ruby-lsp" "nosuch"])))))

(deftest languages-without-a-config
  (let [dir (temp-dir)
        {:keys [exit err]} (main/languages dir [])]
    (is (= 1 exit))
    (is (str/includes? err dir))))

(deftest languages-with-an-invalid-config
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml") "[servers.s]\n")
    (let [{:keys [exit err]} (main/languages dir [])]
      (is (= 1 exit))
      (is (str/includes? err "server type \"s\" matches no files")))))

(deftest languages-with-warnings
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml")
          "[servers.s]\nlanguages = ['x']\n[scopes.'.'.s]\nrunner = 'host'\ncommand = 's'\nimage = 'i'\n")
    (let [{:keys [exit out err]} (main/languages dir [])]
      (is (= 0 exit))
      (is (= "{\"languages\":[\"x\"]}" out))
      (is (str/includes? err "\"image\" isn't used by runner \"host\"")))))

(deftest check-a-valid-config
  (let [dir "test/heddle/fixtures/design"]
    (is (= {:exit 0 :out (str "No errors in " dir "/.heddle.toml.")}
           (main/check dir)))))

(deftest check-without-a-config
  (let [dir (temp-dir)
        {:keys [exit err]} (main/check dir)]
    (is (= 1 exit))
    (is (str/includes? err dir))))

(deftest check-an-invalid-config
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml") "[servers.s]\n[servers.t]\nglobs = 't'\n")
    (let [{:keys [exit out err]} (main/check dir)]
      (is (= 1 exit))
      (is (nil? out))
      (is (str/includes? err "server type \"s\" matches no files"))
      (is (str/includes? err "server type \"t\": globs must be a list of strings")))))

(deftest init-takes-a-directory
  (is (= {:command :init :dir nil} (main/parse-args ["init"])))
  (is (= {:command :init :dir "/repo"} (main/parse-args ["init" "/repo"])))
  (is (= {:error "init takes at most one directory"} (main/parse-args ["init" "/repo" "/other"]))))

(deftest init-writes-a-config-that-check-accepts
  (let [dir (temp-dir)
        {:keys [exit out err]} (main/outcome {:command :init :dir dir})]
    (is (= 0 exit))
    (is (nil? err))
    (is (str/includes? out (str (io/file dir ".heddle.toml"))))
    (is (str/includes? out "heddle check"))
    (is (= 0 (:exit (main/check dir))))))

(deftest init-leaves-an-existing-config-alone
  (let [dir (temp-dir)
        f (io/file dir ".heddle.toml")]
    (spit f "not toml\n")
    (let [{:keys [exit out err]} (main/outcome {:command :init :dir dir})]
      (is (= 1 exit))
      (is (nil? out))
      (is (str/includes? err "already"))
      (is (= "not toml\n" (slurp f))))))

(deftest init-needs-an-existing-directory
  (let [file (str (io/file (temp-dir) "file"))]
    (spit file "")
    (doseq [dir [(str (temp-dir) "/nosuch") file]]
      (let [{:keys [exit err]} (main/outcome {:command :init :dir dir})]
        (is (= 1 exit) dir)
        (is (str/includes? err (str "Couldn't write " (io/file dir ".heddle.toml"))) dir)))))

(deftest check-a-config-with-warnings
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml")
          "[servers.s]\nlanguages = ['x']\n[scopes.'.'.s]\nrunner = 'host'\ncommand = 's'\nimage = 'i'\n")
    (let [{:keys [exit err]} (main/check dir)]
      (is (= 0 exit))
      (is (str/includes? err "\"image\" isn't used by runner \"host\"")))))
