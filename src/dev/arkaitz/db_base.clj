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
  files, and neither is refused here.

  **Migrations run inside `start`, before it returns** (SPEC §7). They come from a
  classpath prefix the host names, through ragtime, and are recorded in
  `ragtime_migrations`. The source is loaded and checked before any pool exists; a
  failure at migration N leaves everything before it applied and recorded, closes the
  pool and names N. What to apply is decided from the recorded ids as a set and the
  source's order, never from the order the control table returns them in."
  (:require [clojure.string :as str]
            [ragtime.core :as ragtime]
            [dev.arkaitz.db-base.collision :as collision]
            [dev.arkaitz.db-base.dialect.postgresql :as postgresql]
            [dev.arkaitz.db-base.session.schema :as schema]
            [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.protocols :as ragtime-protocols]
            ;; ragtime's own resource reader: the same listing it loads from, so a file it
            ;; would ignore can be refused instead (SPEC §7).
            [resauce.core :as resauce])
  (:import [clojure.lang ExceptionInfo]
           [com.zaxxer.hikari HikariConfig HikariDataSource]
           [java.sql Connection DriverManager PreparedStatement ResultSet SQLException]
           [javax.sql DataSource]))

(def ^:private config-keys #{:jdbc-url :user :password :pool :migrations :sessions :libraries})

(def ^:private pool-keys #{:max :timeout-ms})

(def ^:private migrations-keys #{:dir :lock-wait-ms})

(def ^:private sessions-keys #{:lock-wait-ms :dialect})

(def ^:private library-keys #{:dir :table :lock-wait-ms})

(def ^:private session-dialects
  "The session schemas a host may name under `[:sessions :dialect]`, each a dialect
  namespace's own (SPEC §8). Named and never detected: an engine absent from here gets
  the portable schema, which every engine measured takes."
  {:postgresql postgresql/sessions})

(defn- session-schema
  "`{:migrations … :data-max …}` for the dialect `sessions` names, or the portable one."
  [sessions]
  (if-let [dialect (:dialect sessions)]
    (session-dialects dialect)
    {:migrations schema/migrations :data-max schema/data-max}))

(def ^:private host-migrations-table
  "The host's control table: ragtime's own default, so a host that already used ragtime
  keeps its history (SPEC §7)."
  "ragtime_migrations")

(def ^:private library-migrations-table
  "This library's own control table, beside the host's and never shared with it (SPEC
  §8). A single table breaks three ways in silence: a library migration that sorts below
  ones the host already applied is skipped forever, a tool that checksums applied
  migrations bricks every host's boot the day this library edits its own, and the host's
  own reset drops this library's history with it."
  "db_base_migrations")

(def ^:private lock-table
  "This library's own table, written here and never generated (SPEC §7). It has to exist
  before any migration could record a version of it, so its columns can never change: a
  different shape would be a different name."
  "db_base_migration_lock")

(defn- host-run
  "How the host's migration run names itself. Every refusal below reads the control
  table, the source and the configuration key to blame from a map like this one rather
  than from a literal, because this library has a second run of its own to make and the
  two agree on nothing but the lock table they share (SPEC §7, §8). `:extra` is whatever
  else that run's lock refusal carries in its data."
  [dir wait-ms]
  {:table      host-migrations-table
   :source     dir
   :value      dir
   :blame      [:migrations :dir]
   :wait-blame [:migrations :lock-wait-ms]
   :wait-ms    wait-ms
   :extra      {:dir dir}})

(defn- library-dir-run
  "How a library's run from `:libraries` names itself: like the host's — a classpath
  prefix, loaded and refused the same way — under its own control table, blaming its own
  entry."
  [i {:keys [dir table lock-wait-ms]}]
  {:table      table
   :source     dir
   :value      dir
   :blame      [:libraries i :dir]
   :wait-blame [:libraries i :lock-wait-ms]
   :wait-ms    lock-wait-ms
   :extra      {:dir dir}})

(defn- library-run
  "How this library's own migration run names itself. Its source is a version of this
  library rather than a prefix, so a refusal carries no `:value`: nothing the host wrote
  is at fault, and the key it blames is the one the host wrote to ask for the run at all.
  The phrase reads after \"not found under\", which is the downgrade case — a database
  migrated by a newer db-base and then booted by an older one."
  [wait-ms dialect]
  {:table      library-migrations-table
   :source     (if dialect
                 (str "the " dialect " session schema this version of db-base ships")
                 "the schema this version of db-base ships")
   :blame      [:sessions]
   :wait-blame [:sessions :lock-wait-ms]
   :wait-ms    wait-ms
   :extra      {}})

(def ^:private lock-poll-ms
  "How finely the wait for the lock is sliced. Nothing depends on it: what a boot waits
  is `[:migrations :lock-wait-ms]`."
  50)

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
  "Any integer type, BigInt included, within bounds the caller derives."
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
    (let [{:keys [dir lock-wait-ms]} migrations]
      (refuse-unknown-keys! migrations migrations-keys [:migrations])
      (when-not (non-blank-string? dir)
        (fail! "[:migrations :dir] must be a non-blank string" [:migrations :dir] dir))
      ;; 0 is a single attempt. The ceiling is the pool timeouts' own, decided with the
      ;; user: far past any migration, and still an int.
      (when-not (integer-between? 0 Integer/MAX_VALUE lock-wait-ms)
        (fail! (str "[:migrations :lock-wait-ms] must be an integer from 0 to " Integer/MAX_VALUE
                    " milliseconds")
               [:migrations :lock-wait-ms] lock-wait-ms)))

    :else
    (fail! ":migrations must be :none or a map of :dir and :lock-wait-ms" [:migrations] migrations)))

(defn- validate-sessions! [sessions]
  (cond
    (= :none sessions) nil

    (map? sessions)
    (let [{:keys [lock-wait-ms]} sessions]
      (refuse-unknown-keys! sessions sessions-keys [:sessions])
      ;; The same bounds as the host's run waits by, and for the same reason: two
      ;; instances booting at once is the ordinary case, and a lock without a deadline
      ;; turns it into a hang (SPEC §7).
      (when-not (integer-between? 0 Integer/MAX_VALUE lock-wait-ms)
        (fail! (str "[:sessions :lock-wait-ms] must be an integer from 0 to " Integer/MAX_VALUE
                    " milliseconds")
               [:sessions :lock-wait-ms] lock-wait-ms))
      (when (and (contains? sessions :dialect) (not (contains? session-dialects (:dialect sessions))))
        (fail! (str "[:sessions :dialect] must be one of " (pr-str (vec (sort (keys session-dialects))))
                    ", or absent for the portable schema")
               [:sessions :dialect] (:dialect sessions))))

    :else
    (fail! ":sessions must be :none or a map of :lock-wait-ms and :dialect" [:sessions] sessions)))

(def ^:private table-name
  "What a library run's control table may be called. Its name is written into statements
  — the lock row's id among them, between quotes — so it is an identifier and nothing
  else, in the one case every engine folds the same way."
  #"[a-z][a-z0-9_]{0,62}")

(defn- validate-libraries!
  "`:libraries`, a vector of `{:dir … :table … :lock-wait-ms …}`: each a migration run of
  a library's own, under a control table and a lock row of its own (since 0.4.0). Absent
  is none."
  [libraries]
  (when (some? libraries)
    (when-not (and (vector? libraries) (every? map? libraries))
      (fail! ":libraries must be a vector of {:dir :table :lock-wait-ms} maps" [:libraries] libraries))
    (doseq [[i {:keys [dir table lock-wait-ms] :as library}] (map-indexed vector libraries)]
      (refuse-unknown-keys! library library-keys [:libraries i])
      (when-not (non-blank-string? dir)
        (fail! (str "[:libraries " i " :dir] must be a non-blank string") [:libraries i :dir] dir))
      (when-not (and (string? table) (re-matches table-name table))
        (fail! (str "[:libraries " i " :table] must be a lower-case identifier: a letter, then"
                    " letters, digits or _, at most 63")
               [:libraries i :table] table))
      ;; Each run is told apart by its control table, and the lock row by that name.
      (when (#{host-migrations-table library-migrations-table lock-table schema/table} table)
        (fail! (str "[:libraries " i " :table] " table " is one of this library's own tables")
               [:libraries i :table] table))
      (when-not (integer-between? 0 Integer/MAX_VALUE lock-wait-ms)
        (fail! (str "[:libraries " i " :lock-wait-ms] must be an integer from 0 to " Integer/MAX_VALUE
                    " milliseconds")
               [:libraries i :lock-wait-ms] lock-wait-ms)))
    (when-let [twice (first (for [[t n] (frequencies (map :table libraries)) :when (< 1 n)] t))]
      (fail! (str ":libraries names the control table " twice " twice") [:libraries] twice))))

(defn- validate! [config]
  (when-not (map? config)
    (throw (ex-info "db-base: configuration must be a map" {:config-key []})))
  (refuse-unknown-keys! config config-keys [])
  (let [{:keys [jdbc-url user password pool migrations sessions]} config]
    (when-not (non-blank-string? jdbc-url)
      (fail! ":jdbc-url must be a non-blank string" [:jdbc-url]))
    (when-not (string? user)
      (fail! ":user must be a string (\"\" is a value)" [:user]))
    ;; Never defaulted and never generated: it arrives or the call fails (§6).
    (when-not (string? password)
      (fail! ":password must be a string (\"\" is a value)" [:password]))
    (validate-pool! pool)
    (validate-migrations! migrations)
    ;; Asked for like everything else and never defaulted: a host says whether it wants
    ;; the table of §8 or not. `:none` is the opt-out, exactly as it is for :migrations.
    (validate-sessions! sessions)
    (validate-libraries! (:libraries config))))

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

(defn- close-uninterrupted!
  "Closes `resource` with the thread's interrupt flag cleared, and sets it again
  afterwards. HikariCP's close returns at once when the flag is set and leaves the
  pool's housekeeper and connection closer running (measured, three pools of three)."
  [^java.lang.AutoCloseable resource]
  (let [interrupted (Thread/interrupted)]
    (try (.close resource)
         (finally (when interrupted (.interrupt (Thread/currentThread)))))))

(defn- close-after-failure!
  "Closes `resource` without letting a failure to close replace the failure that
  made closing necessary — unless closing threw an `Error` where the failure is not
  one. SPEC §6: an `Error` is not a failure of the configuration or of the database and
  passes through unwrapped, so then it is the `Error` that leaves, carrying the failure
  it interrupted as its suppressed. Between two `Error`s the first one keeps the way
  out, which is what a `try`-with-resources would do and the more informative of the
  two."
  [^java.lang.AutoCloseable resource ^Throwable failure]
  (try (close-uninterrupted! resource)
       (catch Throwable t
         ;; A resource that rethrows the very failure it was closed for: a throwable
         ;; cannot suppress itself, and trying would throw instead. Asked first, so an
         ;; `Error` identical to the failure never reaches the branch below.
         (when-not (identical? t failure)
           (if (and (instance? Error t) (not (instance? Error failure)))
             (do (.addSuppressed t failure) (throw t))
             (.addSuppressed failure t))))))

(defn- migration-failure [run message id cause]
  (ex-info (str "db-base: " message)
           (cond-> {:config-key (:blame run)}
             (contains? run :value) (assoc :value (:value run))
             id (assoc :migration-id id))
           cause))

(def ^:private migration-file-name
  "What ragtime loads: EDN, or SQL named `<id>.up.sql` / `<id>.down.sql`, the statements
  optionally split across numbered files. Anything else it passes over without a word, or
  loads under an empty id and runs nothing of — `001-a.sql` is that second case — and
  either way it is a migration that never runs."
  #"^[^/]+\.edn$|^[^/]+\.(?:up|down)(?:\.\d+)?\.sql$")

(defn- refuse-files-ragtime-would-ignore! [run]
  (let [dir     (:source run)
        ignored (for [url   (resauce/resource-dir dir)
                      :let  [address (str url)]
                      ;; A subdirectory ends in a slash. Only direct children count, which
                      ;; §7 records, so a directory beside them is not a mistake.
                      :when (not (.endsWith address "/"))
                      :let  [file-name (subs address (inc (.lastIndexOf address "/")))]
                      :when (not (re-find migration-file-name file-name))]
                  file-name)]
    (when-let [file-name (first (sort ignored))]
      (throw (migration-failure run (str file-name " under " dir " is not a migration ragtime would"
                                         " load: name SQL files NNN-name.up.sql and EDN files"
                                         " NNN-name.edn, or keep it out of the prefix")
                                nil nil)))))

(defn- load-source
  "The migrations under the classpath prefix the host named, refused before any pool
  exists when a file there is not one, when they cannot be read, when there are none,
  when two load under one id, when one has no id, and when one has nothing to run
  (SPEC §7). Only the host's run has a prefix: this library carries its own migrations as
  data, so nothing here is on that path (SPEC §8)."
  [run]
  (let [dir        (:source run)
        migrations (try (refuse-files-ragtime-would-ignore! run)
                        (vec (ragtime-jdbc/load-resources dir))
                        ;; The refusal above is already this library's; everything else
                        ;; listing or reading the prefix throws is the reader's.
                        (catch ExceptionInfo e (throw e))
                        (catch Exception e
                          (throw (migration-failure run (str "the migrations under " dir " could not be loaded")
                                                    nil e))))]
    (when (empty? migrations)
      ;; Zero found is the schema one deploy behind: a misspelt prefix, or a jar built
      ;; without directory entries, which answers nothing for any prefix.
      (throw (migration-failure run (str "no migrations found under the classpath prefix " dir) nil nil)))
    (when (some #(str/blank? (ragtime-protocols/id %)) migrations)
      ;; Several migrations in one EDN file, none of them named: ragtime records the
      ;; first under the empty id and the second collides with it.
      (throw (migration-failure run (str "a migration under " dir " has no id: name SQL files"
                                         " NNN-name.up.sql, and give every migration in an EDN"
                                         " vector its own id")
                                nil nil)))
    (when-let [id (first (sort (keep (fn [[id n]] (when (< 1 n) id))
                                     (frequencies (map ragtime-protocols/id migrations)))))]
      (throw (migration-failure run (str "two migrations under " dir " load under the id " id) id nil)))
    ;; ragtime records such a migration as applied without running anything: an EDN
    ;; function that does not resolve loads as nil, and a lone down file as no statements.
    (when-let [id (first (sort (keep (fn [m] (let [up (:up m)]
                                               (when (or (nil? up)
                                                         (and (coll? up)
                                                              (every? #(and (string? %) (str/blank? %)) up)))
                                                 (ragtime-protocols/id m))))
                                     migrations)))]
      (throw (migration-failure run (str "migration " id " under " dir " has nothing to run up: its up"
                                         " is missing, blank, or names a function that does not"
                                         " exist")
                                id nil)))
    migrations))

(defn- prepared ^PreparedStatement [^Connection c sql params]
  (let [statement (.prepareStatement c ^String sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject statement (int (inc i)) p))
    statement))

(defn- update!
  "Runs one statement and returns how many rows it changed."
  [^DataSource ds sql & params]
  (with-open [c  (.getConnection ds)
              st (prepared c sql params)]
    (.executeUpdate st)))

(defn- first-row
  "The first row as a vector, or nil."
  [^DataSource ds sql & params]
  (with-open [c  (.getConnection ds)
              st (prepared c sql params)
              ^ResultSet rows (.executeQuery st)]
    (when (.next rows)
      (mapv #(.getObject rows (int %)) (range 1 (inc (.getColumnCount (.getMetaData rows))))))))

(defn- readable? [ds table]
  (try (first-row ds (str "SELECT COUNT(*) FROM " table)) true (catch SQLException _ false)))

(defn- recorded-ids
  "What the control table records, read with a `SELECT` of this library's own — never
  through ragtime, whose read creates the table, and creating it belongs under the lock
  (SPEC §7). `nil` when the table is not there, which is not the same as none recorded."
  [^DataSource ds run]
  (try (with-open [c  (.getConnection ds)
                   st (prepared c (str "SELECT id FROM " (:table run)) [])
                   ^ResultSet rows (.executeQuery st)]
         (loop [acc #{}] (if (.next rows) (recur (conj acc (.getString rows 1))) acc)))
       (catch SQLException _ nil)))

(defn- lock-table-ready!
  "Probe, create, probe again. The existence clause some engines offer for `CREATE TABLE`
  is not ANSI — Derby rejects it, and §3's scan will not let src spell it, here or in a
  docstring — and two boots can race for the creation, which this absorbed 200 times out
  of 200 (measured, SPEC §7).

  The second probe is `collision/arbitrate!`'s re-read: a refused creation is answered
  by whether the table is there now, never by the engine's code for the refusal. This is
  where that function's shape was first written, before any host needed it; `readable?`
  answers `false` for a table that is not there, which the function reads as nothing
  found and rethrows. `take-lock-row!` is the same event with the opposite meaning — a
  lost race there means wait, not done — and so does not use it."
  [ds]
  (when-not (readable? ds lock-table)
    (collision/arbitrate!
     #(update! ds (str "CREATE TABLE " lock-table " (id VARCHAR(64) NOT NULL PRIMARY KEY,"
                       " holder VARCHAR(36) NOT NULL, acquired_at BIGINT NOT NULL)"))
     #(readable? ds lock-table))))

(defn- lock-held-elsewhere [run holder acquired-at]
  (let [wait-ms (:wait-ms run)]
    (ex-info (str "db-base: another instance holds the migration lock" (when holder (str ": " holder))
                  (when acquired-at (str ", taken at " acquired-at " (epoch milliseconds)"))
                  ", and " wait-ms " ms of " (pr-str (:wait-blame run)) " were not enough. If that"
                  " instance is gone, the repair is: DELETE FROM " lock-table " WHERE id = '"
                  (:table run) "'")
             (cond-> (merge {:config-key (:wait-blame run) :value wait-ms} (:extra run))
               holder (assoc :holder holder)
               acquired-at (assoc :acquired-at acquired-at)))))

(defn- take-lock-row!
  "`true`, or the exception the INSERT threw. A primary key violation means someone else
  holds it, and its SQLSTATE is not portable — SQLite leaves it null — so what says which
  it was is the row, read when the wait runs out."
  [ds run holder]
  (try (update! ds (str "INSERT INTO " lock-table " (id, holder, acquired_at) VALUES (?, ?, ?)")
                (:table run) holder (System/currentTimeMillis))
       true
       (catch SQLException e e)))

(defn- acquire-lock!
  "Takes the lock, waiting at most `wait-ms`, and returns this boot's holder. A boot that
  cannot take it fails naming the holder, when it took the lock, and the statement that
  repairs one that died (SPEC §7)."
  [ds run]
  (let [holder   (str (random-uuid))
        deadline (+ (System/nanoTime) (* 1000000 (long (:wait-ms run))))]
    (try
      (lock-table-ready! ds)
      (loop []
        (let [taken (take-lock-row! ds run holder)]
          (cond
            (true? taken) holder
            (< (System/nanoTime) deadline) (do (Thread/sleep (long lock-poll-ms)) (recur))
            :else
            (let [[other acquired-at] (first-row ds (str "SELECT holder, acquired_at FROM " lock-table
                                                         " WHERE id = ?")
                                                 (:table run))]
              (cond
                ;; The holder gave it back as the wait ran out.
                (and (nil? other) (true? (take-lock-row! ds run holder))) holder
                ;; No row, and the insert still refuses: it was never another instance.
                (nil? other) (throw taken)
                :else (throw (lock-held-elsewhere run other acquired-at)))))))
      (catch SQLException e
        (throw (migration-failure run (str "the lock table " lock-table
                                           " could not be taken, read or created")
                                  nil e))))))

(defn- release-lock! [ds run holder]
  (update! ds (str "DELETE FROM " lock-table " WHERE id = ? AND holder = ?")
           (:table run) holder))

(defn- release-after-failure! [ds run holder ^Throwable failure]
  (try (release-lock! ds run holder)
       (catch Throwable t
         (when-not (identical? t failure) (.addSuppressed failure t)))))

(defn- plan-migrations
  "The ids to apply, in the source's order. Refuses a recorded id the source no longer
  has, and a pending one that sorts before the last applied. Computed from the recorded
  ids as a set: ragtime reads them ordered by a millisecond timestamp, and ties come
  back in whatever order the engine likes, which ragtime's own check reports as a
  conflict that is not there (measured, SPEC §7)."
  [run recorded migrations]
  (let [recorded (set recorded)
        ids      (mapv ragtime-protocols/id migrations)
        position (zipmap ids (range))
        pending  (vec (remove recorded ids))]
    (when-let [id (first (sort (remove position recorded)))]
      (throw (migration-failure run (str "migration " id " is recorded in " (:table run)
                                         " but not found under " (:source run))
                                id nil)))
    (when-let [last-applied (some->> (seq recorded) (map position) (apply max))]
      (when-let [id (first (filter #(< (position %) last-applied) pending))]
        (throw (migration-failure run (str "migration " id " is new but sorts before "
                                           (nth ids last-applied) ", which is already applied")
                                  id nil))))
    pending))

(defn- apply-pending!
  "Applies what is pending and returns how many it applied. A failure names the
  migration it happened in; everything before it stays applied and recorded, and it
  does not (SPEC §7). A migration that ran but could not then be recorded is reported
  the same way, and the next boot runs it again."
  [ds run migrations]
  (let [store   (ragtime-jdbc/sql-database ds {:migrations-table (:table run)})
        unread  #(migration-failure run (str "the control table " (:table run)
                                             " could not be read or created")
                                    nil %)
        pending (plan-migrations run
                                 (try (vec (ragtime-protocols/applied-migration-ids store))
                                      (catch Exception e (throw (unread e))))
                                 migrations)
        current (atom nil)
        started (atom 0)]
    (try
      (ragtime/migrate-all store {} migrations
                           {:strategy (constantly (mapv #(vector :migrate %) pending))
                            :reporter (fn [_ _ id] (reset! current id) (swap! started inc))})
      @started
      (catch InterruptedException e (throw e))
      (catch Exception e
        (throw (if-let [id @current]
                 (migration-failure run (str "migration " id " failed") id e)
                 (unread e)))))))

(defn- migrate!
  "Under the lock, and only when there is something to do: a boot whose control table
  already records every migration takes no lock, so a crash during an ordinary restart
  leaves no row behind (SPEC §7). A history that disagrees with the source is refused
  here, before the lock, from this library's own read; what is pending is then read again
  under the lock, by ragtime, before anything runs. A lock that cannot be given back is
  a failure of the boot like any other, with the migrations already applied and recorded.
  If the row survived whatever stopped the `DELETE`, it stays: the boots that follow with
  nothing to apply never ask for the lock and never see it, and the first one that has a
  migration to run names it (SPEC §7)."
  [ds run migrations]
  (let [recorded (recorded-ids ds run)]
    (if (and recorded (empty? (plan-migrations run recorded migrations)))
      0
      (let [holder  (acquire-lock! ds run)
            applied (try (apply-pending! ds run migrations)
                         (catch Throwable t
                           (release-after-failure! ds run holder t)
                           (throw t)))]
        (try (release-lock! ds run holder)
             (catch InterruptedException e (throw e))
             (catch Exception e
               ;; Which of the two it left — a row, or a lock table that is itself gone — is
               ;; not knowable from here without another statement that can fail the same way.
               (throw (ex-info (str "db-base: the migration run finished, but this boot's lock row could"
                                    " not be given back. If it is still there, the repair is: DELETE FROM "
                                    lock-table " WHERE id = '" (:table run) "' AND holder = '"
                                    holder "'")
                               (cond-> {:config-key (:blame run) :holder holder}
                                 (contains? run :value) (assoc :value (:value run)))
                               e))))
        applied))))

(defn- delegates-to?
  "Whether `loader` can see what `origin` loaded: `origin` is that loader, one of the
  loaders it delegates to, or the bootstrap. A parent cannot see its child's classes, and
  that asymmetry is the whole case below. Asked by walking the chain rather than by
  loading the name, which §5 forbids src to do and §3's scan enforces."
  [^ClassLoader loader ^ClassLoader origin]
  (or (nil? origin)
      (boolean (some #(identical? origin %)
                     (take-while some? (iterate #(.getParent ^ClassLoader %) loader))))))

(defn- borrow-once!
  "Borrows one connection and gives it back, so a database that cannot be reached
  fails the boot instead of the first request."
  [^HikariDataSource ds timeout-ms]
  (try (with-open [^Connection _ (.getConnection ds)])
       (catch Exception e
         (throw (ex-info (str "db-base: no connection to the database within " timeout-ms " ms")
                         {:config-key [:pool :timeout-ms]} e)))))

(defn start
  "Validates `config`, loads its migrations, opens the pool, borrows one connection
  and applies what is pending before returning, so a database that cannot be reached
  or cannot be migrated fails the boot instead of the first request. Returns
  `{:datasource ds :migrations-applied n}`, where `ds` is a `javax.sql.DataSource` and
  nothing more; with `:migrations :none` the count is absent, not zero.

    :jdbc-url    non-blank string
    :user        string, \"\" included
    :password    string, \"\" included — never defaulted, never generated
    :pool        {:max integer 1..2147483647 :timeout-ms integer 250..2147483646}
    :migrations  :none, or {:dir classpath-prefix :lock-wait-ms integer 0..2147483647}
    :sessions    :none, or {:lock-wait-ms integer 0..2147483647 :dialect :postgresql}
    :libraries   optional: [{:dir classpath-prefix :table control-table
                             :lock-wait-ms integer 0..2147483647} …] (since 0.4.0)

  `:sessions` asks for the table §8's session store keeps, and its migration runs
  **before** the host's, into a control table and under a lock row of this library's own,
  so a host schema may already refer to what it creates. The handle then carries
  `:session-migrations-applied`, which counts that run and never the host's; with
  `:sessions :none` nothing is created and the key is absent. A host that never
  constructs the store has no reason to ask for it. `:dialect` names an engine whose
  session schema differs from the portable one; it is never detected, and the handle's
  `:session-data-max` says what the table holds — an integer, or nil for unbounded — so
  the store refuses a session too long for it before the engine does.

  `:libraries` are the migrations a library ships under a classpath prefix of its own —
  auth-base's tables, say — each run after the session table and before the host's, into
  a control table and under a lock row of its own, so a library's schema is migrated with
  the library and never copied into the host's. The handle then carries
  `:library-migrations-applied`, a map of control table to count. This library names no
  other: the host names the prefix and the table.

  Migration ids sort as strings, so numbers are zero-padded; a `down` never runs.

  Every failure is `ex-info` carrying `:config-key` as a vector path, and
  `:migration-id` when one migration is to blame — a lock failure with a row to name
  carries that row's `:holder`, and one whose wait ran out carries `[:migrations
  :lock-wait-ms]`, the wait as `:value`, `:dir` and `:acquired-at` — except what the
  host's JVM makes HikariCP throw on its own, such as a `hikaricp.configurationFile`
  that cannot be found, read or applied (SPEC §6), and an `Error` or an interrupt,
  which pass through.
  A pool that opened is closed before a failure leaves this function, so an unreachable
  database costs `[:pool :timeout-ms]` to borrow plus, at most, HikariCP's wait for its
  connection-adder on close: the login timeout, which is the timeout plus half a second
  in whole seconds and never less than one. A driver stuck on a socket spends all of
  it. HikariCP keeps the login timeout on `DriverManager`, which the JVM shares, so with
  several pools the wait is the last-constructed pool's."
  [config]
  (validate! config)
  (let [driver (try (DriverManager/getDriver (:jdbc-url config))
                    (catch SQLException e
                      (throw (ex-info "db-base: no JDBC driver on the classpath accepts :jdbc-url"
                                      {:config-key [:jdbc-url]} e))))]
    ;; HikariCP asks `DriverManager` the same question from its own class, and
    ;; `DriverManager` answers by the CALLER's loader: `Class.forName(name, true, callerCL)`
    ;; has to come back as the very class the driver is. A driver added to a running JVM —
    ;; `add-lib` at a REPL — is visible to the loader Clojure compiles this into and not to
    ;; HikariCP's, and the pool's own refusal for that carries the JDBC URL (measured
    ;; 2026-09-20), so it is refused here instead, before anything opens.
    (when-not (delegates-to? (.getClassLoader HikariDataSource)
                             (.getClassLoader ^Class (class driver)))
      (throw (ex-info "db-base: the JDBC driver that accepts :jdbc-url is not one the pool can use"
                      {:config-key [:jdbc-url] :driver (.getName (class driver))}))))
  (let [dir     (get-in config [:migrations :dir])
        run     (when dir (host-run dir (get-in config [:migrations :lock-wait-ms])))
        source  (when dir (load-source run))
        ;; Before the host's, and under a lock row of its own: the host's schema may
        ;; already refer to what this one creates, and the two runs must never be able to
        ;; wait for each other (SPEC §8).
        library (when (map? (:sessions config))
                  (library-run (get-in config [:sessions :lock-wait-ms])
                               (get-in config [:sessions :dialect])))
        sessions (when library (session-schema (:sessions config)))
        ;; Loaded, and refused, before any pool exists, as the host's source is.
        libraries (vec (for [[i entry] (map-indexed vector (:libraries config))
                             :let [lib-run (library-dir-run i entry)]]
                         [lib-run (load-source lib-run)]))
        ds      (open-pool config)]
    (try
      (borrow-once! ds (get-in config [:pool :timeout-ms]))
      (let [session-applied (when library (migrate! ds library (:migrations sessions)))
            ;; After this library's own and before the host's: a host's schema refers to
            ;; a library's tables — `account(subject)` — and never the other way round.
            library-applied (into {} (for [[lib-run source] libraries]
                                       [(:table lib-run) (migrate! ds lib-run source)]))]
        (cond-> {:datasource ds}
          library         (assoc :session-migrations-applied session-applied
                                 :session-data-max (:data-max sessions))
          (seq libraries) (assoc :library-migrations-applied library-applied)
          source          (assoc :migrations-applied (migrate! ds run source))))
      (catch Throwable t
        (close-after-failure! ds t)
        (throw t)))))

(defn stop
  "Closes the pool behind a handle returned by `start`, from a thread that has been
  interrupted too, leaving its interrupt flag as it found it."
  [{:keys [datasource]}]
  (close-uninterrupted! ^HikariDataSource datasource))

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

(defn pool-stats
  "The pool's load at this moment, as `{:active :idle :total :waiting}`: connections
  lent out, connections idle in the pool, the two together, and threads waiting for one
  — for a host's metrics, or for telling an exhausted pool from a slow database.

  HikariCP fills the pool in the background up to `[:pool :max]`, so `:total` grows
  after `start` with nothing borrowed. The numbers are HikariCP's own; this library
  registers no JMX bean, which stays the host's to decide.

  Throws `ex-info` under `:config-key [:datasource]`, before reading anything, for a
  handle whose `:datasource` is not the pool `start` opened, and for one `stop` has
  closed: a closed pool answers zeros, which a dashboard would read as an idle one."
  [handle]
  (let [datasource (:datasource handle)]
    ;; Never echoed, as in `ready?`: a mis-wired caller may pass the configuration map.
    (when-not (instance? HikariDataSource datasource)
      (fail! "pool-stats takes the handle start returned, whose :datasource is the pool it opened"
             [:datasource]))
    (when (.isClosed ^HikariDataSource datasource)
      (fail! "pool-stats: the pool has been stopped" [:datasource]))
    (let [bean (.getHikariPoolMXBean ^HikariDataSource datasource)]
      {:active  (.getActiveConnections bean)
       :idle    (.getIdleConnections bean)
       :total   (.getTotalConnections bean)
       :waiting (.getThreadsAwaitingConnection bean)})))
