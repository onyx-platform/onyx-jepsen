(ns onyx-peers.functions.functions
  (:require [taoensso.timbre :refer [info error debug fatal]]))

(defn identity-log [segment]
  (info "Read segment" segment)
  (assoc segment :stage 2))

(defn restartable? [e]
  true)
