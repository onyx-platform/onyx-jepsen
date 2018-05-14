(defproject onyx-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen testing Onyx"
  :url "github.com/onyx-platform/onyx-jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ;[org.slf4j/slf4j-nop "1.7.21"]
                 [fipp "0.6.4"]
                 [org.onyxplatform/onyx "0.13.0-20180514.064012-3"]
                 [org.onyxplatform/onyx-peer-http-query "0.13.0.0-alpha1"]
                 [org.onyxplatform/onyx-metrics "0.13.0.0-alpha1" :exclusions [org.onyxplatform/onyx]]
                 [org.onyxplatform/onyx-bookkeeper "0.13.0.0-alpha1" :exclusions [org.onyxplatform/onyx]]
                 [jepsen "0.1.4" :exclusions [ring]]]
  :test-selectors {:jepsen :jepsen
                   :test-jepsen-tests :test-jepsen-tests
                   :all (constantly true)}
  :jvm-opts ^:replace ["-server" "-Xmx12g" "-XX:+UseG1GC"]
  :profiles {:uberjar {:aot [onyx-peers.launcher.launch-prod-peers]}
             :dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [incanter/incanter-core "1.9.1"]
                                  [incanter/incanter-charts "1.9.1"]
                                  [incanter/incanter-io "1.9.1"]]
                   :source-paths ["env/dev" "src"]}})
