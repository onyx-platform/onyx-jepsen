(ns onyx-peers.functions.functions
  (:require [onyx.compression.nippy :as nippy]
            [taoensso.timbre :refer [info error debug fatal]]))

(defn add-job-num [job-num segment]
  (assoc segment :job-num job-num))

(defn unwrap [segment]
  (:value segment))

(defn update-state-log [{:keys [bookkeeper/ledger-handle] :as event} 
                        window 
                        trigger 
                        {:keys [window-id upper-bound lower-bound context]} 
                        state]
  (when (= :task-complete context) 
    (let [compressed (nippy/zookeeper-compress [(java.util.Date.) lower-bound upper-bound state])
          n-bytes (count compressed)] 
      (info "task complete:" (.getId ledger-handle) n-bytes "bytes" [lower-bound upper-bound (map :id state)])
      (.addEntry ledger-handle compressed)
      (info "task complete successfully wrote" n-bytes "bytes"))))
