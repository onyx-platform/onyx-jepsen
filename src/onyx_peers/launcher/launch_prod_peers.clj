(ns onyx-peers.launcher.launch-prod-peers
  (:gen-class)
  (:require [clojure.core.async :refer [chan <!!]]
            [clojure.java.io :refer [resource]]
            [onyx.bookkeeper.bookkeeper :as bkserver]
            [onyx-peers.functions.functions]
            [onyx-peers.lifecycles.restart-lifecycle]
            [taoensso.timbre :refer [info error debug fatal]]
            [com.stuartsierra.component :as component]
            [onyx.compression.nippy :as nippy]
            [unilog.config :as unilog]
            [onyx.plugin.bookkeeper]
            [onyx.http-query]
            [schema.core :as s]
            [onyx.api]))

(defn -main [n bind-addr & args]
  ;; Turn on schema validations for jepsen
  ;(s/set-fn-validation! true)
  (let [n-peers (Integer/parseInt n)
        env-config (-> "prod-env-config.edn" resource slurp read-string)
        env (onyx.api/start-env env-config)
        bookie-monitor (component/start (bkserver/new-bookie-monitor env-config (:log env) (:onyx.bookkeeper/port env-config)))
        peer-config (-> "prod-peer-config.edn" resource slurp read-string
                        (assoc :onyx.messaging/bind-addr bind-addr)
                        (assoc :onyx.query.server/ip bind-addr))
        peer-group (onyx.api/start-peer-group peer-config)
        peers (onyx.api/start-peers n-peers peer-group)]

    (unilog/start-logging!
     {:level   "info"
      :console   false
      :appenders [{:appender :console
                   :pattern "%p\t[%t] %c: %m%n"}]})

    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                       (fn []
                         (doseq [v-peer peers]
                           (onyx.api/shutdown-peer v-peer))
                         (onyx.api/shutdown-peer-group peer-group)
                         (component/stop bookie-monitor)
                         (shutdown-agents))))
    (println "Started peers. Blocking forever.")
    ;; Block forever.
    (<!! (chan))))
