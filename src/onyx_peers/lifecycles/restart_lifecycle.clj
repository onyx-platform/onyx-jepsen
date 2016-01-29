(ns onyx-peers.lifecycles.restart-lifecycle
  (:require [taoensso.timbre :refer [info error debug fatal]]))

;; TODO, tighten up to test whether onyx-bookkeeper handles all its exceptions properly
(defn handle-exception [event lifecycle lf-kw exception]
  :restart)

(def restart-calls
  {:lifecycle/handle-exception handle-exception})
