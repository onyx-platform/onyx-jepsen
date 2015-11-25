(ns onyx-peers.launcher.launch-prod-peers
  (:gen-class)
  (:require [clojure.core.async :refer [chan <!!]]
            [clojure.java.io :refer [resource]]
            ;[onyx-peers.lifecycles.lifecycle]
            ;[onyx-peers.functions.functions]
            [onyx.plugin.core-async]
            [onyx.api]))

(defn -main [n & args]
  (let [n-peers (Integer/parseInt n)
        env-config (-> "prod-env-config.edn" resource slurp read-string)
        env (onyx.api/start-env env-config)
        peer-config (-> "prod-peer-config.edn" resource slurp read-string)
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
