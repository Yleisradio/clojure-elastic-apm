(ns clojure-elastic-apm.ring
  (:require [clojure-elastic-apm.core :refer [with-apm-transaction
                                              type-request]]))

(defn match-uri [pattern uri]
  (let [pattern-segs (clojure.string/split pattern #"/")
        uri-segs (clojure.string/split uri #"/")
        matcher (fn [p u]
                  (cond
                    (= p "*") u
                    (= p "_") "_"
                    (= p u) u
                    :else false))]
    (if (> (count pattern-segs)
           (count uri-segs))
      false
      (let [matches (map matcher pattern-segs uri-segs)
            matched? (reduce #(and %1 %2) matches)]
        (if matched?
          (clojure.string/join "/" matches)
          matched?)))))

(defn request->tx-name [{:keys [request-method uri]}]
  (str (.toUpperCase (name request-method)) " " uri))

(defn wrap-apm-transaction
  ([handler]
   (fn [{:keys [headers] :as request}]
     (let [tx-name (request->tx-name request)
           traceparent (get-in headers ["traceparent"])]
       (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
         (let [{:keys [status] :as response} (handler (assoc request :clojure-elastic-apm/transaction tx))]
           (when status
             (.setResult tx (str "HTTP " status)))
           response)))))
  ([handler patterns]
   (fn [{:keys [request-method uri headers] :as request}]
     (let [matched (->> patterns
                        (map #(match-uri % uri))
                        (drop-while false?)
                        (first))
           tx-name (str (.toUpperCase (name request-method)) " " matched)
           traceparent (get-in headers ["traceparent"])]
       (if matched
         (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
           (let [{:keys [status] :as response} (handler (assoc request :clojure-elastic-apm/transaction tx))]
             (when status
               (.setResult tx (str "HTTP " status)))
             response))
         (handler request))))))
