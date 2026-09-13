(ns dev.arkaitz.db-base
  "A pooled connection with a lifecycle (SPEC §6). `start` takes a map and returns
  a plain map, `stop` closes what `start` opened, and `ready?` asks whether the
  database answers. Nothing here lives in a var root: the handle is the only state
  there is.

  **Configuration is refused before anything opens.** A pool that silently
  defaults to something is a production incident with no symptom in
  development, so every key is required, a key nobody reads is refused, and the
  message names the key. The JDBC URL, the user and the password are reported by
  presence and never by value, in the message or in the data. A connection
  failure keeps the pool's exception as the cause, with the driver's beneath it
  when the driver said anything; what a driver puts in its own exception is the
  driver's. The pool's own exception for a URL no driver accepts echoes the URL,
  so that case is decided here, through `DriverManager`, before the pool sees it.

  **A boot fails within a bounded time.** The pool is HikariCP, chosen by
  measurement (CLAUDE.md). Its default constructor opens a connection on the
  caller's thread with no deadline — measured past 30 s against a socket that
  accepts and never answers — so fail-fast initialisation is switched off and
  `start` borrows one connection itself, which Hikari bounds by
  `[:pool :timeout-ms]`. What no pool can bound is the driver's own connect
  thread stuck on such a socket: it survives `stop` as a daemon, and only the
  driver's socket timeout, which is the host's URL, cures it.

  **What the pool and the driver read on their own is the host's JVM** (SPEC §6).
  This library reads no file, environment or property; HikariCP honours
  `hikaricp.configurationFile` when the JVM sets it, and a driver reads its own
  files, and neither is refused here."
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.sql Connection DriverManager SQLException]
           [javax.sql DataSource]))

(def ^:private config-keys #{:jdbc-url :user :password :pool :migrations})

(def ^:private pool-keys #{:max :timeout-ms})

(def ^:private migrations-keys #{:dir})

(def ^:private hikari-timeout-floor-ms
  "HikariCP's default floor: it throws `IllegalArgumentException` below this and turns
  0 into `Integer/MAX_VALUE`, so the floor is enforced here, where it can be named as a
  configuration key. A JVM that raises it makes the pool refuse more; `open-pool`
  reports that under the same key."
  250)

(defn- fail!
  ([message config-key]
   (throw (ex-info (str "db-base: " message) {:config-key config-key})))
  ([message config-key value]
   (throw (ex-info (str "db-base: " message) {:config-key config-key :value value}))))

(defn- refuse-unknown-keys!
  "A key nobody reads is configuration the host believes is in force — `:migration`
  for `:migrations` would otherwise buy a pool with no migrations and no complaint.
  Sorted by printed form so keys of mixed types cannot make the refusal throw."
  [m allowed path]
  (when-let [unknown (not-empty (sort-by pr-str (remove allowed (keys m))))]
    (fail! (str "unknown key" (when (next unknown) "s") " " (pr-str (vec unknown))
                (if (seq path) (str " in " (pr-str path)) "")
                " — it takes " (pr-str (vec (sort allowed))))
           (conj path (first unknown)))))

(defn- non-blank-string? [x]
  (and (string? x) (not (.isBlank ^String x))))

(defn- integer-between?
  "Any integer type, BigInt included, as long as it fits what the pool's setter
  takes."
  [low high x]
  (and (integer? x) (<= low x high)))

(defn- validate-pool! [pool]
  (when-not (map? pool)
    (fail! ":pool must be a map of :max and :timeout-ms" [:pool] pool))
  (refuse-unknown-keys! pool pool-keys [:pool])
  (let [{:keys [max timeout-ms]} pool]
    (when-not (integer-between? 1 Integer/MAX_VALUE max)
      (fail! (str "[:pool :max] must be an integer from 1 to " Integer/MAX_VALUE) [:pool :max] max))
    ;; HikariCP casts timeouts to int (PoolBase.setNetworkTimeout) and reads
    ;; Integer/MAX_VALUE as no timeout at all, so the largest real deadline is one less.
    (when-not (integer-between? hikari-timeout-floor-ms (dec Integer/MAX_VALUE) timeout-ms)
      (fail! (str "[:pool :timeout-ms] must be an integer from " hikari-timeout-floor-ms
                  " to " (dec Integer/MAX_VALUE) " milliseconds")
             [:pool :timeout-ms] timeout-ms))))

(defn- validate-migrations! [migrations]
  (cond
    (= :none migrations) nil

    (map? migrations)
    (do (refuse-unknown-keys! migrations migrations-keys [:migrations])
        (when-not (non-blank-string? (:dir migrations))
          (fail! "[:migrations :dir] must be a non-blank string" [:migrations :dir]
                 (:dir migrations))))

    :else
    (fail! ":migrations must be :none or a map with :dir" [:migrations] migrations)))

(defn- validate! [config]
  (when-not (map? config)
    (throw (ex-info "db-base: configuration must be a map" {:config-key []})))
  (refuse-unknown-keys! config config-keys [])
  (let [{:keys [jdbc-url user password pool migrations]} config]
    (when-not (non-blank-string? jdbc-url)
      (fail! ":jdbc-url must be a non-blank string" [:jdbc-url]))
    (when-not (string? user)
      (fail! ":user must be a string (\"\" is a value)" [:user]))
    ;; Never defaulted and never generated: it arrives or the call fails (§6).
    (when-not (string? password)
      (fail! ":password must be a string (\"\" is a value)" [:password]))
    (validate-pool! pool)
    (validate-migrations! migrations)))

(defn- open-pool ^HikariDataSource [{:keys [jdbc-url user password pool]}]
  (let [{:keys [max timeout-ms]} pool
        hikari (doto (HikariConfig.)
                 (.setJdbcUrl jdbc-url)
                 (.setUsername user)
                 (.setPassword password)
                 (.setMaximumPoolSize (int max))
                 ;; The fail-fast check connects on the caller's thread with no
                 ;; deadline; `start` borrows under the timeout instead.
                 (.setInitializationFailTimeout -1))]
    (try
      (doto hikari
        (.setConnectionTimeout (long timeout-ms))
        ;; Its 5 s default is spent on top of the acquisition timeout whenever a
        ;; borrowed connection is checked for liveness.
        (.setValidationTimeout (long timeout-ms)))
      (catch IllegalArgumentException e
        ;; 250 is only HikariCP's default floor: a JVM started with
        ;; com.zaxxer.hikari.timeoutMs.floor raised makes the pool refuse a value
        ;; validation accepted, and that refusal still names the key.
        (throw (ex-info "db-base: the pool refused [:pool :timeout-ms]"
                        {:config-key [:pool :timeout-ms] :value timeout-ms} e))))
    (HikariDataSource. hikari)))

(defn- close-after-failure!
  "Closes `resource` without letting a failure to close replace the failure that
  made closing necessary."
  [^java.lang.AutoCloseable resource ^Throwable failure]
  (try (.close resource)
       (catch Throwable t
         ;; A resource that rethrows the very failure it was closed for: a throwable
         ;; cannot suppress itself, and trying would throw instead.
         (when-not (identical? t failure) (.addSuppressed failure t)))))

(defn start
  "Validates `config`, opens the pool and borrows one connection before
  returning, so a database that cannot be reached fails the boot instead of the
  first request. Returns `{:datasource ds}`, where `ds` is a
  `javax.sql.DataSource` and nothing more.

    :jdbc-url    non-blank string
    :user        string, \"\" included
    :password    string, \"\" included — never defaulted, never generated
    :pool        {:max integer 1..2147483647 :timeout-ms integer 250..2147483646}
    :migrations  :none, or {:dir non-blank-string}

  Every failure is `ex-info` carrying `:config-key` as a vector path, except what
  the host's JVM makes HikariCP throw on its own, such as a
  `hikaricp.configurationFile` that cannot be found, read or applied (SPEC §6). A pool that
  opened is closed before a failure leaves this function, so an unreachable
  database costs `[:pool :timeout-ms]` to borrow plus, at most, HikariCP's wait for
  its connection-adder on close: the login timeout, which is the timeout plus half a
  second in whole seconds and never less than one. A driver stuck on a socket spends
  all of it. HikariCP keeps the login timeout on `DriverManager`, which the JVM
  shares, so with several pools the wait is the last-constructed pool's."
  [config]
  (validate! config)
  (when (map? (:migrations config))
    ;; Refused rather than skipped: running zero migrations silently is §7's trap.
    (fail! "[:migrations :dir] is not implemented yet; use :migrations :none"
           [:migrations :dir]))
  (try (DriverManager/getDriver (:jdbc-url config))
       (catch SQLException e
         (throw (ex-info "db-base: no JDBC driver on the classpath accepts :jdbc-url"
                         {:config-key [:jdbc-url]} e))))
  (let [ds (open-pool config)]
    (try
      (with-open [^Connection _ (.getConnection ds)])
      {:datasource ds}
      (catch Throwable t
        (close-after-failure! ds t)
        (throw (if (instance? Exception t)
                 (ex-info (str "db-base: no connection to the database within "
                               (get-in config [:pool :timeout-ms]) " ms")
                          {:config-key [:pool :timeout-ms]} t)
                 t))))))

(defn stop
  "Closes the pool behind a handle returned by `start`."
  [{:keys [datasource]}]
  (.close ^HikariDataSource datasource))

(defn ready?
  "Whether the database answers, through `java.sql.Connection/isValid` on a
  connection borrowed from the handle's pool. `timeout` is in seconds, as `isValid`
  takes it, and is an integer from 1 to 2147483647: zero is `isValid`'s no deadline.

  Returns true or false, and throws `ex-info` only for what it refuses before
  borrowing: a handle whose `:datasource` is not a `javax.sql.DataSource`, under
  `:config-key [:datasource]`, and a timeout out of range, under `:config-key
  [:timeout]`. A borrow that fails, an `isValid` that throws and a connection that
  cannot be given back all answer false. An `Error`, from `isValid` or from closing
  the connection, is not an answer and propagates.

  The timeout bounds the call only as far as the driver honours it (SPEC §6):
  `isValid` runs on the caller's thread, and so does the check HikariCP makes through
  the driver before lending a connection that sat idle. The wait for a free connection
  is bounded by `[:pool :timeout-ms]`, and that check only as far as the driver honours
  the same number, so a borrow can outlast the wait by one check; H2 over TCP ignores
  both, and only `NETWORK_TIMEOUT` in the URL bounds it."
  [handle timeout]
  (let [datasource (:datasource handle)]
    ;; Never echoed: what a mis-wired caller passed may be the configuration map.
    (when-not (instance? DataSource datasource)
      (fail! "ready? takes the handle start returned, whose :datasource is a javax.sql.DataSource"
             [:datasource]))
    (when-not (integer-between? 1 Integer/MAX_VALUE timeout)
      (fail! (str "[:timeout] must be an integer from 1 to " Integer/MAX_VALUE " seconds")
             [:timeout] timeout))
    (try
      (let [^Connection c (.getConnection ^DataSource datasource)
            answer        (try (.isValid c (int timeout))
                               (catch Throwable t
                                 ;; Not with-open, whose finally lets whatever close
                                 ;; throws replace t. An Error must outlive a close that
                                 ;; fails; after an Exception, which is about to become
                                 ;; false, an Error from close must get through.
                                 (if (instance? Error t)
                                   (close-after-failure! c t)
                                   (.close c))
                                 (throw t)))]
        (.close c)
        answer)
      ;; A closed pool, a borrow that timed out and a driver's refusal all mean the
      ;; database did not answer. An Error is not an answer, so it is not caught.
      (catch Exception _ false))))
