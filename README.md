# clojure-elastic-apm

A Clojure wrapper for Elastic APM Java Agent's Public API.

## Installation

First you need to download Elastic APM Java Agent and configure it. Follow the instructions in
[APM Java Agent documentation](https://www.elastic.co/guide/en/apm/agent/java/current/intro.html)
on how to do so.

The easiest way to enable the Java Agent on development is to add it to `:jvm-opts` in Leiningen
along with the agent's recommended configuration options:

```clojure
:jvm-opts ["-javaagent:path/to/elastic-apm-agent-AGENT_VERSION.jar"
           "-Delastic.apm.service_name=my-app-production"
           "-Delastic.apm.application_packages=my_app"
           "-Delastic.apm.server_urls=http://localhost:8200"]
```

Now with the agent setup, you need to add two dependencies to your project: one for the Elastic APM Public API and
one for clojure-elastic-apm. clojure-elastic-apm doesn't provide this dependency automatically, because it should
match exactly with the agent version.

So, add these to your project.clj dependencies:

```clojure
[co.elastic.apm/apm-agent-api "AGENT_VERSION"]
[yleisradio/clojure-elastic-apm "0.2.0"]
```

Note, in the agent configuration, the `elastic.apm.application_packages` option should be the top level namespace in your
Clojure application, but with hyphens replaced with underscores.

## Usage

Wrap any code block into APM transaction and track spans within the transaction:

```clojure
(require '[clojure-elastic-apm.core :as apm])

(apm/with-apm-transaction [tx {:name "MyTransaction" :type "Background Job"}]
  (do-something)

  (apm/with-apm-span [span {:name "Operation"}]
    (do-something-in-a-span)))
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

### Manually starting, activating and ending transactions and spans

The `with-apm-transaction` and `with-apm-span` macros are useful if the transaction or span starts and ends in the same thread, which is often
the case. However, if you need to track transactions that start in a different thread than where they end, you can also manually start a transaction
in one thread, hand it off to another thread and activate it there.

For example, consider a scenario where your HTTP API accepts a request to build reports. You queue each request to be processed by a `ExecutorService`
and then send email when things finish. If you wanted to measure the time between HTTP API getting the request and it being processed, you could
do something like this:

```clojure
(defn process-report-request [tx request]
  (try
    (with-open [scope (apm/activate tx)]
      (build-and-send-report request))
    (catch Exception e
      (send-failure-notice request)
      (apm/capture-exception e))
    (finally
      (apm/end tx))))

(defn handle-report-request [request]
  (let [tx (apm/start-transaction {:name "" :type apm/type-request})]
    (.submit executor #(process-report-request tx request))
    {:status 201 :body "Accepted"))
```

The process is similar for spans, but instead of `apm/start-transaction` you would use `apm/create-span`.
All the same options, except for the activation, are supported here.

Remember to always close any resources, or otherwise you might cause memory leaks:

1. Always wrap any code in span/transaction to try..catch and end the transaction in `finally` block using `apm/end`
2. Use `with-open` when activating a span/transaction
3. The scope opened by `apm/activate` needs to be closed in the same thread

### Getting current active transaction or span

Current active transaction can be retrieved by calling `apm/current-apm-transaction`. The active span can be retrieved with `apm/current-apm-span`.

Note that these functions might return a "noop" span or transaction in case there's no active span/transaction. This means you never need to check
for null values. You can still capture exceptions on noop spans/transactions - they just will be reported at the application level and will not
be associated with any particular span.

### Capture and report exceptions

Any exceptions thrown out of `with-apm-transaction` and `with-apm-span` macro bodies will automatically be captured and reported on the APM
server and then rethrown.

You can also manually report exceptions that you don't wish to propagate further by calling `apm/capture-exception`:

```
(with-apm-transaction [tx {:name "BackgroundJob"}]
  (try
    (do-something)
    (catch Exception e
      (apm/capture-exception tx e)
      (do-something-else))))
```

A function specifically designed for doing this, `clojure-elastic-apm.core/catch-error-to-apm` is included, which takes a thunk function, attempts 
to evaluate it, and captures the exception if one occurs.


### Adding tags

You can add tags to any transaction or span by using `apm/add-tag`:

```
(apm/with-apm-transaction [tx {:name "CreatePayment"}]
  (let [payment (create-payment ...)]
    (apm/add-tag tx "payment_id" (:id payment)
    (store-payment payment)
    ...)))
```

Only string keys and values are supported for tags by APM, so for anything non-string, `add-tag` will
convert the key or value to a string using `.toString`.

### Overriding transaction name

If you wish to override the transaction name (for example, because you didn't know it when starting the transaction), you can do so
any time by calling `apm/set-name` on the transaction:

```
(apm/with-apm-transaction [tx]
  (do-something)
  (apm/set-name tx "BackgroundJob"))
```

See the section below on using with Ring for a more concrete example where this might be useful.:

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

#### Filtering and matching URIs

The `wrap-apm-transaction` middleware takes an optional argument, that allows you to pass a list of URI patterns, which 
will be matched against any requests, and only log a transaction for those that match the pattern:

```clojure
(require '[clojure-elastic-apm.ring :as apm-ring])

(def app (->> app-routes
              wrap-params
              wrap-json-params
              (apm-ring/wrap-apm-transaction ["/*/*/_"])))
```

The pattern format breaks up the URI into segments on the slashes ("/") and matches the segments with the following syntax:
- `*`: matches any data in the segment, and keeps it in the resulting transaction name
- `_`: matches any data in the segment, but ignores it in the resulting tx name, instead inserting an asterisk('*') character
- any other string: matches only if the segment in the pattern and the URI match exactly, and retains the string in the tx name

In the example above, the URI `/v1/foo/124` would match and yield `/v1/foo/*` as the tx name, while `/v1/foo` would not. This allows you to both ignore all but a specific set of URLs, while also grouping URLs in the APM tx logs by leaving out 
unnecessary extra detail like URL parameters.

If any segments fail to match one of the given patterns, then no transaction is logged. The matcher will attempt to match
the patterns in descending order as given in the vector. Matches are "eager": a short pattern like `"/*"` will match any URI, but ignore segments after it (so `/*` would match against `/v1/foo/bar` but only return `/v1`).

#### Accessing the current transaction

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
    (when-let [tx (:clojure-elastic-apm/transaction request)]
      (apm/set-name tx (str (:compojure/route request))))
    {:status 200 :body ...}))
```

If you have a lot of routes or find this to be tedious, it might be worth it creating a custom routes macro that does this automatically. This is not included here because integration with compojure and other routing
libraries is out of this project's scope.

## Development

### Running tests

There are some tests for the functionality. To run them, you need to have ElasticSearch, Kibana and APM server running at ports 9200, 5601 and
8200 respectively. You can either manually install the necessary components, or use the provided docker-compose file to launch all three
as containers:

```bash
$ docker-compose up -d
```

Then, to run tests:

```bash
$ lein test
```

Note that the tests are extremely slow. The APM Agent's Public API doesn't provide a way to retrieve the information we set in clojure-elastic-apm.
The only way to access all the details is to fetch the transaction info from ElasticSearch. This takes time, because the agent can sync only every
1 second and the APM server doesn't flush to ElasticSearch all the time either.

