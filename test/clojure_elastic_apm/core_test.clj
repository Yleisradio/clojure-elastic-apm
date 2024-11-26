(ns clojure-elastic-apm.core-test
  (:require [clojure-elastic-apm.core :as apm]
            [clojure.test :refer :all]
            [clj-http.client :as http]))

(defn es-find-first-document [query]
  (loop [attempts 1]
    (let [{:keys [body]} (http/get (str "http://localhost:9200/_search?q=" query) {:as :json})]
      (if-let [source (get-in body [:hits :hits 0 :_source])]
        source
        (if (<= attempts 60)
          (do
            (Thread/sleep 1000)
            (recur (inc attempts)))
          (do
            (println (str "http://localhost:9200/_search?q=" query))
            (throw (RuntimeException. "Could not find transaction details from ElasticSearch before timeout."))))))))

(deftest with-apm-transaction-test
  (let [transaction-id (atom nil)]
    (apm/with-apm-transaction [tx {:name "TestTransaction" :tags {"t1" "1", :t2 2}}]
      (reset! transaction-id (.getId tx))
      (is (not= "" (.getId tx)) "tx did not activate in the with-apm-transactionblock")
      (is (= (.getId tx) (.getId (apm/current-apm-transaction))) "tx did not activate in the with-apm-transaction block")
      (apm/set-label tx "t3" true)
      (apm/set-label tx "t4" (reify Object (toString [_] "Label 4")))
      (apm/set-result tx "test-result")
      (apm/set-outcome-success tx)
      (Thread/sleep 100))
    (is (= "" (.getId (apm/current-apm-transaction))) "tx did not deactive after with-apm-transaction block ended")
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= "TestTransaction" (get-in tx-details [:transaction :name])))
      (is (= "1" (get-in tx-details [:labels :t1])))
      (is (= "2" (get-in tx-details [:labels :t2])))
      (is (= "true" (get-in tx-details [:labels :t3])))
      (is (= "Label 4" (get-in tx-details [:labels :t4])))
      (is (= "test-result" (get-in tx-details [:transaction :result])))
      (is (= "success" (get-in tx-details [:event :outcome]))))))

(deftest with-apm-transaction-no-activation-test
  (apm/with-apm-transaction [tx {:name "TestTransaction" :activate? false}]
    (is (= "" (.getId (apm/current-apm-transaction))) "tx should not have been activated")
    (is (not= "" (.getId tx)))))

(deftest with-apm-span-test
  (let [transaction-id (atom nil)
        span-id (atom nil)]
    (apm/with-apm-transaction [tx]
      (reset! transaction-id (.getId tx))
      (apm/set-name tx "TestWithSpans")
      (is (= (.getId tx) (.getId (apm/current-apm-span))))
      (apm/with-apm-span [span {:name "TestSpan" :tags {"t1" "1"} :type "type1"}]
        (reset! span-id (.getId span))
        (Thread/sleep 100)
        (apm/set-label span "t1" "1")
        (apm/set-outcome-failure span)
        (is (not= (.getId tx) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")
        (is (= (.getId span) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")))
    (is (= "" (.getId (apm/current-apm-span))) "span did not deactivate after the with-apm-span block")
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))
          span-details (es-find-first-document (str "(processor.event:span%20AND%20span.id:" @span-id ")"))]
      (is (= "TestSpan" (get-in span-details [:span :name])))
      (is (= "1" (get-in span-details [:labels :t1])))
      (is (= "type1" (get-in span-details [:span :type])))
      (is (= @transaction-id (get-in span-details [:transaction :id])))
      (is (= 1 (get-in tx-details [:transaction :span_count :started])))
      (is (= "failure" (get-in span-details [:event :outcome]))))))

(deftest with-apm-exit-span-test
  (let [span-id (atom nil)]
    (apm/with-apm-transaction [tx]
      (apm/set-name tx "TestWithSpans")
      (is (= (.getId tx) (.getId (apm/current-apm-span))))
      (apm/with-apm-exit-span [span {:name "TestExitSpan" :type "type1" :subtype "serviceX" :action "action1"}]
        (reset! span-id (.getId span))
        (Thread/sleep 100)
        (apm/set-outcome-failure span)
        (is (not= (.getId tx) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")
        (is (= (.getId span) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")))
    (is (= "" (.getId (apm/current-apm-span))) "span did not deactivate after the with-apm-span block")
    (let [span-details (es-find-first-document (str "(processor.event:span%20AND%20span.id:" @span-id ")"))]
      (is (= "TestExitSpan" (get-in span-details [:span :name])))
      (is (= "type1" (get-in span-details [:span :type])))
      (is (= "serviceX" (get-in span-details [:span :subtype])))
      (is (= "action1" (get-in span-details [:span :action]))))))

(deftest with-apm-exit-span-defaults-test
  (let [span-id (atom nil)]
    (apm/with-apm-transaction [tx]
      (apm/set-name tx "TestWithSpans")
      (is (= (.getId tx) (.getId (apm/current-apm-span))))
      (apm/with-apm-exit-span [span {:name "TestExitSpan"}]
        (reset! span-id (.getId span))
        (Thread/sleep 100)
        (apm/set-outcome-failure span)
        (is (not= (.getId tx) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")
        (is (= (.getId span) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")))
    (is (= "" (.getId (apm/current-apm-span))) "span did not deactivate after the with-apm-span block")
    (let [span-details (es-find-first-document (str "(processor.event:span%20AND%20span.id:" @span-id ")"))]
      (is (= "TestExitSpan" (get-in span-details [:span :name])))
      (is (= "ext" (get-in span-details [:span :type])))
      (is (= "undefined subtype" (get-in span-details [:span :subtype])))
      (is (nil? (get-in span-details [:span :action]))))))

(deftest with-apm-span-no-activation-test
  (apm/with-apm-transaction [tx]
    (apm/with-apm-span [span {:activate? false}]
      (is (= (.getId (apm/current-apm-transaction)) (.getId (apm/current-apm-span))))
      (is (not= "" (.getId span))))))

(deftest with-apm-transaction-error-capturing-test
  (let [transaction-id (atom nil)]
    (is (thrown? RuntimeException (apm/with-apm-transaction [tx]
                                    (reset! transaction-id (.getId tx))
                                    (throw (RuntimeException. "Simulated error")))))
    (let [error-details (es-find-first-document (str "(processor.event:error%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= "java.lang.RuntimeException" (get-in error-details [:error :exception 0 :type])))
      (is (= "Simulated error" (get-in error-details [:error :exception 0 :message]))))))

(apm/defnspan f1
  [_]
  (apm/current-apm-span))

(deftest defnspan-default-test
  (apm/with-apm-transaction [tx]
    (let [span (f1 :a)
          doc (es-find-first-document (str "(processor.event:span%20AND%20span.id:" (.getId span) ")"))]
      (is (= {:span {:name "clojure-elastic-apm.core-test/f1" :type "custom"}}
             (-> doc
                 (select-keys [:span])
                 (update :span (fn [span] (select-keys span [:name :type])))))))))

(apm/defnspan f2
  {:apm/name "Custom name"
   :apm/type "db"
   :apm/tags {"t1" "1"}}
  [_ _]
  (apm/current-apm-span))

(deftest defnspan-meta-test
  (apm/with-apm-transaction [tx]
    (let [span (f2 :a :b)
          doc (es-find-first-document (str "(processor.event:span%20AND%20span.id:" (.getId span) ")"))]
      (is (= {:span {:name "Custom name" :type "db"}
              :labels {:t1 "1"}}
             (-> doc
                 (select-keys [:span :labels])
                 (update :span (fn [span] (select-keys span [:name :type])))))))))

(apm/defnspan f3
  [_ _ _ _ _]
  (apm/current-apm-span))

(deftest defnspan-many-args-test
  (apm/with-apm-transaction [tx]
    (let [span (f3 :a :b :c :d :e)
          doc (es-find-first-document (str "(processor.event:span%20AND%20span.id:" (.getId span) ")"))]
      (is (= {:span {:name "clojure-elastic-apm.core-test/f3" :type "custom"}}
             (-> doc
                 (select-keys [:span])
                 (update :span (fn [span] (select-keys span [:name :type])))))))))

(defn trace-context
  "Given an APM span, return the trace context[1] HTTP headers of the span.

  [1]: https://www.w3.org/TR/trace-context/"
  [span]
  (let [headers (transient {})]
    (.injectTraceHeaders span
      (reify co.elastic.apm.api.HeaderInjector
        (addHeader [_ name value]
          (assoc! headers name value))))
    (persistent! headers)))

(def ^:private traceparent-regex
  "https://www.w3.org/TR/trace-context/#traceparent-header"
  #"^([0-9a-fA-F]{2})-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$")

(def ^:private tracestate-regex
  "https://www.w3.org/TR/trace-context/#tracestate-header"
  #"^[!-~]+(,[!-~]+=[!-~]+)*$")

(defn parse-traceparent-header
  "Given a traceparent header, parse the header value into its constituents."
  [traceparent]
  (zipmap [:version :trace-id :parent-id :trace-flags]
    (rest (re-matches traceparent-regex traceparent))))

(defn fields-except
  "Given a traceparent header, return a map of its constituents, except fields."
  [traceparent & fields]
  (-> (parse-traceparent-header traceparent)
      (apply dissoc fields)))

(deftest trace-context-test
  (testing "no opts"
    (let [tx (apm/start-transaction)
          {:strs [traceparent tracestate]} (trace-context tx)]
      ;; APM adds new trace context headers.
      (is (re-matches traceparent-regex traceparent))
      (is (re-matches tracestate-regex tracestate))))

  (testing ":headers -- empty & nil"
    (let [tx (apm/start-transaction {:headers {}})
          {:strs [traceparent tracestate]} (trace-context tx)]
      (is (re-matches traceparent-regex traceparent))
      (is (re-matches tracestate-regex tracestate)))

    (let [tx (apm/start-transaction {:headers nil})
          {:strs [traceparent tracestate]} (trace-context tx)]
      (is (re-matches traceparent-regex traceparent))
      (is (re-matches tracestate-regex tracestate))))

  (testing ":headers -- traceparent only"
    (let [inbound-traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
          tx (apm/start-transaction {:headers {"traceparent" inbound-traceparent}})
          {:strs [traceparent]} (trace-context tx)]
      (is (= (fields-except inbound-traceparent :parent-id)
             (fields-except traceparent :parent-id)))))

  (testing ":headers -- traceparent & tracestate"
    (let [inbound-traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
          inbound-tracestate "es=s:0.25"
          tx (apm/start-transaction {:headers {"traceparent" inbound-traceparent
                                               "tracestate" inbound-tracestate}})
          {:strs [traceparent tracestate]} (trace-context tx)]
      (is (= (fields-except inbound-traceparent :parent-id)
             (fields-except traceparent :parent-id)))
      (is (= inbound-tracestate tracestate))))

  (testing ":headers -- multi-value tracestate"
    (let [inbound-traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
          inbound-tracestate "es=s:0.5,rojo=00f067aa0ba902b7"
          tx (apm/start-transaction {:headers {"traceparent" inbound-traceparent
                                               "tracestate" inbound-tracestate}})
          {:strs [tracestate]} (trace-context tx)]
      (is (= inbound-tracestate tracestate))))

  (testing ":traceparent (backwards compatibility only, do not use)"
    (let [inbound-traceparent "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
          tx (apm/start-transaction {:traceparent inbound-traceparent})
          {:strs [traceparent tracestate]} (trace-context tx)]
      (is (= (fields-except inbound-traceparent :parent-id)
             (fields-except traceparent :parent-id)))

      ;; Using the :traceparent option incorrectly sets the value of every
      ;; trace context header -- including tracestate -- to the value of the
      ;; traceparent header. Do not use it.
      (is (= inbound-traceparent tracestate)))))
