(ns onyx-peers.jobs.basic-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [jepsen 
             [client :as client]
             [checker :as check]
             [generator :as gen]]
            [onyx-jepsen.core :as oj]
            [onyx.test-helper :refer [load-config with-test-env]]
            [onyx-jepsen.simple-job :as simple-job]

            [onyx.plugin.bookkeeper]
            [onyx-peers.functions.functions]
            [taoensso.timbre :refer [fatal info]]
            [onyx.api]))

(deftest basic-test
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/id id)
        peer-config (assoc (:peer-config config) :onyx/id id)
        test "basic-clojure.test"
        version "dummy-version"
        {:keys [client checker model generator] :as basic-test} (oj/basic-test env-config peer-config version)
        batch-size 2
        events [{:type :invoke :f :add :value 1}
                {:type :invoke :f :add :value 2}
                {:type :invoke :f :add :value 3}
                {:type :invoke :f :add :value 4}
                {:type :invoke :f :add :value 5}
                {:type :invoke :f :add :value 6}
                {:type :invoke :f :submit-job :job-num 0 :n-jobs 1 :params {:batch-size 1}}
                {:type :invoke :f :close-ledgers-await-completion}
                {:type :invoke :f :read-ledgers}
                {:type :invoke :f :read-peer-log :timeout 1000}]
        simple-gen (gen/seq events)]
    (with-test-env [test-env [15 env-config peer-config]]
      (let [setup-client (client/setup! client "onyx-unit" "n1")
            history (reduce (fn [vs event]
                              (conj vs event (client/invoke! setup-client test (gen/op simple-gen test 0))))
                            []
                            events)
            results (check/check checker test model history)]
        (println results)
        (is (:valid? results))))))
