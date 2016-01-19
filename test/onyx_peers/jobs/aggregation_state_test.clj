(ns onyx-peers.jobs.aggregation-state-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [jepsen 
             [client :as client]
             [checker :as check]
             [generator :as gen]]
            [onyx-jepsen.core :as oj]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.log.zookeeper :as zk]
            [onyx-jepsen.simple-job :as simple-job]
            [onyx.compression.nippy :as nippy]
            [onyx-peers.functions.functions]
            [onyx.plugin.bookkeeper]
            [onyx.test-helper :refer [load-config with-test-env]]
            [taoensso.timbre :refer [fatal info]]
            [onyx.api]))

(def input
  [{:id 1  :age 21 :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 3  :age 3  :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 3  :age 3  :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 3  :age 3  :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 4  :age 64 :event-time #inst "2015-09-13T03:06:00.829-00:00"}
   {:id 5  :age 53 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:id 6  :age 52 :event-time #inst "2015-09-13T03:08:00.829-00:00"}
   {:id 7  :age 24 :event-time #inst "2015-09-13T03:09:00.829-00:00"}
   {:id 8  :age 35 :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:id 9  :age 49 :event-time #inst "2015-09-13T03:25:00.829-00:00"}
   {:id 10 :age 37 :event-time #inst "2015-09-13T03:45:00.829-00:00"}
   {:id 3  :age 3  :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 11 :age 15 :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   {:id 12 :age 22 :event-time #inst "2015-09-13T03:56:00.829-00:00"}
   {:id 13 :age 83 :event-time #inst "2015-09-13T03:59:00.829-00:00"}
   {:id 14 :age 60 :event-time #inst "2015-09-13T03:32:00.829-00:00"}
   {:id 15 :age 35 :event-time #inst "2015-09-13T03:16:00.829-00:00"}])

(def expected-windows
  [[Double/NEGATIVE_INFINITY 
    Double/POSITIVE_INFINITY
    #{{:id 1,
       :age 21,
       :event-time #inst "2015-09-13T03:00:00.829-00:00"}
      {:id 2,
       :age 12,
       :event-time #inst "2015-09-13T03:04:00.829-00:00"}
      {:id 3,
       :age 3,
       :event-time #inst "2015-09-13T03:05:00.829-00:00"}
      {:id 4,
       :age 64,
       :event-time #inst "2015-09-13T03:06:00.829-00:00"}
      {:id 5,
       :age 53,
       :event-time #inst "2015-09-13T03:07:00.829-00:00"}
      {:id 6,
       :age 52,
       :event-time #inst "2015-09-13T03:08:00.829-00:00"}
      {:id 7,
       :age 24,
       :event-time #inst "2015-09-13T03:09:00.829-00:00"}
      {:id 8,
       :age 35,
       :event-time #inst "2015-09-13T03:15:00.829-00:00"}
      {:id 9,
       :age 49,
       :event-time #inst "2015-09-13T03:25:00.829-00:00"}
      {:id 10,
       :age 37,
       :event-time #inst "2015-09-13T03:45:00.829-00:00"}
      {:id 11,
       :age 15,
       :event-time #inst "2015-09-13T03:03:00.829-00:00"}
      {:id 12,
       :age 22,
       :event-time #inst "2015-09-13T03:56:00.829-00:00"}
      {:id 13,
       :age 83,
       :event-time #inst "2015-09-13T03:59:00.829-00:00"}
      {:id 14,
       :age 60,
       :event-time #inst "2015-09-13T03:32:00.829-00:00"}
      {:id 15,
       :age 35,
       :event-time #inst "2015-09-13T03:16:00.829-00:00"}}]])

(deftest ^:test-jepsen-tests aggregation-state-test
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
        events (into (mapv (fn [v]
                             {:type :invoke :f :add :value v})
                           input)
                     [{:type :invoke 
                       :f :submit-job 
                       :job-type :window-state-job 
                       :job-num 0 
                       :n-jobs (:n-jobs test-setup) 
                       :params (:job-params test-setup)}
                      {:type :invoke :f :close-ledgers-await-completion}
                      {:type :invoke :f :read-ledgers :task :persist}
                      {:type :invoke :f :read-ledgers :task :identity-log}
                      {:type :invoke :f :read-peer-log :timeout 1000}])
        
        simple-gen (gen/seq events)]
    (with-test-env [test-env [n-peers-total env-config peer-config]]
      (let [setup-client (client/setup! client "onyx-unit" "n1")
            history (reduce (fn [vs event]
                              (conj vs event (client/invoke! setup-client test (gen/op simple-gen test 0))))
                            []
                            events)
            _ (Thread/sleep 2000)
            results (check/check checker test model history)]
        (println results)
        (is (:valid? results))))))
