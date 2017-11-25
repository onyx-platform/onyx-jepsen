(ns onyx-jepsen.onyx-aggregation-test
  (:require [clojure.test :refer :all]
            [onyx-jepsen.onyx-test :as onyx-test]
            [jepsen 
             [client :as client]
             [checker :as check]
             [generator :as gen]]
            [onyx-jepsen.gen :as onyx-gen]
            [jepsen.core :as jc]
            [jepsen.control :as c]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(def test-name 
  "onyx-aggregation-test")

(def env-config
  (-> "resources/prod-env-config.edn" slurp read-string))

(def peer-config
  (-> "resources/prod-peer-config.edn" slurp read-string))

(def test-setup 
  {:job-params {:batch-size (inc (rand-int 20))}
   :job-type :window-state-job
   :nemesis :random-halves ;:bridge-shuffle ; :bridge-shuffle or :random-halves
   ;; increase awake and stop time to give gcs more time to settle
   :awake-secs 600
   :stopped-secs 600
   :time-limit 1800
   :n-nodes 5
   ; may or may not work when 5 is not divisible by n-jobs
   :n-jobs 1
   ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
   :n-peers 3})

(defn generator [{:keys [job-type time-limit awake-secs stopped-secs n-jobs job-params] :as test-setup}]
  (let [input-data (map (fn [n]
                          {:id n :age (rand-int 100) :curr (System/currentTimeMillis) :event-time (java.util.Date. n)}) 
                        (range))] 
    (gen/phases
      (->> (onyx-gen/filter-new identity 
                                (onyx-gen/frequency [(onyx-gen/adds input-data)
                                                     (onyx-gen/submit-job-gen job-type n-jobs job-params)
                                                     (onyx-gen/gc-peer-logs)
                                                     ]
                                                    [0.99
                                                     0.01
                                                     0.000]))
           ;(gen/stagger 1/15)
           (gen/stagger 1/5)
           (gen/nemesis (onyx-gen/start-stop-nemesis-seq awake-secs stopped-secs))
           (gen/time-limit time-limit)) 

      ;; Bring everything back at the end
      ;; This way we can test that the peers came back up
      (gen/nemesis (gen/once {:type :info :f :stop}))
      ;; Sleep for a while to give peers a chance to come back up
      ;; Should be enough time that curator backoff * max-retries is covered
      (gen/sleep 300)

      (onyx-gen/close-await-completion-gen)
      (onyx-gen/read-peer-log-gen)
      (onyx-gen/read-ledgers-gen :persist)
      (onyx-gen/read-ledgers-gen :annotate-job))))

(deftest aggregation-test
  (println "Running with test setup:" test-setup)
  (is (-> (onyx-test/jepsen-test env-config peer-config test-setup test-name version (generator test-setup))
          jc/run!
          :results
          :valid?)))
