(ns onyx-peers.functions.functions
  (:require [onyx.compression.nippy :as nippy]
            [taoensso.timbre :refer [info error debug fatal]]))

(defn annotate-job-num [job-num segment]
  (assoc segment :job-num job-num))

(defn unwrap [segment]
  (:value segment))

(defn update-state-log [{:keys [bookkeeper/ledger-handle] :as event} 
                        window 
                        trigger 
                        opts
                        state]
  (when (= :task-lifecycle-stopped (:context opts)) 
    (let [extent-value (first (vals state))
          value [(java.util.Date.) extent-value]
          compressed (nippy/zookeeper-compress value)
          n-bytes (count compressed)] 
      (info "task complete:" (.getId ledger-handle) n-bytes "bytes" [(java.util.Date.) (map :id extent-value)])
      (.addEntry ledger-handle compressed)
      (info "task complete successfully wrote" n-bytes "bytes"))))
