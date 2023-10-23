(ns clojure-elastic-apm.ring
  (:require
   [clojure-elastic-apm.core :as apm :refer [type-request with-apm-transaction]]))

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

(defn match-patterns [patterns uri]
  (if patterns
    (->> patterns
         (map #(match-uri % uri))
         (drop-while false?)
         (first))
    uri))

(defn wrap-apm-transaction
  ([handler]
   (wrap-apm-transaction handler nil))
  ([handler patterns]
   (fn
     ([{:keys [request-method uri headers] :as request}]
      (let [matched (match-patterns patterns uri)
            tx-name (str (.toUpperCase (name request-method)) " " matched)
            traceparent (get-in headers ["traceparent"])]
        (if matched
          (with-apm-transaction [tx {:name tx-name :type type-request :traceparent traceparent}]
            (let [{:keys [status] :as response} (handler (assoc request :clojure-elastic-apm/transaction tx))]
              (when status
                (.setResult tx (str "HTTP " status)))
              response))
          (handler request))))
     ([{:keys [request-method uri headers] :as request} respond raise]
      (let [matched (match-patterns patterns uri)
            tx-name (str (.toUpperCase (name request-method)) " " matched)
            traceparent (get-in headers ["traceparent"])]
        (if matched
          (let [tx (apm/start-transaction {:name tx-name :type type-request :traceparent traceparent})
                req (assoc request :clojure-elastic-apm/transaction tx)]
            (with-open [_ (apm/activate tx)]
              (handler req
                       (fn [{:keys [status] :as response}]
                         (when status
                           (.setResult tx (str "HTTP " status)))
                         (apm/end tx)
                         (respond response))
                       (fn [err]
                         (when (instance? Exception err)
                           (apm/capture-exception tx err))
                         (apm/end tx)
                         (raise err)))))
          (handler request respond raise)))))))
