# clojure-elastic-apm

A Clojure wrapper for Elastic APM Java API.

NOTE: This library is not released yet. The usage information below is an attempt to document how the library could eventually be used
and to ensure the library works in most common use cases. The information is subject to change.

## Installation

Add this to your Leiningen project `:dependencies`:

```
[yleisradio/clojure-elastic-apm "0.1.0"]
```

You also need to download Elastic APM Java Agent and specify the `-javaagent` option on your java command line. With leiningen, add:

```
:jvm-opts ["-javaagent:path/to/elastic-apm-agent-VERSION.jar"]
```

Follow the [https://www.elastic.co/guide/en/apm/agent/java/current/intro.html](APM Java Agent documentation) on how to download and
configure the agent. Note that the `elastic.apm.application_packages` configuration option should be the top level namespace in your
Clojure application, but with hyphens replaced with underscores.

## Usage

Wrap any code block into APM transaction and track spans within the transaction:

```clojure
(require '[clojure-elastic-apm.core :as apm])

(apm/with-apm-transaction [tx {:name "MyTransaction" :type "Background Job"}]
  (apm/add-tag tx "some_tag" "some value")

  (apm/with-apm-span [span {:name "Operation"}]
    ; do something exciting here
    (Thread/sleep 200))

  (apm/with-apm-span [span {:name "AnotherOperation"}]
    ; do something exciting here
    (apm/add-tag span "another_tag" "another value")
    (Thread/sleep 100)))
```

The options hash for `with-apm-transaction` accepts the following options:

* `:name` - `String` - the transaction name
* `:type` - `String` - the transaction type (the special type `"request"`, or via `apm/type-request`, should be used for transactions that track requests)
* `:tags` - `{String String}` - map or sequence of tag names and values to add to the transaction
* `:activate?` - `Boolean` - whether to make the transaction active in the context of the executing thread (defaults to true). When activated, calling `apm/current-apm-transaction` returns this transaction.

The options hash for `with-apm-span` accepts the following options:

* `:name` - `String` - the span name
* `:parent` - `Span` - the parent span, the new span will be created as child of this span (defaults to current active span or transaction)
* `:activate?` - `Boolean` - whether to make the span active in the context of the executing thread (defaults to true). When activated, calling `apm/current-apm-span` returns this span.
* `:tags` - `{String String}` - map or sequence of tag names and values to add to the transaction

In both cases, all options are optional and the options hash can be omitted completely. However, it's good idea to at least provide the name. At the time of writing, the default transaction type
is `"custom"`.

### Using with Ring

For convenience, the `clojure-elastic-apm.ring` namespace provides `wrap-apm-transaction` Ring middleware:

```clojure
(require '[clojure-elastic-apm.ring :as apm-ring])

(def app (-> app-routes
             wrap-params
             wrap-json-params
             apm-ring/wrap-apm-transaction))
```

With this setup, all requests will be tracked as APM transactions with `"request"` type. The transaction will be named `METHOD /path`.

You can access the APM transaction created by the middleware from the request map under `:clojure-elastic-apm/transaction` key.

No other request information will be added to the transaction. The APM Java Agent's Public API, at the time of writing this library, doesn't allow
setting transaction context fields. However, if you want to include extra context as tags, it's easy to do so by adding custom middleware:

```clojure
(defn wrap-apm-transaction-context [handler]
  (fn [request]
    (when-let [tx (:clojure-elastic-apm/transaction request)]
      (apm/add-tag tx "user_agent" (get-in request [:headers "user-agent"]))
      (apm/add-tag tx "request_query_string" (:query-string request)))
    (handler request)))

(def app (-> app-routes
             wrap-apm-transaction-context
             apm-ring/wrap-apm-transaction))
```


If you use Compojure and would like to name the transactions after the Compojure route, it's possible, albeit some extra work is needed.
Compojure includes the matched route information under `:compojure/route` request key, but unfortunately `wrap-apm-transaction` doesn't have
access to this info - only the routes can see it.

Thus, in each route, you need to manually override the transaction name by invoking `apm/set-transaction-name` with the transaction reference
from request and the transaction name derived from compojure route information:

```clojure
(defroutes app-routes
  (GET "/exchange_rates/:base" request
    (apm/set-transaction-name (:clojure-elastic-apm/transaction) (str (:compojure/route request)))
    {:status 200 :body ...}))
```

If you have a lot of routes or find this to be tedious, it might be worth it creating a custom routes macro that does this automatically. This is not included here because integration with compojure and other routing
libraries is out of this project's scope.

