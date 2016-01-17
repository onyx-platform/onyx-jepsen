(ns onyx-peers.functions.functions
  (:require [taoensso.timbre :refer [info error debug fatal]]))

(defn add-job-num [job-num segment]
  (assoc segment :job-num job-num))

(defn restartable? [e]
  true)
