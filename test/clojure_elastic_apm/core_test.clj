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
      (Thread/sleep 100))
    (is (= "" (.getId (apm/current-apm-transaction))) "tx did not deactive after with-apm-transaction block ended")
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= "TestTransaction" (get-in tx-details [:transaction :name])))
      (is (= "1" (get-in tx-details [:labels :t1])))
      (is (= 2 (get-in tx-details [:labels :t2])))
      (is (true? (get-in tx-details [:labels :t3])))
      (is (= "Label 4" (get-in tx-details [:labels :t4]))))))

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
      (apm/with-apm-span [span {:name "TestSpan" :tags {"t1" "1"}}]
        (reset! span-id (.getId span))
        (Thread/sleep 100)
        (apm/set-label span "t1" "1")
        (is (not= (.getId tx) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")
        (is (= (.getId span) (.getId (apm/current-apm-span))) "span did not activate in the with-apm-span block")))
    (is (= "" (.getId (apm/current-apm-span))) "span did not deactivate after the with-apm-span block")
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))
          span-details (es-find-first-document (str "(processor.event:span%20AND%20span.id:" @span-id ")"))]
      (is (= "TestSpan" (get-in span-details [:span :name])))
      (is (= "1" (get-in span-details [:labels :t1])))
      (is (= @transaction-id (get-in span-details [:transaction :id])))
      (is (= 1 (get-in tx-details [:transaction :span_count :started]))))))

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
