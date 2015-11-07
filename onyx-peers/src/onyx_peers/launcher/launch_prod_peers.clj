(ns onyx-peers.launcher.launch-prod-peers
  (:gen-class)
  (:require [clojure.core.async :refer [chan <!!]]
            [clojure.java.io :refer [resource]]
            ;[onyx-peers.lifecycles.lifecycle]
            ;[onyx-peers.functions.functions]
            [onyx.plugin.core-async]
            [onyx.api]))

(defn -main [onyx-id n & args]
  (let [n-peers (Integer/parseInt n)
        env-config (assoc (-> "prod-env-config.edn"
                              resource slurp read-string) :onyx/id onyx-id)
        env (onyx.api/start-env env-config)
        peer-config (assoc (-> "prod-peer-config.edn"
                               resource slurp read-string) :onyx/id onyx-id)
        peer-group (onyx.api/start-peer-group peer-config)
        peers (onyx.api/start-peers n-peers peer-group)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                       (fn []
                         (doseq [v-peer peers]
                           (onyx.api/shutdown-peer v-peer))
                         (onyx.api/shutdown-peer-group peer-group)
                         (shutdown-agents))))
    (println "Started peers. Blocking forever.")
    ;; Block forever.
    (<!! (chan))))
