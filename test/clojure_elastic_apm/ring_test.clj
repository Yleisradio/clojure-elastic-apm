(ns clojure-elastic-apm.ring-test
  (:require [clojure-elastic-apm.core :as apm]
            [clojure-elastic-apm.ring :as apm-ring]
            [clojure-elastic-apm.core-test :refer [es-find-first-document]]
            [clojure.test :refer :all]))

(deftest wrap-apm-transaction-test
  (let [transaction-id (atom nil)
        request {:request-method :get, :uri "/foo/bar"}
        response {:status 200 :body "Ok."}
        handler (fn [request]
                  (is (not= "" (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                  (is (= (.getId (:clojure-elastic-apm/transaction request)) (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                  (reset! transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                  response)
        wrapped-handler (apm-ring/wrap-apm-transaction handler)]
    (is (= (wrapped-handler request) response))
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= apm/type-request (get-in tx-details [:transaction :type])))
      (is (= "HTTP 200" (get-in tx-details [:transaction :result])))
      (is (= "GET /foo/bar" (get-in tx-details [:transaction :name]))))))
