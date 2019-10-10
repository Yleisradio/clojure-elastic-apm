(ns clojure-elastic-apm.ring
  (:require [clojure-elastic-apm.core :refer [with-apm-transaction
                                              type-request]]))

(defn wrap-apm-transaction [handler]
  (fn [{:keys [request-method uri] :as request}]
    (let [tx-name (str (.toUpperCase (name request-method)) " " uri)]
      (with-apm-transaction [tx {:name tx-name :type type-request}]
        (let [{:keys [status] :as response} (handler (assoc request :clojure-elastic-apm/transaction tx))]
          (when status
            (.setResult tx (str "HTTP " status)))
          response)))))

(defn with-apm-tx-tags [handler]
  (fn [{:keys [request-method uri] :as request}]
    (when-let [tx (:clojure-elastic-apm/transaction request)]
      (let [method-name (upper-case (name request-method))
            transaction-name (str method-name " " uri)]
        (apm/add-tag tx "transaction" transaction-name)))
    (handler request)))