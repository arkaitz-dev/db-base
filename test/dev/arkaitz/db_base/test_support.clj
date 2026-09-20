(ns dev.arkaitz.db-base.test-support
  "Witnesses shared by the lifecycle and configuration tests. Each exists because
  the property it observes has no other synchronous, attributable signal."
  (:import [clojure.lang ExceptionInfo]
           [java.net InetAddress ServerSocket Socket]))

(defn attempt
  "The refusal as `[message data]`, or `::no-throw`. Catches `ExceptionInfo` only:
  a `ClassCastException` or an NPE must surface as an error naming itself rather
  than read as a wrong message."
  [f]
  (try (f) ::no-throw
       (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

(defn thrown
  "The `ExceptionInfo` `f` throws, or `::no-throw`."
  [f]
  (try (f) ::no-throw
       (catch ExceptionInfo e e)))

(defn thrown-any
  "The exception of any class `f` throws, or `::no-throw` — for a third party's
  refusal, where the class is what gets asserted."
  [f]
  (try (f) ::no-throw
       (catch Exception e e)))

(defn h2-memory-url
  "A private in-memory H2 database that outlives its last connection, so a pool that
  closes every connection does not empty it."
  [label]
  (str "jdbc:h2:mem:" label "-" (random-uuid) ";DB_CLOSE_DELAY=-1"))

(defn sqlite-file-url
  "A SQLite database in a fresh temp file, removed when the JVM exits. Not `:memory:`:
  an in-memory SQLite database is one per connection, and a pool would split it."
  [label]
  (let [f (java.io.File/createTempFile (str "db-base-" label "-") ".db")]
    (.deleteOnExit f)
    (str "jdbc:sqlite:" (.getAbsolutePath f))))

(def dialect-tokens
  "The spellings SPEC §3 and CLAUDE.md name or imply for a dialect that belongs to one
  engine family, each written with the shortest head that identifies it: a longer one
  stops matching the moment someone writes the clause with its own column list. Shared,
  because §3 is proven twice — `dialect_test` fires each of these at both engines and
  `structure_test` refuses to find one spelled in src — and two lists would drift."
  ["ON CONFLICT" "RETURNING" "MERGE INTO" "WHEN MATCHED" "LISTEN" "NOTIFY" "jsonb"])

(def scan-only-tokens
  "Forbidden in src, and invisible to the engines: both H2 and SQLite take
  `CREATE TABLE IF NOT EXISTS`, which SPEC §7 refuses because Derby rejects it. A form
  the pair accepts cannot be a cell of `dialect_test`'s matrix — nothing would refuse it
  — so the scan is the only place it can be said at all."
  ["IF NOT EXISTS"])

(def url-sentinel "URL-SENTINEL-7f3a")
(def user-sentinel "USER-SENTINEL-7f3a")
(def password-sentinel "PASSWORD-SENTINEL-7f3a")

(defn leaks-in
  "Every `[label field secret]` where the message or the printed data of `e`
  contains one of the three sentinels. `pr-str`, not `str`, so a sentinel inside a
  keyword, a symbol or a vector is rendered and seen."
  [label ^Throwable e]
  (vec (for [[field text] [[:message (ex-message e)] [:data (pr-str (ex-data e))]]
             [secret s]   [[:url url-sentinel] [:user user-sentinel] [:password password-sentinel]]
             :when (and text (.contains ^String text ^String s))]
         [label field secret])))

(defn pool-number
  "HikariCP names every pool from this property, incrementing it on the caller's
  thread before any I/O and never decrementing it. So its value moves exactly when
  a pool is constructed — including one that is closed again at once, which no
  thread snapshot can see."
  []
  (some-> (System/getProperty "com.zaxxer.hikari.pool_number") parse-long))

(defn hikari-threads
  "Live threads belonging to pool number `n`."
  [n]
  (let [prefix (str "HikariPool-" n ":")]
    (filter #(.startsWith (.getName ^Thread %) prefix) (keys (Thread/getAllStackTraces)))))

(defn threads-alive-after-join
  "Joins each thread of pool `n` whose name matches `re` for at most `ms` in total
  and returns the names still alive. The join is a hang guard around an exit that
  is asynchronous by construction."
  [n re ms]
  (let [deadline (+ (System/currentTimeMillis) ms)
        matching #(filter (fn [^Thread t] (re-find re (.getName t))) (hikari-threads n))]
    (doseq [^Thread t (matching)]
      (.join t (max 1 (- deadline (System/currentTimeMillis)))))
    (sort (map #(.getName ^Thread %) (filter #(.isAlive ^Thread %) (matching))))))

(defn silent-server
  "A socket on 127.0.0.1 that accepts and never answers: the database whose driver
  connect never returns. Accepted sockets are retained, because a collected socket
  with unread bytes sends a reset — a fast refusal, which is a different test.
  Returns `{:port p :accepted atom :close! fn}`; `close!` ends every connection so a
  driver thread stuck on one gets EOF and does not outlive the test."
  []
  (let [server   (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        accepted (atom [])
        acceptor (doto (Thread. (fn []
                                  (try (loop []
                                         (swap! accepted conj (.accept server))
                                         (recur))
                                       ;; DELIBERATE: closing the server is how the loop ends.
                                       (catch java.io.IOException _ nil))))
                   (.setDaemon true)
                   (.start))]
    {:port     (.getLocalPort server)
     :accepted accepted
     :close!   (fn []
                 (.close server)
                 (doseq [^Socket s @accepted] (.close s))
                 (.join acceptor 5000))}))

(defn elapsed-ms
  "Runs `f` in a future bounded by `guard-ms`. Returns `[result ms]`, where result
  is `::hang` when the guard expired. The guard is a hang guard only; no pass
  criterion may depend on it."
  [guard-ms f]
  (let [t0  (System/nanoTime)
        res (deref (future (f)) guard-ms ::hang)]
    [res (/ (- (System/nanoTime) t0) 1e6)]))
