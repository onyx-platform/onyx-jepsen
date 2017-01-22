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
                        state-event
                        extent-state]
  (when (= :job-completed (:event-type state-event)) 
    (let [value [(java.util.Date.) extent-state]
          compressed (nippy/zookeeper-compress value)
          n-bytes (count compressed)] 
      (when (> n-bytes 1000000)
        (throw (Exception. "Payload too big for BookKeeper")))
      (info "task complete:" (.getId ledger-handle) n-bytes "bytes" [(java.util.Date.) (map :id extent-state)])
      (.addEntry ledger-handle compressed)
      (info "task complete successfully wrote" n-bytes "bytes"))))
