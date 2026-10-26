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

(deftest serve-takes-a-file-to-trace-to
  (is (= {:command :serve :eager? true :server-types ["ruby-lsp"] :trace "/tmp/trace.jsonl"}
         (main/parse-args ["serve" "--trace" "/tmp/trace.jsonl" "--eager" "ruby-lsp"])
         (main/parse-args ["--eager" "serve" "ruby-lsp" "--trace" "/tmp/trace.jsonl"])))
  (is (= {:error "--trace needs a file"} (main/parse-args ["serve" "--trace"])))
  (is (= {:error "--trace needs a file"} (main/parse-args ["--trace" "--eager"])))
  (is (= {:error "--trace can only be given once"} (main/parse-args ["--trace" "a" "--trace" "b"])))
  (is (= {:error "check takes only --config, but was given --trace"}
         (main/parse-args ["check" "--trace" "/tmp/trace.jsonl"]))))

(deftest serve-languages-and-check-take-a-config-file
  (is (= {:command :serve :eager? true :server-types ["ruby-lsp"] :trace "t.jsonl" :config "c.toml"}
         (main/parse-args ["--config" "c.toml" "--eager" "serve" "ruby-lsp" "--trace" "t.jsonl"])
         (main/parse-args ["serve" "ruby-lsp" "--trace" "t.jsonl" "--config" "c.toml" "--eager"])))
  (is (= {:command :languages :dir "/repo" :server-types ["ruby-lsp"] :config "c.toml"}
         (main/parse-args ["languages" "--config" "c.toml" "/repo" "ruby-lsp"])))
  (is (= {:command :check :dir "/repo" :config "c.toml"}
         (main/parse-args ["check" "/repo" "--config" "c.toml"])
         (main/parse-args ["--config" "c.toml" "check" "/repo"])))
  (is (= {:error "--config needs a file"} (main/parse-args ["--config"])))
  (is (= {:error "--config needs a file"} (main/parse-args ["--config" "--eager"])))
  (is (= {:error "--config needs a file"} (main/parse-args ["check" "--config"])))
  (is (= {:error "--config can only be given once"} (main/parse-args ["check" "--config" "a" "--config" "b"])))
  (is (= {:error "init takes no options, but was given --config"}
         (main/parse-args ["init" "--config" "c.toml"]))))

(deftest the-config-file-comes-from-config-or-heddle-config
  (is (nil? (main/config-file {} {})))
  (is (nil? (main/config-file {} {"HEDDLE_CONFIG" ""})))
  (is (= "/etc/heddle.toml" (main/config-file {} {"HEDDLE_CONFIG" "/etc/heddle.toml"})))
  (is (= "/etc/heddle.toml"
         (main/config-file {:config "/etc/heddle.toml"} {"HEDDLE_CONFIG" "/elsewhere.toml"})))
  (is (= (str (System/getProperty "user.dir") "/c.toml") (main/config-file {:config "c.toml"} {})))
  (is (= (str (System/getProperty "user.dir") "/c.toml") (main/config-file {} {"HEDDLE_CONFIG" "c.toml"}))))

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
  (is (= {:error "languages takes only --config, but was given --eager"}
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
          "[servers.s]\nlanguages = ['x']\n[scopes.'.'.s]\ncommand = 's'\nimage = 'i'\n")
    (let [{:keys [exit out err]} (main/languages dir [])]
      (is (= 0 exit))
      (is (= "{\"languages\":[\"x\"]}" out))
      (is (str/includes? err "\"image\" isn't used when run directly")))))

(deftest check-a-valid-config
  (let [dir "test/heddle/fixtures/design"]
    (is (= {:exit 0 :out (str "No errors in " dir "/.heddle.toml.\n"
                              "Each server type runs in these scopes:\n"
                              "  rubocop: .\n"
                              "  ruby-lsp: services/api, services/billing, services/legacy\n"
                              "  typescript: services/web")}
           (main/check dir)))))

(deftest check-a-config-with-a-server-type-no-scope-names
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml")
          "[servers.s]\nlanguages = ['x']\nboot = { command = 's' }\n[servers.t]\nlanguages = ['y']\nboot = { command = 't' }\n[scopes.a.s]\n")
    (is (= {:exit 0 :out (str "No errors in " dir "/.heddle.toml.\n"
                              "Each server type runs in these scopes:\n"
                              "  s: a\n"
                              "  t: . (the whole repo, since no scope names it)")}
           (main/check dir)))))

(deftest check-a-config-with-shared-directories
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml")
          "[servers.s]\nlanguages = ['x']\nboot = { command = 's' }\n[scopes.a.s]\nalso = ['shared', 'lib']\n[scopes.b.s]\n")
    (is (= {:exit 0 :out (str "No errors in " dir "/.heddle.toml.\n"
                              "Each server type runs in these scopes:\n"
                              "  s: a (also shared, lib), b")}
           (main/check dir)))))

(deftest check-a-config-with-no-server-types
  (let [dir (temp-dir)]
    (main/init dir)
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

(def ^:private design-config (str (.getAbsolutePath (io/file "test/heddle/fixtures/design/.heddle.toml"))))

(deftest check-and-languages-with-a-config-file
  (let [dir (temp-dir)]
    (is (= {:exit 0 :out "{\"languages\":[\"ruby\"]}"}
           (main/languages dir ["rubocop"] design-config)))
    (is (= {:exit 1 :err (str "There's no server type \"nosuch\" in " design-config ".")}
           (main/languages dir ["nosuch"] design-config)))
    (is (str/starts-with? (:out (main/check dir design-config)) (str "No errors in " design-config ".\n")))))

(deftest check-and-languages-with-heddle-config
  (let [dir (temp-dir)
        config (main/config-file {} {"HEDDLE_CONFIG" design-config})]
    (is (= 0 (:exit (main/outcome {:command :check :dir dir :config config}))))
    (is (= {:exit 0 :out "{\"languages\":[\"ruby\"]}"}
           (main/outcome {:command :languages :dir dir :server-types ["rubocop"] :config config})))))

(deftest check-and-languages-with-a-config-file-that-isnt-there
  (let [dir (temp-dir)
        file (str dir "/nosuch.toml")]
    (spit (io/file dir ".heddle.toml") "")
    (is (= {:exit 1 :err (str "There's no config file " file ".")} (main/check dir file)))
    (is (= {:exit 1 :err (str "There's no config file " file ".")} (main/languages dir [] file)))))

(deftest config-takes-a-directory-and-a-config-file
  (is (= {:command :config :dir nil} (main/parse-args ["config"])))
  (is (= {:command :config :dir "/repo" :config "c.toml"} (main/parse-args ["config" "--config" "c.toml" "/repo"])))
  (is (= {:error "config takes at most one directory"} (main/parse-args ["config" "/repo" "/other"]))))

(deftest config-prints-the-expanded-config
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml")
          "[servers.s]\nlanguages = ['x']\nboot = { command = 's', image = 'i' }\n")
    (let [{:keys [exit out err]} (main/expanded-config dir)]
      (is (= 0 exit))
      (is (str/includes? out "command = \"s\"  # servers.s.boot (line 3)"))
      (is (str/includes? err "\"image\" isn't used when run directly"))))
  (is (= (main/expanded-config (temp-dir) design-config)
         (main/outcome {:command :config :dir "test/heddle/fixtures/design" :config design-config})
         (main/expanded-config "test/heddle/fixtures/design")))
  (is (= 1 (:exit (main/expanded-config (temp-dir))))))

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

(defn- nothing-to-serve
  ([terminal? dir] (nothing-to-serve terminal? dir []))
  ([terminal? dir server-types]
   (with-redefs [main/stdin-terminal? (constantly terminal?)]
     (main/nothing-to-serve dir server-types nil))))

(deftest serve-at-a-terminal-stops-without-a-config
  (let [dir (temp-dir)
        {:keys [exit err]} (nothing-to-serve true dir)]
    (is (= 1 exit))
    (is (str/starts-with? err (str "heddle: There's no .heddle.toml in " dir ".\n")))
    (is (str/includes? err "heddle init"))))

(deftest serve-at-a-terminal-stops-with-a-config-that-starts-nothing
  (let [dir (temp-dir)]
    (main/init dir)
    (let [{:keys [exit err]} (nothing-to-serve true dir)]
      (is (= 1 exit))
      (is (str/starts-with? err (str "heddle: There's no server type in " dir "/.heddle.toml.\n"))))))

(deftest serve-at-a-terminal-stops-with-an-invalid-config
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml") "[servers.s]\n")
    (let [{:keys [exit err]} (nothing-to-serve true dir)]
      (is (= 1 exit))
      (is (str/includes? err "server type \"s\" matches no files")))))

(deftest serve-at-a-terminal-stops-when-no-named-server-type-is-in-the-config
  (let [dir "test/heddle/fixtures/design"
        {:keys [exit err]} (nothing-to-serve true dir ["nosuch"])]
    (is (= 1 exit))
    (is (str/starts-with? err (str "heddle: There's no server type \"nosuch\" in " dir "/.heddle.toml.\n")))))

(deftest serve-at-a-terminal-serves-a-config-that-starts-something
  (let [dir "test/heddle/fixtures/design"]
    (is (nil? (nothing-to-serve true dir)))
    (is (nil? (nothing-to-serve true dir ["nosuch" "rubocop"])))))

(deftest serve-at-a-terminal-with-a-config-file
  (let [dir (temp-dir)]
    (with-redefs [main/stdin-terminal? (constantly true)]
      (is (nil? (main/nothing-to-serve dir [] design-config)))
      (let [{:keys [exit err]} (main/nothing-to-serve dir [] (str dir "/nosuch.toml"))]
        (is (= 1 exit))
        (is (str/starts-with? err (str "heddle: There's no config file " dir "/nosuch.toml.\n")))))))

(deftest serve-from-an-editor-or-pipe-always-serves
  (is (nil? (nothing-to-serve false (temp-dir)))))

(deftest check-a-config-with-warnings
  (let [dir (temp-dir)]
    (spit (io/file dir ".heddle.toml")
          "[servers.s]\nlanguages = ['x']\n[scopes.'.'.s]\ncommand = 's'\nimage = 'i'\n")
    (let [{:keys [exit err]} (main/check dir)]
      (is (= 0 exit))
      (is (str/includes? err "\"image\" isn't used when run directly")))))
