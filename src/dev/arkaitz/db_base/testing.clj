(ns dev.arkaitz.db-base.testing
  "For a host's tests, and only what a host's tests asked for: a reader that does not go
  through the code under test, and a way to suspend one caller between its statements.
  Nothing here is meant for a request path, and nothing here reads a file; a temporary
  database is the host's to make, because making one is a file.

  Added 2026-09-25 from `demo-tasks/`, whose tests wrote both by hand. `structure_test`
  pins the three public vars, and a fourth is an amendment of SPEC §10 before it is code."
  (:import [java.lang.reflect InvocationHandler InvocationTargetException Method Proxy]
           [java.sql Clob Connection Driver DriverManager PreparedStatement ResultSet SQLException]
           [java.util Properties]
           [java.util.concurrent CountDownLatch TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]
           [javax.sql DataSource]))

(defn- refuse! [message config-key]
  (throw (ex-info (str "db-base: " message) {:config-key config-key})))

(defn- connect
  "A connection of the caller's own, opened from `config` and never from a pool. The
  driver is asked first, as `start` asks it, because `DriverManager`'s own refusal for a
  URL nobody accepts carries the URL."
  ^Connection [config]
  ;; Never echoed: this is the configuration map, password included.
  (when-not (map? config)
    (refuse! "rows reads through the configuration map start takes" []))
  (let [{:keys [jdbc-url user password]} config]
    (when-not (and (string? jdbc-url) (not (.isBlank ^String jdbc-url)))
      (refuse! ":jdbc-url must be a non-blank string" [:jdbc-url]))
    (when-not (string? user) (refuse! ":user must be a string (\"\" is a value)" [:user]))
    (when-not (string? password) (refuse! ":password must be a string (\"\" is a value)" [:password]))
    (let [driver (try (DriverManager/getDriver jdbc-url)
                      (catch SQLException e
                        (throw (ex-info "db-base: no JDBC driver on the classpath accepts :jdbc-url"
                                        {:config-key [:jdbc-url]} e))))]
      ;; `connect` answers nil for a URL the driver disowns, which after `getDriver`
      ;; matched it no shipped driver does; named rather than left to an NPE.
      (or (.connect ^Driver driver jdbc-url (doto (Properties.)
                                              (.setProperty "user" user)
                                              (.setProperty "password" password)))
          (refuse! "the JDBC driver that accepts :jdbc-url returned no connection" [:jdbc-url])))))

(defn- column-value
  "A `Clob` read while its connection is still open, because it is a handle into that
  connection and not a value; anything else as the driver returned it."
  [v]
  (if (instance? Clob v)
    (let [^Clob c v] (.getSubString c 1 (int (.length c))))
    v))

(defn rows
  "Every row `sql` answers, as vectors, through a connection opened from `config` — the
  map `start` takes — and closed before this returns. Never the pool's: a test that
  reads back a write through the handle that made it asks the same code twice.

  Values come back as the driver's `getObject` gives them, so a count stays a number,
  except a `java.sql.Clob`, which comes back as a String: H2 returns the text column of
  SPEC §8 as one and SQLite as a String, and a reader written against either alone
  breaks on the other.

  The configuration is refused by key and never echoed. What the driver throws when it
  cannot connect is the driver's."
  [config sql & params]
  (with-open [c  (connect config)
              st (.prepareStatement c ^String sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject ^PreparedStatement st (int (inc i)) p))
    (with-open [^ResultSet rs (.executeQuery ^PreparedStatement st)]
      (let [n (.getColumnCount (.getMetaData rs))]
        (loop [acc []]
          (if (.next rs)
            (recur (conj acc (mapv #(column-value (.getObject rs (int %))) (range 1 (inc n)))))
            acc))))))

(defn one
  "The first column of the first row `sql` answers, or nil when it answers none — what
  `rows` reads, cut to one value. Further rows and columns are ignored, so a query that
  must match one row says so in its own `WHERE`."
  [config sql & params]
  (ffirst (apply rows config sql params)))

;; --- parking ------------------------------------------------------------------

(defn- forward
  "Calls `m` on `target`, and throws what the target threw rather than the reflective
  wrapper: through a proxy, a driver's `SQLException` would otherwise reach the caller
  as an `UndeclaredThrowableException`, and every `catch SQLException` above it —
  `arbitrate!`'s included — would miss it."
  [^Method m target args]
  (try (.invoke m target (object-array (or args [])))
       (catch InvocationTargetException e (throw (.getCause e)))))

(defn- proxying [^Class iface f]
  (Proxy/newProxyInstance (.getClassLoader iface) (into-array Class [iface])
                          (reify InvocationHandler
                            (invoke [_ _proxy method args] (f method args)))))

(def ^:private preparing #{"prepareStatement" "prepareCall"})

(defn parking
  "A handle like `handle`, except that the first statement whose SQL `accepts?` answers
  truthy for — prepared through `prepareStatement` or `prepareCall` on any connection it
  lends — waits before it is prepared, until `release!` is called or `guard-ms` runs out.
  Only the first: every later caller runs through, which is what lets a test suspend one
  caller between two of its statements and run another to completion in that window,
  chosen rather than hoped for. Returns

    {:handle   the derived handle, for the caller that must park
     :arrived  a promise, delivered the SQL of the parked statement when it parks
     :release! a function of no arguments that ends the park; calling it twice is harmless
     :exit     a promise, delivered :released, :guard-expired or :interrupted when the
               park ends}

  **`:exit` is the one to assert.** A park that ended by `guard-ms` means the window was
  not the one the test built, and a test that ignores that proves nothing about the order
  it claims. `guard-ms` is a hang guard and never a criterion: an integer from 1 to
  2147483647, of any integer type. An interrupt ends the park too, with the thread's flag set again; in every
  case the statement is then prepared as it would have been.

  What it cannot see: a statement run through `createStatement`, which never passes SQL
  to a prepare. next.jdbc prepares every statement it is handed as a vector.

  **Stop the handle you passed in, never `:handle`**: it holds the same pool behind a
  datasource that is not the pool, which `stop` cannot close — it throws a
  `ClassCastException` and leaves the pool open. **A parked caller holds a
  borrowed connection**, so a pool of one deadlocks the caller meant to run through,
  until the guard. The park comes before the driver sees the statement, so a caller in
  autocommit holds no lock while it waits; **one parked inside a transaction does, and
  an SQLite file then wants WAL**, where a reader does not block a writer. **An H2 memory
  URL needs a close delay**, or the last connection to close takes the database with it."
  [handle accepts? guard-ms]
  (when-not (instance? DataSource (:datasource handle))
    (refuse! "parking takes the handle start returned, whose :datasource is a javax.sql.DataSource"
             [:datasource]))
  (when-not (ifn? accepts?)
    (refuse! "parking takes a predicate of the statement's SQL" [:accepts?]))
  (when-not (and (integer? guard-ms) (<= 1 guard-ms Integer/MAX_VALUE))
    (throw (ex-info (str "db-base: guard-ms must be an integer from 1 to " Integer/MAX_VALUE)
                    {:config-key [:guard-ms] :value guard-ms})))
  (let [^DataSource real (:datasource handle)
        fired    (AtomicBoolean. false)
        released (CountDownLatch. 1)
        arrived  (promise)
        exit     (promise)
        park!    (fn [sql]
                   (deliver arrived sql)
                   (deliver exit (try (if (.await released (long guard-ms) TimeUnit/MILLISECONDS)
                                        :released
                                        :guard-expired)
                                      (catch InterruptedException _
                                        (.interrupt (Thread/currentThread))
                                        :interrupted))))
        lend     (fn [^Connection connection]
                   (proxying Connection
                             (fn [^Method m args]
                               (when (and (not (.get fired))
                                          (preparing (.getName m))
                                          (string? (first args))
                                          (accepts? (first args))
                                          (.compareAndSet fired false true))
                                 (park! (first args)))
                               (forward m connection args))))]
    {:handle   (assoc handle :datasource
                      (proxying DataSource
                                (fn [^Method m args]
                                  (let [answer (forward m real args)]
                                    (if (= "getConnection" (.getName m)) (lend answer) answer)))))
     :arrived  arrived
     :release! (fn [] (.countDown released))
     :exit     exit}))
