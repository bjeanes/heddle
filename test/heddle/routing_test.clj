(ns heddle.routing-test
  (:require [clojure.test :refer [deftest is testing]]
            [heddle.routing :as routing]))

(def config
  {"servers" {"lint" {"languages" ["ruby"]}
              "ruby" {"languages" ["ruby"]}
              "types" {"languages" ["ruby"] "priority" 1}}
   "scopes" {"." {"lint" {} "ruby" {}}
             "api" {"ruby" {} "types" {}}}})

(def session
  {:config config
   :repo "/repo"
   :specs {["lint" "."] {} ["ruby" "."] {} ["ruby" "api"] {} ["types" "api"] {}}})

(defn- backend
  ([slot] (backend slot {}))
  ([slot spec] {:spec (assoc spec :slot slot)}))

(deftest handlers
  (testing "by priority, then by name where priorities tie, each in its deepest scope"
    (is (= [["types" "api"] ["lint" "."] ["ruby" "api"]]
           (routing/handlers session {} "file:///repo/api/a.rb" "ruby"))))
  (testing "only the server types that some scope starts for the file"
    (is (= [["lint" "."] ["ruby" "."]]
           (routing/handlers session {} "file:///repo/b.rb" "ruby"))))
  (testing "a fetched file belongs to the backend that fetched it"
    (let [b (backend ["ruby" "api"] {:cache "/cache/ruby"})]
      (is (= [["ruby" "api"]]
             (routing/handlers session {["ruby" "api"] {:backend b}} "file:///cache/ruby/gems/x.rb" "ruby")))))
  (testing "nothing for a file outside the repo, or in no server type's languages"
    (is (empty? (routing/handlers session {} "file:///elsewhere/a.rb" "ruby")))
    (is (empty? (routing/handlers session {} "file:///repo/README.md" "markdown")))))

(deftest prioritised
  (testing "by priority, then by name where priorities tie, then by scope"
    (let [slots [["ruby" "api"] ["types" "api"] ["ruby" "."] ["lint" "."]]
          backends (into {} (for [k slots] [k {:backend (backend k)}]))]
      (is (= [["types" "api"] ["lint" "."] ["ruby" "."] ["ruby" "api"]]
             (map #(get-in % [:spec :slot]) (routing/prioritised config backends)))))))

(deftest command-backend
  (let [root (backend ["ruby" "."])
        api (backend ["ruby" "api"])
        v1 (backend ["ruby" "api/v1"])
        lint (backend ["lint" "."])
        backends [lint v1 api root]
        offered-by #(routing/command-backend "/repo" backends "ruby" %1 %2)
        backend-for #(offered-by [] %)]
    (testing "the deepest scope containing a file the arguments refer to"
      (is (= v1 (backend-for [{"textDocument" {"uri" "file:///repo/api/v1/a.rb"}}])))
      (is (= api (backend-for [{"uri" "file:///repo/api/a.rb"}]))))
    (testing "the first by scope when the arguments refer to no file in the repo"
      (is (= root (backend-for ["x"])))
      (is (= root (backend-for ["file:///elsewhere/a.rb"]))))
    (testing "only among the backends that offer the command, where any do"
      (is (= api (offered-by [api] [{"uri" "file:///repo/a.rb"}])))
      (is (= api (offered-by [api] ["x"])))
      (is (= root (offered-by [api root] ["x"]))))
    (testing "among them all when those that offer it are of another server type"
      (is (= v1 (offered-by [lint] [{"uri" "file:///repo/api/v1/a.rb"}]))))
    (testing "none without a live backend of the server type"
      (is (nil? (routing/command-backend "/repo" backends "types" [] []))))))
