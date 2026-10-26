(ns heddle.main
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [heddle.backend :as backend]
            [heddle.config :as config]
            [heddle.json :as json]
            [heddle.lsp :as lsp]
            [heddle.proxy :as proxy]
            [heddle.session :as session]
            [heddle.starter :as starter]
            [heddle.trace :as trace])
  (:import (java.io BufferedInputStream)
           (java.lang ProcessBuilder$Redirect)))

(def ^:private usage
  "usage: heddle [serve] [--eager] [--trace FILE] [--config FILE] [SERVER-TYPE...]
       heddle languages [--config FILE] [DIR [SERVER-TYPE...]]
       heddle check [--config FILE] [DIR]
       heddle config [--config FILE] [DIR]
       heddle init [DIR]
       heddle help

serve, the default, speaks LSP over stdin and stdout. It serves every server
type in the config, or only those named. Server types can only be named after
serve.

--eager starts every backend when the client initializes, and waits up to
30 s for them before answering, so that the answer lists their features and
commands. Otherwise each backend starts when one of its files opens.

--trace FILE appends every message between the client, heddle and the
backends to FILE, one JSON object per line. HEDDLE_TRACE=FILE does the same.
Either one overrides the config's [trace] table.

--config FILE reads the config from FILE instead of the root of the repo.
HEDDLE_CONFIG=FILE does the same, unless --config is given. For serve, the
repo is still the client's first workspace root, or else the current
directory. For languages, check and config, it's DIR. A relative path in
FILE's [cache] or [trace] table is relative to FILE's own directory.

languages prints, as JSON, the languages that the config for DIR covers,
counting only the server types named, if any.

check reads the config for DIR and prints its errors and warnings.
It exits non-zero if the config has errors.

config prints the config for DIR as TOML, with each scope's values layered
over its server type's boot table and its runner's defaults. A comment beside
each of those values says where it came from. It exits non-zero if the config
has errors.

init writes a starter config, all commented out, to the root of DIR. It won't
replace a config that's already there.

DIR is the current directory unless given.")

(def ^:private commands #{"serve" "languages" "check" "config" "init" "help"})

(def ^:private trace-variable "HEDDLE_TRACE") ; the environment variable that serves as --trace
(def ^:private config-variable "HEDDLE_CONFIG") ; the environment variable that serves as --config

(def ^:private file-options ["--config" "--trace"]) ; the options that take a FILE

(def ^:private command-options {"serve" #{"--eager" "--trace" "--config"}
                                "languages" #{"--config"}
                                "check" #{"--config"}
                                "config" #{"--config"}
                                "init" #{}})

(defn- option? [arg]
  (str/starts-with? arg "-"))

(defn- take-files
  "The FILEs that `args` give with each of `file-options`, as a map from the
  option to its FILEs, and `args` without those FILEs."
  [args]
  (loop [args args, files {}, kept []]
    (if-let [[arg & more] (seq args)]
      (if (and (some #{arg} file-options) more (not (option? (first more))))
        (recur (rest more) (update files arg (fnil conj []) (first more)) (conj kept arg))
        (recur more files (conj kept arg)))
      [files kept])))

(defn- file-option-error
  "What's wrong with how `options` give the file options, whose FILEs are
  `files`, or nil if nothing is."
  [options files]
  (some (fn [option]
          (case (count (filter #{option} options))
            0 nil
            1 (when (empty? (files option)) (str option " needs a file"))
            (str option " can only be given once")))
        file-options))

(defn parse-args
  "The command that the command line `args` asks for, as a map with its
  :command, or with an :error saying what's wrong with them."
  [args]
  (let [[files args-without-files] (take-files args)
        options (filter option? args-without-files)
        [command & operands] (remove option? args-without-files)
        allowed (command-options (or command "serve") #{})
        unknown (first (remove allowed options))
        file-error (file-option-error options files)
        [trace] (files "--trace")
        [config] (files "--config")]
    (cond
      (or (some #{"--help" "-h"} options) (= "help" command))
      {:command :help}

      (some #{""} args)
      {:error "empty argument"}

      (and command (not (commands command)))
      {:error (str "unknown command " (pr-str command) " (server types can only be named after serve)")}

      (and unknown (or (nil? command) (= "serve" command)))
      {:error (str "unknown option " unknown)}

      (and unknown (empty? allowed))
      {:error (str command " takes no options, but was given " unknown)}

      unknown
      {:error (str command " takes only " (str/join ", " (sort allowed)) ", but was given " unknown)}

      file-error
      {:error file-error}

      (or (nil? command) (= "serve" command))
      (cond-> {:command :serve
               :eager? (boolean (some #{"--eager"} options))
               :server-types (vec operands)}
        trace (assoc :trace trace)
        config (assoc :config config))

      (= "languages" command)
      (cond-> {:command :languages :dir (first operands) :server-types (vec (rest operands))}
        config (assoc :config config))

      (next operands)
      {:error (str command " takes at most one directory")}

      :else
      (cond-> {:command (keyword command) :dir (first operands)}
        config (assoc :config config)))))

(defn config-file
  "The config file that the command line `parsed` names with --config, or else
  that HEDDLE_CONFIG names, as an absolute path, or nil if neither names one.
  `getenv` looks up an environment variable."
  [{:keys [config]} getenv]
  (some-> (or config (not-empty (getenv config-variable))) io/file .getAbsolutePath))

(defn- with-config
  "Calls `f` with the config in `config-file`, or else at the root of the repo
  at `dir`, and the config's path, for what a command prints (:out and :err)
  and its :exit status, which is 0 unless `f` says otherwise. :err has the
  config's problems on failure, and otherwise its warnings unless `f` gives an
  :err of its own."
  [dir config-file f]
  (try
    (let [source (str (or config-file (io/file dir config/file-name)))]
      (if-let [c (config/read-file source)]
        (let [warnings (config/warnings c)]
          (merge {:exit 0}
                 (when (seq warnings) {:err (config/warnings-message source warnings)})
                 (f c source)))
        {:exit 1 :err (if config-file
                        (str "There's no config file " config-file ".")
                        (str "There's no " config/file-name " in " dir "."))}))
    (catch Exception e
      {:exit 1 :err (ex-message e)})))

(defn languages
  "What `heddle languages` prints for the repo at `dir`, when serving
  `server-types` (or every server type, if empty), with the config in
  `config-file`, if given."
  ([dir server-types] (languages dir server-types nil))
  ([dir server-types config-file]
   (with-config dir config-file (fn [c source]
                                  (if-let [unknown (seq (config/unknown-server-types c server-types))]
                                    {:exit 1 :err (config/unknown-server-types-message source unknown)}
                                    {:out (json/write-str {"languages" (config/languages c server-types)})})))))

(defn check
  "What `heddle check` prints for the repo at `dir`, with the config in
  `config-file`, if given."
  ([dir] (check dir nil))
  ([dir config-file]
   (with-config dir config-file (fn [c source]
                                  {:out (str/join "\n" (remove nil? [(str "No errors in " source ".")
                                                                     (config/where-each-runs-message c)]))}))))

(defn expanded-config
  "What `heddle config` prints for the repo at `dir`, with the config in
  `config-file`, if given."
  ([dir] (expanded-config dir nil))
  ([dir config-file]
   (with-config dir config-file (fn [c source]
                                  {:out (config/expanded-toml c (slurp source))}))))

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
  [{:keys [command error dir server-types config]}]
  (let [dir (or dir (System/getProperty "user.dir"))]
    (case command
      :help {:exit 0 :out usage}
      :languages (languages dir server-types config)
      :check (check dir config)
      :config (expanded-config dir config)
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
  `dir`, with the config in `config-file` if given, would serve nothing, or nil
  if it would serve something."
  [dir server-types config-file]
  (let [{:keys [source config problem]} (session/load-config [dir] config-file)
        unknown (config/unknown-server-types config server-types)]
    (cond
      problem (second problem)
      (and (seq server-types) (= (count unknown) (count server-types)))
      (config/unknown-server-types-message source unknown))))

(defn nothing-to-serve
  "What `heddle serve` prints, and its :exit status, when run by hand in `dir`
  with nothing there to serve, or nil if it should serve as usual. It always
  serves when stdin isn't a terminal, since the client may name a root with a
  config at `initialize`."
  [dir server-types config-file]
  (when (stdin-terminal?)
    (when-let [reason (why-nothing-to-serve dir server-types config-file)]
      {:exit 1
       :err (str "heddle: " reason "\n"
                 "heddle serve speaks LSP over stdin and stdout, for an editor to run.\n"
                 "`heddle init` writes a starter config, and `heddle check` checks one.")})))

(defn- configured-trace-path
  "The file that the config for the client's `initialize` names to trace to, or
  nil if it names none."
  [{:strs [params]} {:keys [cwd config-file]}]
  (:trace-path (session/load-config (session/config-roots params cwd) config-file)))

(defn serve-message!
  "Traces the client's `msg` with the tracer in the atom `tracing`, if any, then
  has `proxy` handle it. Until something switches tracing on, `initialize` puts
  a tracer in `tracing` for the file that its config names. Since the proxy
  starts no backend until it handles `initialize`, the trace then has every
  message from `initialize` on."
  [proxy tracing msg]
  (when (and (nil? @tracing) (= "initialize" (get msg "method")))
    (some->> (configured-trace-path msg proxy) trace/tracer (reset! tracing)))
  (some-> @tracing (trace/from-client msg))
  (proxy/handle-client! proxy msg))

(defn- serve! [eager? server-types trace-path config-file]
  (let [tracing (atom (some-> trace-path trace/tracer))
        proxy (proxy/create (trace/traced {:send #(lsp/write-message System/out %)
                                           :connect backend/connect-process
                                           :server-types (when (seq server-types) (set server-types))
                                           :eager eager?
                                           :config-file config-file
                                           :cwd (System/getProperty "user.dir")
                                           :exit #(System/exit %)}
                                          (trace/switchable tracing)))
        in (BufferedInputStream. System/in)]
    (loop []
      (if-let [msg (lsp/read-message in)]
        (do (serve-message! proxy tracing msg)
            (recur))
        (proxy/handle-client! proxy (lsp/notification "exit" nil))))))

(defn -main [& args]
  (let [{:keys [command eager? server-types trace] :as parsed} (parse-args args)
        config (config-file parsed #(System/getenv %))]
    (if (= :serve command)
      (if-let [refusal (nothing-to-serve (System/getProperty "user.dir") server-types config)]
        (report! refusal)
        (serve! eager? server-types (or trace (not-empty (System/getenv trace-variable))) config))
      (report! (outcome (assoc parsed :config config))))))
