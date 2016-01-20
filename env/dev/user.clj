(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh set-refresh-dirs]]
            [com.stuartsierra.component :as component]))

(set-refresh-dirs "src" "test")
