(ns onyx-peers.jobs.basic-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [onyx.bookkeeper.bookkeeper :as bkserver]
            [onyx.plugin.core-async :refer [take-segments!]]
            [jepsen 
             [client :as client]
             [checker :as check]
             [generator :as gen]]
            [com.stuartsierra.component :as component]
            [onyx.test-helper :refer [load-config with-test-env]]
            [onyx-jepsen.simple-job :as simple-job]
            [onyx-jepsen.onyx-test :as onyx-test]
            [onyx.plugin.bookkeeper]
            [onyx-peers.functions.functions]
            [onyx.http-query]
            [jepsen.store :refer [start-logging!]]
            [onyx-peers.lifecycles.restart-lifecycle]
            [com.stuartsierra.component :as component]
            ;[onyx.lifecycle.metrics.timbre]
            [taoensso.timbre :refer [fatal info]]
            [onyx.api]))

(def input
  (vec (range 6)))

(deftest ^:test-jepsen-tests basic-test
  (try (let [id (java.util.UUID/randomUUID)
             config (load-config)
             env-config (assoc (:env-config config) :onyx/tenancy-id id)
             peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
             test "basic-clojure-test"
             version "dummy-version"
             test-setup {:job-params {:batch-size 1}
                         :job-type :simple-job
                         :nemesis :na
                         :time-limit 800 ; unused in non-jepsen test
                         :awake-secs 200 ; unused in non-jepsen test
                         :stopped-secs 100 ; unused in non-jepsen test
                         ; may or may not work when 5 is not divisible by n-jobs
                         :n-nodes 1
                         :n-jobs 1
                         ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
                         :n-peers 15}
             n-peers-total (* (:n-nodes test-setup) (:n-peers test-setup))
             events (into (mapv (fn [v]
                                  {:type :invoke :f :add :value v})
                                input)
                          [{:type :invoke 
                            :f :submit-job 
                            :job-type (:job-type test-setup) 
                            :job-num 0 
                            :n-jobs (:n-jobs test-setup) 
                            :params (:job-params test-setup)}
                           {:type :invoke :f :close-ledgers-await-completion}
                           {:type :invoke :f :read-ledgers :task :persist}
                           {:type :invoke :f :read-peer-log :timeout 1000}])
             {:keys [client checker model generator]
              :as basic-test} (onyx-test/jepsen-test env-config peer-config 
                                                     test-setup test version 
                                                     (gen/seq events))]
         (start-logging! basic-test)
         (with-test-env [test-env [n-peers-total env-config peer-config]]
           (try (let [log (:log (:env test-env))
                      bk-config (assoc env-config 
                                       :onyx.bookkeeper/server? true 
                                       :onyx.bookkeeper/delete-server-data? true
                                       :onyx.bookkeeper/local-quorum-ports [3888 3889 3890]
                                       :onyx.bookkeeper/local-quorum? true)
                      multi-bookie-server (component/start (bkserver/multi-bookie-server bk-config log))] 
                  (try 
                   (let [setup-client (client/setup! client "onyx-unit" "n1")]
                     (try
                      (let [history (reduce (fn [vs event]
                                              (info "INVOKING" event)
                                              (conj vs event (client/invoke! setup-client test (gen/op generator test 0))))
                                            []
                                            events)
                            results (check/check checker test model history {})]
                        (println "FINAL" results)
                        (is (:valid? results)))
                      (finally
                       (client/teardown! setup-client test))))
                   (Thread/sleep 10000)
                   (finally
                    (component/stop multi-bookie-server)))))))))
