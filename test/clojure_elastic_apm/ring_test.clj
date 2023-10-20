(ns clojure-elastic-apm.ring-test
  (:require [clojure-elastic-apm.core :as apm]
            [clojure-elastic-apm.ring :as apm-ring]
            [clojure-elastic-apm.core-test :refer [es-find-first-document]]
            [clojure.test :refer :all]))

(deftest match-uri-test
  (testing "* matches and returns value"
    (= (apm-ring/match-uri "/*/*" "/v1/foo") "/v1/foo")
    (= (apm-ring/match-uri "/*/*/*" "/v1/foo/bar") "/v1/foo/bar"))
  (testing "_ matches but ignores value"
    (= (apm-ring/match-uri "/*/_" "/v1/foo") "/v1/_")
    (= (apm-ring/match-uri "/_/foo" "/v1/foo") "/_/foo"))
  (testing "exact string matches and returns value"
    (= (apm-ring/match-uri "/v1/*" "/v1/foo") "/v1/foo")
    (= (apm-ring/match-uri "/*/foo" "/v2/foo") "/v2/foo")
    (= (apm-ring/match-uri "/v1/*" "/v2/bar") false))
  (testing "matches are eager, and will match a longer URL"
    (= (apm-ring/match-uri "/*" "/v1/foo/bar") "/v1"))
  (testing "but a shorter URL won't match a longer pattern"
    (= (apm-ring/match-uri "/*/*/*/*" "/v1/foo") false))
  (testing "trailing slashes don't affect matches"
    (= (apm-ring/match-uri "/*/*/" "/v1/foo") "/v1/foo")
    (= (apm-ring/match-uri "/*/*" "/v1/foo/") "/v1/foo")))

(deftest wrap-apm-transaction-test
  (let [transaction-id (atom nil)
        request {:request-method :get, :uri "/foo/bar"}
        response {:status 200 :body "Ok."}
        handler (fn [request]
                  (is (not= "" (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                  (is (= (.getId (:clojure-elastic-apm/transaction request)) (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                  (reset! transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                  response)
        wrapped-handler (apm-ring/wrap-apm-transaction handler ["/*/*"])]
    (is (= (wrapped-handler request) response))
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= apm/type-request (get-in tx-details [:transaction :type])))
      (is (= "HTTP 200" (get-in tx-details [:transaction :result])))
      (is (= "GET /foo/bar" (get-in tx-details [:transaction :name])))
      (is (nil? (:parent tx-details))))))

(deftest wrap-apm-transaction-with-pattern-async-test
  (let [transaction-id (promise)
        request {:request-method :get, :uri "/foo/bar"}
        response {:status 200 :body "Ok."}
        response-promise (promise)
        respond (fn [r]
                  (deliver response-promise r))
        handler (fn [request respond _]
                  (future
                    (with-open [_ (apm/activate (:clojure-elastic-apm/transaction request))]
                      (is (not= "" (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                      (is (= (.getId (:clojure-elastic-apm/transaction request)) (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                      (deliver transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                      (respond response))))
        wrapped-handler (apm-ring/wrap-apm-transaction handler ["/*/*"])]
    (wrapped-handler request respond nil)
    (is (= response (deref response-promise 1000 ::timeout)))
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= apm/type-request (get-in tx-details [:transaction :type])))
      (is (= "HTTP 200" (get-in tx-details [:transaction :result])))
      (is (= "GET /foo/bar" (get-in tx-details [:transaction :name])))
      (is (nil? (:parent tx-details))))))

(deftest wrap-apm-transaction-async-test
  (let [transaction-id (promise)
        response-promise (promise)
        request {:request-method :get, :uri "/foo/bar"}
        response {:status 200 :body "Ok."}
        respond (fn [r]
                  (deliver response-promise r))
        handler (fn [request respond _]
                  (future
                    (with-open [_ (apm/activate (:clojure-elastic-apm/transaction request))]
                      (is (not= "" (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                      (is (= (.getId (:clojure-elastic-apm/transaction request)) (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                      (deliver transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                      (respond response))))
        wrapped-handler (apm-ring/wrap-apm-transaction handler)]
    (wrapped-handler request respond nil)
    (is (= response (deref response-promise 1000 ::timeout)))
    (is (not= "" @transaction-id))
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= apm/type-request (get-in tx-details [:transaction :type])))
      (is (= "HTTP 200" (get-in tx-details [:transaction :result])))
      (is (= "GET /foo/bar" (get-in tx-details [:transaction :name])))
      (is (nil? (:parent tx-details))))))

(deftest wrap-apm-transaction-async-exception-test
  (let [transaction-id (promise)
        request {:request-method :get, :uri "/foo/bar"}
        raise (fn [_])
        handler (fn [request _ raise]
                  (future
                    (with-open [_ (apm/activate (:clojure-elastic-apm/transaction request))]
                      (deliver transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                      (raise (ex-info "Error in handler" {})))))
        wrapped-handler (apm-ring/wrap-apm-transaction handler)]
    (wrapped-handler request nil raise)
    (is (not= "" @transaction-id))
    (let [tx-details (es-find-first-document (str "(processor.event:error%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= apm/type-request (get-in tx-details [:transaction :type])))
      (is (= "clojure.lang.ExceptionInfo" (get-in tx-details [:error :exception 0 :type])))
      (is (= "Error in handler" (get-in tx-details [:error :exception 0 :message]))))))

(deftest wrap-apm-remote-transaction-test
  (let [transaction-id (atom nil)
        parent-id "3574dfeefa12d57e"
        trace-id "0e3e09b4c3ae9837806de794f142cf2f"
        request {:request-method :get, :uri "/foo/bar" :headers {"traceparent" (str "00-" trace-id "-" parent-id "-01")}}
        response {:status 200 :body "Ok."}
        handler (fn [request]
                  (is (not= "" (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                  (is (= (.getId (:clojure-elastic-apm/transaction request)) (.getId (apm/current-apm-transaction))) "transaction should've been activated for the duration of the request")
                  (reset! transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                  response)
        wrapped-handler (apm-ring/wrap-apm-transaction handler ["/*/*"])]
    (is (= (wrapped-handler request) response))
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= apm/type-request (get-in tx-details [:transaction :type])))
      (is (= "HTTP 200" (get-in tx-details [:transaction :result])))
      (is (= "GET /foo/bar" (get-in tx-details [:transaction :name])))
      (is (= parent-id (get-in tx-details [:parent :id])))
      (is (= trace-id (get-in tx-details [:trace :id]))))))
