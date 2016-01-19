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
                        {:keys [window-id upper-bound lower-bound]} 
                        state]
  (.addEntry ledger-handle (nippy/zookeeper-compress [lower-bound upper-bound state])))
