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
            [onyx-peers.lifecycles.restart-lifecycle]
            [taoensso.timbre :refer [fatal info]]
            [onyx.api]))

(deftest ^:test-jepsen-tests basic-test
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/id id)
        peer-config (assoc (:peer-config config) :onyx/id id)
        test "basic-clojure.test"
        version "dummy-version"
        test-setup {:job-params {:batch-size 1}
                    :nemesis :na
                    :time-limit 800 ; unused in this test
                    :awake-mean 200 ; unused in this test
                    :stopped-mean 100 ; unused in this test
                    ; may or may not work when 5 is not divisible by n-jobs
                    :n-jobs 1
                    ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
                    :n-peers 3}
        fake-clients 5
        n-peers-total (* fake-clients (:n-peers test-setup))
        {:keys [client checker model generator] :as basic-test} (oj/basic-test env-config peer-config test-setup version)
        events [{:type :invoke :f :add :value 1}
                {:type :invoke :f :add :value 2}
                {:type :invoke :f :add :value 3}
                {:type :invoke :f :add :value 4}
                {:type :invoke :f :add :value 5}
                {:type :invoke :f :add :value 6}
                {:type :invoke 
                 :f :submit-job 
                 :job-type :simple-job 
                 :job-num 0 
                 :n-jobs (:n-jobs test-setup) 
                 :params (:job-params test-setup)}
                {:type :invoke :f :close-ledgers-await-completion}
                {:type :invoke :f :read-ledgers :task :persist}
                {:type :invoke :f :read-peer-log :timeout 1000}]
        simple-gen (gen/seq events)]
    (with-test-env [test-env [n-peers-total env-config peer-config]]
      (let [setup-client (client/setup! client "onyx-unit" "n1")
            history (reduce (fn [vs event]
                              (conj vs event (client/invoke! setup-client test (gen/op simple-gen test 0))))
                            []
                            events)
            results (check/check checker test model history)]
        (println results)
        (is (:valid? results))))))
