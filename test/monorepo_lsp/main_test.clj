(ns monorepo-lsp.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [monorepo-lsp.main :as main])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-dir []
  (str (Files/createTempDirectory "monorepo-lsp-test" (into-array FileAttribute []))))

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
    (is (str/starts-with? out "usage: monorepo-lsp"))
    (is (nil? err))))

(deftest usage-errors-print-to-stderr-and-exit-2
  (let [{:keys [exit out err]} (main/outcome {:error "unknown option -x"})]
    (is (= 2 exit))
    (is (nil? out))
    (is (str/starts-with? err "monorepo-lsp: unknown option -x\nusage: monorepo-lsp"))))

(deftest languages-and-check-take-a-directory
  (is (= {:command :languages :dir nil} (main/parse-args ["languages"])))
  (is (= {:command :languages :dir "/repo"} (main/parse-args ["languages" "/repo"])))
  (is (= {:command :check :dir nil} (main/parse-args ["check"])))
  (is (= {:command :check :dir "/repo"} (main/parse-args ["check" "/repo"])))
  (is (= {:error "check takes at most one directory"} (main/parse-args ["check" "/repo" "/other"])))
  (is (= {:error "languages takes no options, but was given --eager"}
         (main/parse-args ["--eager" "languages"]))))

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

(deftest check-a-valid-config
  (let [dir "test/monorepo_lsp/fixtures/design"]
    (is (= {:exit 0 :out (str "No errors in " dir "/.monorepo-lsp.toml.")}
           (main/check dir)))))

(deftest check-without-a-config
  (let [dir (temp-dir)
        {:keys [exit err]} (main/check dir)]
    (is (= 1 exit))
    (is (str/includes? err dir))))

(deftest check-an-invalid-config
  (let [dir (temp-dir)]
    (spit (io/file dir ".monorepo-lsp.toml") "[servers.s]\n[servers.t]\nglobs = 't'\n")
    (let [{:keys [exit out err]} (main/check dir)]
      (is (= 1 exit))
      (is (nil? out))
      (is (str/includes? err "server type \"s\" matches no files"))
      (is (str/includes? err "server type \"t\": globs must be a list of strings")))))

(deftest check-a-config-with-warnings
  (let [dir (temp-dir)]
    (spit (io/file dir ".monorepo-lsp.toml")
          "[servers.s]\nlanguages = ['x']\n[scopes.'.'.s]\nrunner = 'host'\ncommand = 's'\nimage = 'i'\n")
    (let [{:keys [exit err]} (main/check dir)]
      (is (= 0 exit))
      (is (str/includes? err "\"image\" isn't used by runner \"host\"")))))
