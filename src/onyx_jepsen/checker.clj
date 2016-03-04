(ns onyx-jepsen.checker
  (:require [onyx.extensions]
            [jepsen [checker :as checker]]
            [onyx.system :as system]
            [onyx.log.zookeeper]
            [onyx.log.curator]
            [clojure.stacktrace]
            [com.stuartsierra.component :as component]
            [onyx.log.replica :as replica]
            [taoensso.timbre :as timbre]
            [clojure.core.async :as casync :refer [chan >!! <!! close! alts!!]]
            [clojure.tools.logging :refer :all]))


(defn base-replica [peer-config]
  (merge replica/base-replica 
         {:job-scheduler (:onyx.peer/job-scheduler peer-config)
          :messaging (select-keys peer-config [:onyx.messaging/impl])}))

(defn playback-log [peer-config peer-log-reads]
  (reduce #(onyx.extensions/apply-log-entry %2 %1)
          (base-replica peer-config)
          peer-log-reads))

(defn pulses [conn peer-config]
  (onyx.log.curator/children conn (onyx.log.zookeeper/pulse-path (:onyx/tenancy-id peer-config))))

(defn history->read-ledgers [history task-name]
  (filter (fn [action]
            (and (= (:f action) :read-ledgers)
                 (= (:task action) task-name)
                 (= (:type action) :ok)))
          history))

(defn ledger-reads->job+reads [ledger-reads]
  (->> ledger-reads
       :value
       (mapcat (fn [[job-num reads]]
                 (map (fn [r] 
                        {:job-num job-num
                         :read-results (:results r)}) 
                      reads)))))

(defn reads-correct-jobs? [job+reads]
  (empty? 
    (remove (fn [{:keys [read-results job-num]}]
              (or (empty? read-results) 
                  (= #{job-num} (set (map :job-num read-results)))))
            job+reads)))

(defn job-exception [log-conn final-replica]
  (if-let [killed-job (first (:killed-jobs final-replica))] 
    (onyx.extensions/read-chunk log-conn :exception killed-job)))

(defn history->successful-writes [history]
  (filter (fn [action]
            (and (= (:f action) :add)
                 (= (:type action) :ok)))
          history))

(defn simple-job-invariants [log-conn history final-replica n-jobs]
  (let [exception (job-exception log-conn final-replica)
        ledger-reads (first (history->read-ledgers history :persist))
        ledger-read-results (ledger-reads->job+reads ledger-reads)
        ;; Add a check here that there are no overlaps in the ledgers read by the jobs
        correct-jobs? (reads-correct-jobs? ledger-read-results)
        successfully-added (history->successful-writes history)
        added-values (set (map :value successfully-added))
        read-values (->> ledger-read-results
                         (mapcat :read-results)
                         (map :value)
                         set)
        diff-written-read (clojure.set/difference added-values read-values)
        all-written-read? (empty? diff-written-read)
        unacked-writes-read (clojure.set/difference read-values added-values)]
    {:information {:read-values read-values
                   :diff-written-read diff-written-read
                   :unacknowledged-writes-read unacked-writes-read
                   :job-exception-message (some-> exception (.getMessage))
                   :job-exception-trace (if exception (with-out-str (clojure.stacktrace/print-stack-trace exception)))}
     :invariants {:job-completed? (nil? exception)
                  :reads-correct-jobs? correct-jobs?
                  :all-written-read? all-written-read?}}))

(defn window-state-job-invariants [log-conn history final-replica n-jobs]
  (let [exception (job-exception log-conn final-replica)
        ledger-reads (first (history->read-ledgers history :persist))
        trigger-ledger-reads (first (history->read-ledgers history :identity-log))
        final-window-state-write (->> (get (:value trigger-ledger-reads) 0) ;; only one job in this test
                                      (map (comp first :results)) ;; only one write per ledger
                                      (sort-by first) ;; Grab last written trigger call i.e. highest timestamp
                                      last
                                      last)
        window-state-filtered? (= (sort-by :id final-window-state-write) 
                                  (sort-by :id (set final-window-state-write)))
        ledger-read-results (ledger-reads->job+reads ledger-reads)
        ;; Add a check here that there are no overlaps in the ledgers read by the jobs
        correct-jobs? (reads-correct-jobs? ledger-read-results)
        successfully-added (history->successful-writes history)
        added-values (set (map :value successfully-added))
        read-values (->> ledger-read-results
                         (mapcat :read-results)
                         (map #(dissoc % :job-num))
                         set)
        diff-added-read (clojure.set/difference added-values read-values)
        all-added-read? (empty? diff-added-read)
        written-not-triggered (clojure.set/difference added-values 
                                                      (->> final-window-state-write
                                                           (map #(dissoc % :job-num))
                                                           set))
        all-added-triggered? (empty? written-not-triggered)
        unacked-writes-read (clojure.set/difference read-values added-values)]
    {:information {:read-values read-values
                   :diff-added-read diff-added-read
                   :unacknowledged-writes-read unacked-writes-read
                   :added-not-triggered written-not-triggered
                   :job-exception-message (some-> exception (.getMessage))
                   :job-exception-trace (if exception 
                                          (with-out-str (clojure.stacktrace/print-stack-trace exception)))}
     :invariants {:window-state-filtered? window-state-filtered?
                  :jobs-completed? (and (nil? exception) 
                                        (= (count (:completed-jobs final-replica))
                                           n-jobs))
                  :reads-correct-jobs? correct-jobs?
                  :all-added-triggered? all-added-triggered?
                  :all-added-read? all-added-read?}}))

; (defn history->job-name [history]
;   (:job-type (first (filter (fn [action]
;                               (and (= (:f action) :submit-job)
;                                    (= (:type action) :ok)))
;                             history))))

;; TODO, check whether the jobs were even submitted, if not, nothing should be read back 
;; important for short running tests
(defrecord Checker [test-setup peer-config n-peers n-jobs]
  checker/Checker
  (check [checker test model history]
    (let [;;;;;;;;;
          ;; Cluster invariants
          peer-log-reads (:value (first (filter (fn [action]
                                                  (and (= (:f action) :read-peer-log)
                                                       (= (:type action) :ok)))
                                                history)))
          final-replica (playback-log peer-config peer-log-reads)
          peer-client (component/start (system/onyx-client peer-config))
          log-conn (:log peer-client)
          all-peers-up? (= (count (:peers final-replica))
                           (* 5 n-peers))
          pulse-peers (pulses (:conn log-conn) peer-config)

          peers-match-pulses? (= (sort (map str (:peers final-replica)))
                                 (sort pulse-peers))

          accepting-empty? (empty? (:accepted final-replica))
          prepared-empty? (empty? (:prepared final-replica))

          invariants-cluster {:invariants {:all-peers-up? all-peers-up? 
                                           :read-whole-log-back? (< (count peer-log-reads) 50000)
                                           :peers-match-pulses? peers-match-pulses? 
                                           :accepting-empty? accepting-empty? 
                                           :prepared-empty? prepared-empty?}
                              :information {:pulse-peers pulse-peers
                                            :peer-log peer-log-reads
                                            :final-replica final-replica}}
          ;; Job invariants
          job-invariants-fn (case (:job-type test-setup)
                              :simple-job simple-job-invariants 
                              :window-state-job window-state-job-invariants
                              :no-job (constantly {:information {} :invariants {}}))
          invariants-job (job-invariants-fn log-conn history final-replica n-jobs)
          invariants [invariants-job invariants-cluster]]
      (component/stop peer-client)
      (doto {:valid? (empty? (filter false? (mapcat (comp vals :invariants) invariants)))
             :job-invariants invariants-job
             :cluster-invariants invariants-cluster}
        info))))
