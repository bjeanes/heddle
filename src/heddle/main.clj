(ns heddle.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.backend :as backend]
            [heddle.config :as config]
            [heddle.json :as json]
            [heddle.lsp :as lsp]
            [heddle.proxy :as proxy]
            [heddle.starter :as starter])
  (:import (java.io BufferedInputStream)
           (java.lang ProcessBuilder$Redirect)))

(def ^:private usage
  "usage: heddle [serve] [--eager] [SERVER-TYPE...]
       heddle languages [DIR [SERVER-TYPE...]]
       heddle check [DIR]
       heddle init [DIR]
       heddle help

serve, the default, speaks LSP over stdin and stdout. It serves every server
type in the config, or only those named. Server types can only be named after
serve.

--eager starts every backend when the client initializes, and waits up to
30 s for them before answering, so that the answer lists their features and
commands. Otherwise each backend starts when one of its files opens.

languages prints, as JSON, the languages that the config at the root of DIR
covers, counting only the server types named, if any.

check reads the config at the root of DIR and prints its errors and warnings.
It exits non-zero if the config has errors.

init writes a starter config, all commented out, to the root of DIR. It won't
replace a config that's already there.

DIR is the current directory unless given.")

(def ^:private commands #{"serve" "languages" "check" "init" "help"})

(defn- option? [arg]
  (str/starts-with? arg "-"))

(defn parse-args
  "The command that the command line `args` asks for, as a map with its
  :command, or with an :error saying what's wrong with them."
  [args]
  (let [options (filter option? args)
        [command & operands] (remove option? args)]
    (cond
      (or (some #{"--help" "-h"} options) (= "help" command))
      {:command :help}

      (some #{""} args)
      {:error "empty argument"}

      (and command (not (commands command)))
      {:error (str "unknown command " (pr-str command) " (server types can only be named after serve)")}

      (or (nil? command) (= "serve" command))
      (if-let [unknown (first (remove #{"--eager"} options))]
        {:error (str "unknown option " unknown)}
        {:command :serve
         :eager? (boolean (some #{"--eager"} options))
         :server-types (vec operands)})

      (seq options)
      {:error (str command " takes no options, but was given " (first options))}

      (= "languages" command)
      {:command :languages :dir (first operands) :server-types (vec (rest operands))}

      (next operands)
      {:error (str command " takes at most one directory")}

      :else
      {:command (keyword command) :dir (first operands)})))

(defn- with-config
  "Calls `f` with the config at the root of the repo at `dir` and the config's
  path, for what a command prints (:out and :err) and its :exit status, which
  is 0 unless `f` says otherwise. :err has the config's problems on failure,
  and otherwise its warnings unless `f` gives an :err of its own."
  [dir f]
  (try
    (let [source (str (io/file dir config/file-name))]
      (if-let [c (config/read-config dir)]
        (let [warnings (config/warnings c)]
          (merge {:exit 0}
                 (when (seq warnings) {:err (config/warnings-message source warnings)})
                 (f c source)))
        {:exit 1 :err (str "There's no " config/file-name " in " dir ".")}))
    (catch Exception e
      {:exit 1 :err (ex-message e)})))

(defn languages
  "What `heddle languages` prints for the repo at `dir`, when serving
  `server-types` (or every server type, if empty)."
  [dir server-types]
  (with-config dir (fn [c source]
                     (if-let [unknown (seq (config/unknown-server-types c server-types))]
                       {:exit 1 :err (config/unknown-server-types-message source unknown)}
                       {:out (json/write-str {"languages" (config/languages c server-types)})}))))

(defn check
  "What `heddle check` prints for the repo at `dir`."
  [dir]
  (with-config dir (fn [_ source] {:out (str "No errors in " source ".")})))

(defn init
  "What `heddle init` prints after writing a starter config to the root of the
  repo at `dir`."
  [dir]
  (let [f (io/file dir config/file-name)]
    (if (.exists f)
      {:exit 1 :err (str "There's already a " config/file-name " in " dir ". Remove it first to start over.")}
      (try
        (spit f starter/toml)
        {:exit 0
         :out (str "Wrote a starter config to " f ".\n"
                   "Uncomment what you need, then run `heddle check`.\n"
                   "README's \"The config in full\" describes every key.")}
        (catch Exception e
          {:exit 1 :err (str "Couldn't write " f ": " (ex-message e))})))))

(defn outcome
  "What a command other than serve prints, as :out and :err, and its :exit
  status."
  [{:keys [command error dir server-types]}]
  (let [dir (or dir (System/getProperty "user.dir"))]
    (case command
      :help {:exit 0 :out usage}
      :languages (languages dir server-types)
      :check (check dir)
      :init (init dir)
      {:exit 2 :err (str "heddle: " error "\n" usage)})))

(defn- report! [{:keys [exit out err]}]
  (when err
    (binding [*out* *err*] (println err)))
  (when out
    (println out))
  (flush)
  (System/exit exit))

(defn stdin-terminal?
  "Whether a person is typing into heddle, rather than an editor or a pipe
  feeding it."
  []
  (try
    (zero? (-> (ProcessBuilder. ["test" "-t" "0"])
               (.redirectInput ProcessBuilder$Redirect/INHERIT)
               .start
               .waitFor))
    (catch Exception _ false)))

(defn- why-nothing-to-serve
  "Why serving `server-types` (or every server type, if empty) from the repo at
  `dir` would serve nothing, or nil if it would serve something."
  [dir server-types]
  (let [{:keys [repo config problem]} (proxy/load-config [dir])
        unknown (config/unknown-server-types config server-types)]
    (cond
      problem (second problem)
      (and (seq server-types) (= (count unknown) (count server-types)))
      (config/unknown-server-types-message (str (io/file repo config/file-name)) unknown))))

(defn nothing-to-serve
  "What `heddle serve` prints, and its :exit status, when run by hand in `dir`
  with nothing there to serve, or nil if it should serve as usual. It always
  serves when stdin isn't a terminal, since the client may name a root with a
  config at `initialize`."
  [dir server-types]
  (when (stdin-terminal?)
    (when-let [reason (why-nothing-to-serve dir server-types)]
      {:exit 1
       :err (str "heddle: " reason "\n"
                 "heddle serve speaks LSP over stdin and stdout, for an editor to run.\n"
                 "`heddle init` writes a starter config, and `heddle check` checks one.")})))

(defn- serve! [eager? server-types]
  (let [proxy (proxy/create {:send #(lsp/write-message System/out %)
                             :connect backend/connect-process
                             :server-types (when (seq server-types) (set server-types))
                             :eager eager?
                             :cwd (System/getProperty "user.dir")
                             :exit #(System/exit %)})
        in (BufferedInputStream. System/in)]
    (loop []
      (if-let [msg (lsp/read-message in)]
        (do (proxy/handle-client! proxy msg)
            (recur))
        (proxy/handle-client! proxy (lsp/notification "exit" nil))))))

(defn -main [& args]
  (let [{:keys [command eager? server-types] :as parsed} (parse-args args)]
    (if (= :serve command)
      (if-let [refusal (nothing-to-serve (System/getProperty "user.dir") server-types)]
        (report! refusal)
        (serve! eager? server-types))
      (report! (outcome parsed)))))
