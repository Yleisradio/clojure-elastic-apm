(ns clojure-elastic-apm.core
  (:import [co.elastic.apm.api ElasticApm Transaction Span]))

(defn current-apm-transaction []
  (ElasticApm/currentTransaction))

(defn current-apm-span []
  (ElasticApm/currentSpan))

(defn add-tag [^Span span-or-tx k v]
  (.addTag span-or-tx k v))

(defn set-name [^Span span-or-tx name]
  (.setName span-or-tx name))

(defn start-transaction
  ([]
   (start-transaction {}))
  ([{tx-name :name,
     tx-type :type,
     tags :tags}]
   (let [tx (ElasticApm/startTransaction)]
     (when tx-name
       (set-name tx tx-name))
     (when tx-type
       (.setType tx tx-type))
     (doseq [[k v] tags]
       (add-tag tx (name k) (str v)))
     tx)))

(defn create-span
  ([]
   (create-span {}))
  ([{parent :parent,
     span-name :name,
     tags :tags}]
   (let [span  (.createSpan (or parent (current-apm-span)))]
     (when span-name
       (set-name span span-name))
     (doseq [[k v] tags]
       (add-tag span (name k) (str v)))
     span)))

(defn end [^Span span-or-tx]
  (.end span-or-tx))

(defn activate [^Span span-or-tx]
  (.activate span-or-tx))

(defn capture-exception [^Span span-or-tx ^Exception e]
  (.captureException span-or-tx e))

(defn apm-transaction*
  ([func]
   (apm-transaction* func {}))
  ([func opts]
   (let [current (current-apm-transaction)
         activate? (or (nil? (:activate? opts)) (:activate? opts))
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
  ([func]
   (apm-span* func {}))
  ([func opts]
   (let [activate? (or (nil? (:activate? opts)) (:activate? opts))
         span (create-span opts)]
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
  `(apm-span* (^{:once true} fn* [~(first binding)] ~@body)
              ~(second binding)))
