(ns onyx-jepsen.onyx-basic-test
  (:require [clojure.test :refer :all]
            [jepsen.core :as jc]
            [jepsen.generator :as gen]
            [onyx-jepsen.gen :as onyx-gen]
            [onyx-jepsen.onyx-test :as onyx-test]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(def test-name 
  "onyx-basic-test")

(def env-config
  (-> "resources/prod-env-config.edn" slurp read-string))

(def peer-config
  (-> "resources/prod-peer-config.edn" slurp read-string))

(def test-setup 
  {:job-params {:batch-size 10}
   :job-type :simple-job
   :nemesis (first (shuffle [:bridge-shuffle #_:random-halves])) ; :bridge-shuffle or :random-halves
   :awake-secs 400
   :stopped-secs 400
   :time-limit 1600
   :n-nodes 5
   ; may or may not work when 5 is not divisible by n-jobs
   :n-jobs (first (shuffle [1 5]))
   ; Minimum total = 5 (input ledgers) + 1 intermediate + 1 output
   :n-peers 3})

(defn generator [{:keys [job-type time-limit awake-secs stopped-secs n-jobs job-params] :as test-setup}]
  (gen/phases
    (->> (onyx-gen/filter-new identity 
                              (onyx-gen/frequency [(onyx-gen/adds (range)) 
                                                   (onyx-gen/submit-job-gen job-type n-jobs job-params)
                                                   (gen/once (onyx-gen/gc-peer-logs))]
                                                  [0.99
                                                   0.01
                                                   0.001]))
         (gen/stagger 1/10)
         ;(gen/delay 1)
         (gen/nemesis (onyx-gen/start-stop-nemesis-seq awake-secs stopped-secs))
         (gen/time-limit time-limit)) 

    ;; Bring everything back at the end
    ;; This way we can test that the peers came back up
    (gen/nemesis (gen/once {:type :info :f :stop}))
    ;; Sleep for a while to give peers a chance to come back up
    ;; Should be enough time that curator backoff * max-retries is covered
    (gen/sleep 300)

    (onyx-gen/close-await-completion-gen)
    (onyx-gen/read-ledgers-gen :persist)
    (onyx-gen/read-peer-log-gen)))

(deftest basic-test
  (println "Running with test setup:" test-setup)
  (is (-> (onyx-test/jepsen-test env-config peer-config test-setup test-name version (generator test-setup))
          jc/run!
          :results
          :valid?)))
