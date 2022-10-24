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

(deftest reitit-ring-tx-name-test
  (let [transaction-id (atom nil)
        request {:request-method :get, :uri "/foo/123"
                 :reitit.core/match {:template "/foo/{id}"
                                     :path     "/foo/123"}}
        response {:status 200 :body "Ok."}
        handler (fn [request]
                  (reset! transaction-id (.getId (:clojure-elastic-apm/transaction request)))
                  response)
        wrapped-handler (apm-ring/wrap-apm-transaction handler)]
    (is (= (wrapped-handler request) response))
    (let [tx-details (es-find-first-document (str "(processor.event:transaction%20AND%20transaction.id:" @transaction-id ")"))]
      (is (= "GET /foo/{id}" (get-in tx-details [:transaction :name]))))))

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
