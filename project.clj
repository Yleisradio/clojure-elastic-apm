(defproject clojure-elastic-apm "0.1.0"
  :description "Clojure wrapper for Elastic APM Java Agent"
  :url "https://github.com/Yleisradio/clojure-elastic-apm"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :profiles {:provided {:dependencies [[co.elastic.apm/apm-agent-api "1.3.0"]]}})
