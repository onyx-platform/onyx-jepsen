(defproject onyx-peers "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.onyxplatform/onyx "0.8.1-20151121.090130-7" :exclusions [org.slf4j/slf4j-nop]]]
  :jvm-opts ^:replace ["-server"]
  :profiles {:uberjar {:aot [onyx-peers.launcher.aeron-media-driver
                             onyx-peers.launcher.launch-prod-peers]}
             :dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["env/dev" "src"]}})
