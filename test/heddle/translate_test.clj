(ns heddle.translate-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.translate :as translate]))

(def ^:private ctx
  {:repo "/r" :paths {"." "/src" "services/api" "/app"} :cache "/c"})

(defn- round-trips [client server]
  (is (= server (translate/to-server ctx client)))
  (is (= client (translate/to-client ctx server))))

(deftest whole-payloads
  (round-trips {"textDocument" {"uri" "file:///r/services/api/app/models/user.rb"}
                "position" {"line" 3 "character" 7}
                "workspaceFolders" [{"uri" "file:///r/services/api" "name" "api"}]
                "context" {"includeDeclaration" true "other" nil}}
               {"textDocument" {"uri" "file:///app/app/models/user.rb"}
                "position" {"line" 3 "character" 7}
                "workspaceFolders" [{"uri" "file:///app" "name" "api"}]
                "context" {"includeDeclaration" true "other" nil}}))

(deftest most-specific-mapping-wins
  (round-trips "file:///r/services/api/x.rb" "file:///app/x.rb")
  (round-trips "file:///r/services/web/x.ts" "file:///src/services/web/x.ts")
  (round-trips "file:///r" "file:///src"))

(deftest uris-inside-strings
  (testing "markdown links in hover text"
    (round-trips "Defined in [user.rb](file:///r/services/api/user.rb#L18,3-20,6)."
                 "Defined in [user.rb](file:///app/user.rb#L18,3-20,6)."))
  (testing "the path ends where text usually ends a URI, keeping what follows"
    (doseq [end [")" "]" "#L1" "\"" "'" "?q" " and more"]]
      (round-trips (str "file:///r/lib/x.rb" end) (str "file:///src/lib/x.rb" end)))))

(deftest percent-encoded-paths
  (round-trips "file:///r/lib/my%20file%F0%9F%98%80.rb" "file:///src/lib/my%20file%F0%9F%98%80.rb")
  (round-trips "file:///r/lib/a+b$c%23d.rb" "file:///src/lib/a+b$c%23d.rb")
  (testing "malformed escapes are left alone"
    (is (= "file:///r/lib/%zz.rb" (translate/to-server ctx "file:///r/lib/%zz.rb"))))
  (testing "a URI that needs no translation keeps its encoding"
    (is (= "file:///elsewhere/a%3Ab" (translate/to-server ctx "file:///elsewhere/a%3Ab")))))

(deftest map-keys
  (round-trips {"changes" {"file:///r/lib/x.rb" [{"newText" "y"}]}}
               {"changes" {"file:///src/lib/x.rb" [{"newText" "y"}]}}))

(deftest bare-paths
  (testing "a string that's entirely a path covered by `paths`"
    (round-trips "/r/services/api/Gemfile" "/app/Gemfile"))
  (testing "strings that aren't whole paths are left alone"
    (doseq [s ["/" "." ":" "" "a /src/x.rb" "/src/x.rb and more" "src/x.rb"]]
      (is (= s (translate/to-server ctx s)))
      (is (= s (translate/to-client ctx s)))))
  (testing "a lone `/` is left alone, even with the repo mounted at the root"
    (let [ctx (assoc ctx :paths {"." "/"})]
      (is (= "/" (translate/to-client ctx "/")))
      (is (= "/" (translate/to-server ctx "/")))))
  (testing "a client path outside every mapping is left alone"
    (is (= "/etc/hosts" (translate/to-server ctx "/etc/hosts")))))

(deftest fetched-files
  (testing "only inside URIs"
    (round-trips "file:///c/usr/lib/ruby/x.rb" "file:///usr/lib/ruby/x.rb")
    (round-trips "[x](file:///c/usr/lib/my%20gem/x.rb#L2)" "[x](file:///usr/lib/my%20gem/x.rb#L2)")
    (is (= "/usr/lib/ruby/x.rb" (translate/to-client ctx "/usr/lib/ruby/x.rb")))
    (is (= "/c/usr/lib/ruby/x.rb" (translate/to-server ctx "/c/usr/lib/ruby/x.rb"))))
  (testing "the root, or a path that could escape the cache, is left alone"
    (is (= "file:///" (translate/to-client ctx "file:///")))
    (is (= "file:///usr/../etc/x" (translate/to-client ctx "file:///usr/../etc/x"))))
  (testing "listed from a server payload, decoded"
    (is (= #{"/usr/lib/ruby/x.rb" "/usr/lib/my gem/y.rb"}
           (translate/fetched-files ctx {"file:///usr/lib/ruby/x.rb" "file:///app/x.rb"
                                         "contents" "[y](file:///usr/lib/my%20gem/y.rb#L1)"
                                         "items" ["/usr/lib/bare.rb" "file:///src/z.rb" "file:///" "file:///usr/../etc/x"]}))))
  (testing "without `paths`, no file is fetched"
    (let [ctx (assoc ctx :paths nil)]
      (is (= "file:///usr/lib/x.rb" (translate/to-client ctx "file:///usr/lib/x.rb")))
      (is (= #{} (translate/fetched-files ctx "file:///usr/lib/x.rb"))))))

(deftest without-paths-everything-maps-to-itself
  (let [ctx (assoc ctx :paths nil)
        v {"uri" "file:///r/x.rb" "path" "/r/x.rb" "other" "/elsewhere"}]
    (is (= v (translate/to-server ctx v)))
    (is (= v (translate/to-client ctx v)))))
