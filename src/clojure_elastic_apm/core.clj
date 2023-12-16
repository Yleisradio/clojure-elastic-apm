(ns clojure-elastic-apm.core
  (:import [co.elastic.apm.api ElasticApm Transaction Span HeaderExtractor Outcome]))

(set! *warn-on-reflection* true)

(defn current-apm-transaction []
  (ElasticApm/currentTransaction))

(defn current-apm-span []
  (ElasticApm/currentSpan))

(defn ^:deprecated add-tag [^Span span-or-tx k v]
  (.setLabel span-or-tx (name k) (str v)))

(defn set-label [^Span span-or-tx k v]
  (cond
    (number? v) (.setLabel span-or-tx (name k) ^Number v)
    (boolean? v) (.setLabel span-or-tx (name k) ^boolean v)
    :else (.setLabel span-or-tx (name k) (str v))))

(defn set-name [^Span span-or-tx name]
  (.setName span-or-tx name))

(defn set-result [^Transaction tx ^String result]
  (.setResult tx result))

(def outcome-success Outcome/SUCCESS)

(def outcome-failure Outcome/FAILURE)

(def outcome-unknown Outcome/UNKNOWN)

(defn set-outcome [^Span span-or-tx ^Outcome outcome]
  (.setOutcome span-or-tx outcome))

(defn set-outcome-success [^Span span-or-tx]
  (set-outcome span-or-tx outcome-success))

(defn set-outcome-failure [^Span span-or-tx]
  (set-outcome span-or-tx outcome-failure))

(defn set-outcome-unknown [^Span span-or-tx]
  (set-outcome span-or-tx outcome-unknown))

(defn trace-extractor ^HeaderExtractor [traceparent]
  (reify HeaderExtractor
    (getFirstHeader [_ _] traceparent)))

(def type-request Transaction/TYPE_REQUEST)

(defn start-transaction
  ([]
   (start-transaction {}))
  (^Transaction
   [{tx-name :name
     ^String tx-type :type
     tags :tags
     labels :labels
     traceparent :traceparent}]
   (let [tx (if traceparent
              (ElasticApm/startTransactionWithRemoteParent (trace-extractor traceparent))
              (ElasticApm/startTransaction))]
     (when tx-name
       (set-name tx tx-name))
     (when tx-type
       (.setType tx tx-type))
     (doseq [[k v] labels]
       (set-label tx k v))
     (doseq [[k v] tags]
       (set-label tx k v))
     tx)))

(defn create-span
  ([]
   (create-span {}))
  ([{parent :parent
     span-name :name
     labels :labels
     tags :tags}]
   (let [parent-span (or parent (current-apm-span))
         child-span (.startSpan ^Span parent-span)]
     (when span-name
       (set-name child-span span-name))
     (doseq [[k v] labels]
       (set-label child-span k v))
     (doseq [[k v] tags]
       (set-label child-span k v))
     child-span)))

(defn create-exit-span [{parent :parent
                         span-name :name
                         labels :labels
                         type :type
                         subtype :subtype
                         action :action}]
  (let [parent-span (or parent (current-apm-span))
        child-span (.startExitSpan ^Span parent-span
                                   (or type "ext")
                                   (or subtype "undefined subtype")
                                   action)]
    (when span-name
      (set-name child-span span-name))
    (doseq [[k v] labels]
      (set-label child-span k v))
    child-span))

(defn end [^Span span-or-tx]
  (.end span-or-tx))

(defn activate ^java.lang.AutoCloseable [^Span span-or-tx]
  (.activate span-or-tx))

(defn capture-exception [^Span span-or-tx ^Exception e]
  (.captureException span-or-tx e))

(defn apm-transaction*
  ([func]
   (apm-transaction* func {}))
  ([func opts]
   (let [activate? (or (nil? (:activate? opts)) (:activate? opts))
         tx (start-transaction opts)]
     (try
       (if activate?
         (with-open [_ (activate tx)]
           (func tx))
         (func tx))
       (catch Exception e
         (capture-exception tx e)
         (throw e))
       (finally
         (end tx))))))

(defn apm-span*
  ([create-span-fn func]
   (apm-span* create-span-fn func {}))
  ([create-span-fn func opts]
   (let [activate? (or (nil? (:activate? opts)) (:activate? opts))
         span (create-span-fn opts)]
     (try
       (if activate?
         (with-open [_ (activate span)]
           (func span))
         (func span))
       (catch Exception e
         (capture-exception span e)
         (throw e))
       (finally
         (end span))))))

(defmacro with-apm-transaction [binding & body]
  `(apm-transaction* (^{:once true} fn* [~(first binding)] ~@body)
                     ~(second binding)))

(defmacro with-apm-span [binding & body]
  `(apm-span* create-span
              (^{:once true} fn* [~(first binding)] ~@body)
              ~(second binding)))

(defmacro with-apm-exit-span [binding & body]
  `(apm-span* create-exit-span
              (^{:once true} fn* [~(first binding)] ~@body)
              ~(second binding)))

(defn catch-error-to-apm [thunk]
  (with-apm-transaction [tx (current-apm-transaction)]
    (try
      (thunk)
      (catch Exception e
        (capture-exception tx e)))))
