(defproject clojure-elastic-apm "0.2.0"
  :description "Clojure wrapper for Elastic APM Java Agent"
  :url "https://github.com/Yleisradio/clojure-elastic-apm"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :profiles {:dev {:jvm-opts ["-javaagent:lib/elastic-apm-agent-1.10.0.jar"
                              "-Delastic.apm.service_name=test-service"
                              "-Delastic.apm.application_packages=clojure-elastic-apm"
                              "-Delastic.apm.server_urls=http://localhost:8200"
                              "-Delastic.apm.metrics_interval=1s"]
                   :dependencies [[clj-http "3.9.1"]
                                  [cheshire "5.8.1"]]}
             :provided {:dependencies [[co.elastic.apm/apm-agent-api "1.10.0"]]}})
