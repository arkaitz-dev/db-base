(ns dev.arkaitz.db-base.testing-test
  "The namespace a host's tests lean on, tested as a thing that can lie. A reader that
  quietly went through the pool, or a park that quietly never waited, would turn every
  host test built on them into a green over nothing — so each claim below is observed
  from outside the function that makes it: the pool counter, H2's own session table,
  the order two threads really took.

  Every deref carries a hang guard, and an expired one is the named value `::hang`,
  never a criterion. The parks' own `guard-ms` is a hang guard too, except in the one
  test whose subject is what happens when it runs out."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.collision :as collision]
            [dev.arkaitz.db-base.test-support :as ts]
            [dev.arkaitz.db-base.testing :as dbt])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.sql Connection Driver DriverManager DriverPropertyInfo SQLException
            SQLFeatureNotSupportedException]
           [javax.sql DataSource]))

(defn- config [url] (assoc (ts/config url "unused") :migrations :none))

(defn- on-each-engine
  "Calls `f` once per engine with a table `t` of three rows, written through a connection
  of the test's own, and a handle started over it; the handle is stopped however `f`
  ends — a second close of a Hikari pool is harmless."
  [f]
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url "CREATE TABLE t (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(10), data CLOB)")
    (ts/execute! url "INSERT INTO t (id, name, data) VALUES (1, 'a', 'hello')")
    (ts/execute! url "INSERT INTO t (id, name, data) VALUES (2, 'b', '')")
    (ts/execute! url "INSERT INTO t (id, name, data) VALUES (3, 'c', NULL)")
    (let [cfg    (config url)
          handle (db/start cfg)]
      (try (f {:engine engine :url url :cfg cfg :handle handle})
           (finally (db/stop handle))))))

(defn- update-through
  "One statement over a connection borrowed from `ds`, prepared — so a park can see it —
  answering the update count."
  [^DataSource ds sql]
  (with-open [c  (.getConnection ds)
              st (.prepareStatement c ^String sql)]
    (.executeUpdate st)))

(defn- chain-leaks
  "Every leak in `e`, its causes and their suppressed exceptions. Bounded, because a
  cause chain can be made cyclic and a walk that follows one hangs."
  [label e]
  (let [chain (take 32 (take-while some? (iterate ex-cause e)))]
    (vec (mapcat #(ts/leaks-in label %) (concat chain (mapcat #(.getSuppressed ^Throwable %) chain))))))

(defn- starts-with [prefix] (fn [^String sql] (.startsWith sql prefix)))

;; --- rows and one --------------------------------------------------------------

(deftest rows-reads-through-a-connection-of-its-own-and-closes-it-before-returning
  (on-each-engine
   (fn [{:keys [engine url cfg handle]}]
     (is (= [[3]] (ts/query url "SELECT COUNT(*) FROM t"))
         (str engine ": precondition — the fixture wrote its three rows"))
     (db/stop handle)
     (when (= "H2" engine)
       ;; Before any other call: a leaking `rows` leaves its connection open, and a count
       ;; taken after one would red the precondition instead of the invariant.
       (let [sessions #(dbt/rows cfg "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS")]
         (is (= [[1]] (sessions))
             (str "H2: precondition — with the pool stopped, only the counting session is open;"
                  " more means the pool left sessions behind, or `rows` opened a connection"
                  " beside the one it closes"))
         (dotimes [_ 3] (dbt/rows cfg "SELECT id FROM t"))
         (is (= [[1]] (sessions))
             "H2: four calls later, still only the counting session: every call closed its connection")))
     (let [n (ts/pool-number)]
       (is (some? n) (str engine ": precondition — the pool counter is live, so its not moving means something"))
       (is (= [[1] [2] [3]] (dbt/rows cfg "SELECT id FROM t ORDER BY id"))
           (str engine ": read with the pool closed, so the read needs no pool — and, since the"
                " database exists only under the sentinel user, with the configured credentials"))
       (is (= n (ts/pool-number))
           (str engine ": and no pool was constructed to do it"))))))

(deftest rows-answers-what-getObject-answers-except-a-clob-which-is-text-and-one-cuts-it-to-a-value
  (on-each-engine
   (fn [{:keys [engine url cfg]}]
     (let [raw-class (class (ffirst (ts/query url "SELECT data FROM t ORDER BY id")))]
       (is (= ({"H2" org.h2.jdbc.JdbcClob "SQLite" String} engine) raw-class)
           (str engine ": precondition — what the driver itself hands back for the column; on H2 a"
                " Clob, so the branch that reads one is really exercised below")))
     (is (= [["hello"] [""] [nil]] (dbt/rows cfg "SELECT data FROM t ORDER BY id"))
         (str engine ": the column comes back as its text — empty text as empty, NULL as nil"))
     (let [sql   "SELECT COUNT(*), id FROM t WHERE id = 1 GROUP BY id"
           mine  (dbt/rows cfg sql)
           raw   (ts/query url sql)]
       (is (= (mapv class (first raw)) (mapv class (first mine)))
           (str engine ": every other value is the driver's own object — if this reds and the"
                " next does not, the driver moved and not `rows`"))
       (is (= ({"H2" [Long Integer] "SQLite" [Integer Integer]} engine) (mapv class (first mine)))
           (str engine ": a count stays a number, of the class the driver chose"))
       (is (= [[1 1]] mine) (str engine ": and its value")))
     (is (= [[2]] (dbt/rows cfg "SELECT id FROM t WHERE id = ? AND name = ?" 2 "b"))
         (str engine ": parameters bind, in order"))
     (is (= 1 (dbt/one cfg "SELECT id, name FROM t ORDER BY id"))
         (str engine ": one is the first column of the first row"))
     (is (= "b" (dbt/one cfg "SELECT name FROM t WHERE id = ?" 2))
         (str engine ": one binds parameters, choosing a row other than the first"))
     (is (nil? (dbt/one cfg "SELECT id FROM t WHERE id = 99"))
         (str engine ": and nil when there is no row")))))

(deftest rows-and-one-refuse-the-configuration-by-key-and-echo-neither-url-user-nor-password
  (let [url  (ts/h2-memory-url ts/url-sentinel)
        base (config url)
        user-message     "db-base: :user must be a string (\"\" is a value)"
        password-message "db-base: :password must be a string (\"\" is a value)"
        ;; A sentinel in every refused input, the non-map included: a leak check over an
        ;; input with nothing secret in it cannot fail.
        cases [["not a map, carrying the sentinel" (str "jdbc:h2:mem:" ts/url-sentinel)
                ["db-base: rows reads through the configuration map start takes" {:config-key []}]]
               [":jdbc-url missing" (dissoc base :jdbc-url)
                ["db-base: :jdbc-url must be a non-blank string" {:config-key [:jdbc-url]}]]
               [":jdbc-url blank" (assoc base :jdbc-url "  ")
                ["db-base: :jdbc-url must be a non-blank string" {:config-key [:jdbc-url]}]]
               [":user not a string, carrying the sentinel" (assoc base :user [ts/user-sentinel])
                [user-message {:config-key [:user]}]]
               [":password nil" (assoc base :password nil)
                [password-message {:config-key [:password]}]]
               [":password a symbol carrying the sentinel" (assoc base :password (symbol ts/password-sentinel))
                [password-message {:config-key [:password]}]]]
        thrown (for [[label cfg _] cases] [label (ts/thrown #(dbt/rows cfg "SELECT 1"))])]
    (is (= [] (vec (for [[label e] thrown :when (= ::ts/no-throw e)] label)))
        "precondition: every case threw, so every case below was checked for a leak")
    (doseq [[[label _ expected] [_ e]] (map vector cases thrown)
            :when (not= ::ts/no-throw e)]
      (is (= expected [(ex-message e) (ex-data e)]) label)
      (is (= [] (chain-leaks label e)) (str "SPEC §6, the whole chain: " label)))
    (testing "a URL no driver accepts is refused before a driver sees it, and nothing in the chain echoes it"
      (let [e (ts/thrown #(dbt/rows (assoc base :jdbc-url (str "jdbc:nobody://" ts/user-sentinel ":"
                                                               ts/password-sentinel "@h/" ts/url-sentinel))
                                    "SELECT 1"))]
        (is (= ["db-base: no JDBC driver on the classpath accepts :jdbc-url" {:config-key [:jdbc-url]}]
               (ts/pair e)))
        (is (and (instance? SQLException (ex-cause e)) (= "08001" (.getSQLState ^SQLException (ex-cause e))))
            "DriverManager's refusal is the cause")
        (is (= [] (chain-leaks :no-driver e)) "the whole chain, suppressed included")))
    (testing "a driver that accepts the URL and then answers no connection is named, not left to an NPE"
      (let [driver (reify Driver
                     (acceptsURL [_ url] (.startsWith ^String url "jdbc:db-base-nil:"))
                     (connect [_ _ _] nil)
                     (getPropertyInfo [_ _ _] (make-array DriverPropertyInfo 0))
                     (getMajorVersion [_] 1)
                     (getMinorVersion [_] 0)
                     (jdbcCompliant [_] false)
                     (getParentLogger [_] (throw (SQLFeatureNotSupportedException.))))]
        (DriverManager/registerDriver driver)
        (try
          (let [e (ts/thrown #(dbt/rows (assoc base :jdbc-url (str "jdbc:db-base-nil:" ts/url-sentinel))
                                        "SELECT 1"))]
            (is (= ["db-base: the JDBC driver that accepts :jdbc-url returned no connection"
                    {:config-key [:jdbc-url]}]
                   (ts/pair e)))
            (is (= [] (chain-leaks :nil-connection e)) "and echoes nothing"))
          (finally (DriverManager/deregisterDriver driver)))))
    (is (= [user-message {:config-key [:user]}] (ts/attempt #(dbt/one (assoc base :user 42) "SELECT 1")))
        "one goes through the same door")
    (testing "what the driver refuses is the driver's"
      (ts/execute! url "SELECT 1")
      (let [e (ts/thrown-any #(dbt/rows (assoc base :password "wrong") "SELECT 1"))]
        (is (= org.h2.jdbc.JdbcSQLInvalidAuthorizationSpecException (class e))
            "H2's own refusal of a wrong password, not an ex-info of ours")))))

;; --- parking ---------------------------------------------------------------------

(deftest parking-suspends-the-first-accepted-statement-only-and-a-second-caller-runs-through-in-the-window
  (on-each-engine
   (fn [{:keys [engine cfg handle]}]
     (is (= 2 (get-in cfg [:pool :max]))
         (str engine ": precondition — two connections, one for the caller that parks and one for"
              " the caller that runs through; with one, the second waits for the first"))
     ;; The predicate demo-tasks wrote, verbatim: it answers a String, not a boolean, so
     ;; this is also where "truthy" is held to its word.
     (let [{:keys [arrived release! exit] parked :handle} (dbt/parking handle #(re-find #"(?i)^\s*delete" %) 10000)
           ds       (:datasource parked)
           selected (promise)
           [_ a]    (ts/running
                     #(with-open [^Connection c (.getConnection ^DataSource ds)]
                        (with-open [st (.prepareStatement c "SELECT COUNT(*) FROM t")
                                    rs (.executeQuery st)]
                          (.next rs)
                          (deliver selected (.getObject rs 1)))
                        (with-open [st (.prepareStatement c "DELETE FROM t WHERE id = 1")]
                          (.executeUpdate st))))]
       (try
         (is (= "DELETE FROM t WHERE id = 1" (deref arrived 5000 ::hang))
             (str engine ": the park landed on the first ACCEPTED statement, and says which"))
         (is (= 3 (deref selected 0 ::not-yet))
             (str engine ": the unaccepted SELECT before it ran through, and counted all three"
                  " rows — neither DELETE had run"))
         (is (= [[1] [2] [3]] (dbt/rows cfg "SELECT id FROM t ORDER BY id"))
             (str engine ": and the parked DELETE has not run"))
         (is (= 1 (update-through ds "DELETE FROM t WHERE id = 2"))
             (str engine ": a second caller through the same derived handle ran to completion"))
         (is (false? (realized? exit))
             (str engine ": while the first was still parked — a park that ended early did not"
                  " build the window this test needs"))
         (release!)
         (is (= :released (deref exit 5000 ::hang)) (str engine ": the park ended by release"))
         (is (= [:ok 1] (deref a 5000 ::hang)) (str engine ": and the parked DELETE then ran"))
         (is (= [[3]] (dbt/rows cfg "SELECT id FROM t ORDER BY id"))
             (str engine ": both deletes landed"))
         (is (= [nil nil] [(release!) (release!)]) (str engine ": releasing again is harmless"))
         (finally (release!)))))))

(deftest in-flight-runs-the-first-caller-inside-the-seconds-window
  ;; Two autocommit check-then-insert callers: with the first run between the second's
  ;; check and its insert, both insert — the window is the one asked for.
  (on-each-engine
   (fn [{:keys [engine cfg handle]}]
     ;; The second is slow to start, so a first that did not wait for the park would run
     ;; before the second's check; the first records whether that check had happened.
     (let [checked (promise)
           naive (fn [id] (fn [^DataSource ds]
                            (when (zero? (count (dbt/rows cfg "SELECT id FROM t WHERE name = 'x'")))
                              (update-through ds (str "INSERT INTO t (id, name) VALUES (" id ", 'x')")))))
           first! (fn [ds] [(realized? checked) ((naive 8) ds)])
           second! (fn [ds] (Thread/sleep 200) (let [n (count (dbt/rows cfg "SELECT id FROM t WHERE name = 'x'"))]
                                                 (deliver checked n)
                                                 (when (zero? n)
                                                   (update-through ds "INSERT INTO t (id, name) VALUES (9, 'x')"))))
           out   (dbt/in-flight handle #(clojure.string/starts-with? % "INSERT") first! second!)]
       (is (= "INSERT INTO t (id, name) VALUES (9, 'x')" (:arrived out)) (str engine ": the second parked at its insert, after its check"))
       (is (= :released (:exit out)) (str engine ": released, so the window was this one"))
       (is (= [[true 1] 1] [(:first out) (:second out)])
           (str engine ": the first ran after the second's check, and both inserted: " (pr-str [(:first out) (:second out)])))
       (is (= [[8] [9]] (dbt/rows cfg "SELECT id FROM t WHERE name = 'x' ORDER BY id")) (str engine ": two rows where one was meant"))))))

(deftest in-flight-ends-the-park-when-the-first-caller-throws
  (on-each-engine
   (fn [{:keys [engine handle]}]
     (let [second-done (promise)
           thrown (try (dbt/in-flight handle #(clojure.string/starts-with? % "DELETE")
                                      (fn [_] (throw (ex-info "first failed" {})))
                                      (fn [^DataSource ds] (deliver second-done (update-through ds "DELETE FROM t WHERE id = 1")))
                                      10000)
                       (catch clojure.lang.ExceptionInfo e (ex-message e)))]
       (is (= "first failed" thrown) (str engine ": the first's exception leaves"))
       (is (= 1 (deref second-done 5000 ::hang)) (str engine ": and the second was released, never left to its guard"))))))

(deftest a-park-nobody-releases-ends-by-the-guard-and-the-statement-still-runs
  ;; Alone, this would pass over an `await` that returned at once; the test above is the
  ;; one that proves a park waits (`exit` unrealized after the second caller returned).
  (on-each-engine
   (fn [{:keys [engine cfg handle]}]
     (let [{:keys [arrived exit] parked :handle} (dbt/parking handle (starts-with "DELETE") 50)
           [_ a] (ts/running #(update-through (:datasource parked) "DELETE FROM t WHERE id = 3"))]
       (is (= "DELETE FROM t WHERE id = 3" (deref arrived 5000 ::hang)) (str engine ": it parked"))
       (is (= :guard-expired (deref exit 5000 ::hang))
           (str engine ": a park nobody released reports the guard, never :released"))
       (is (= [:ok 1] (deref a 5000 ::hang)) (str engine ": and the statement still ran"))
       (is (= [] (dbt/rows cfg "SELECT id FROM t WHERE id = 3")) (str engine ": to its end"))))))

(deftest prepareCall-parks-too-and-what-the-driver-then-does-with-it-is-the-drivers
  (on-each-engine
   (fn [{:keys [engine url handle]}]
     (let [raw (with-open [c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)]
                 (ts/thrown-any #(.close (.prepareCall c "SELECT 1"))))]
       (is (= ({"H2" ::ts/no-throw "SQLite" SQLException} engine) (if (keyword? raw) raw (class raw)))
           (str engine ": precondition — what the driver itself does with this prepareCall")))
     (let [{:keys [arrived release! exit] parked :handle} (dbt/parking handle (starts-with "SELECT 1") 10000)
           [_ a] (ts/running #(with-open [^Connection c (.getConnection ^DataSource (:datasource parked))
                                          st (.prepareCall c "SELECT 1")
                                          rs (.executeQuery st)]
                                (.next rs)
                                (.getObject rs 1)))]
       (try
         (is (= "SELECT 1" (deref arrived 5000 ::hang)) (str engine ": prepareCall was intercepted"))
         (is (false? (realized? exit)) (str engine ": and is waiting"))
         (release!)
         (is (= :released (deref exit 5000 ::hang)))
         (let [[outcome v] (deref a 5000 [::hang])]
           (if (= "H2" engine)
             (is (= [:ok 1] [outcome v]) "H2: then runs as the driver runs it")
             (is (= [:threw SQLException "SQLite does not support Stored Procedures"]
                    [outcome (class v) (when (instance? Throwable v) (ex-message v))])
                 (str "SQLite: then fails as the driver fails it, with the driver's own class —"
                      " and only after the park, which it could not have reached otherwise"))))
         (finally (release!)))))))

(deftest a-drivers-refusal-at-prepare-crosses-the-derived-handle-as-its-own-class-and-arbitrate!-catches-it
  ;; At prepare and not at execute: a statement's own failure is thrown by the driver's
  ;; statement, which the proxy never wraps, so a duplicate key here would be green with
  ;; the unwrap deleted (measured). A missing table fails at prepare on both engines.
  (on-each-engine
   (fn [{:keys [engine handle]}]
     (let [sql     "INSERT INTO no_such_table (id) VALUES (1)"
           parked  (:handle (dbt/parking handle (constantly false) 1))
           through #(update-through (:datasource parked) sql)
           raw     (class (ts/thrown-any #(update-through (:datasource handle) sql)))]
       (is (= ({"H2" org.h2.jdbc.JdbcSQLSyntaxErrorException "SQLite" org.sqlite.SQLiteException} engine) raw)
           (str engine ": precondition — the driver's own class for this refusal, through the real pool"))
       (is (= raw (class (ts/thrown-any through)))
           (str engine ": the same class through the derived handle, not a reflective wrapper"))
       (is (= :seen (collision/arbitrate! through (constantly :seen)))
           (str engine ": so arbitrate! recognises it as a refused write"))
       (testing "and a refusal of the datasource itself, a borrow from a closed pool, crosses the same way"
         (db/stop handle)
         (let [raw (class (ts/thrown-any #(.getConnection ^DataSource (:datasource handle))))]
           (is (= SQLException raw)
               (str engine ": precondition — the closed pool's own refusal, through the real pool"))
           (is (= raw (class (ts/thrown-any #(.getConnection ^DataSource (:datasource parked)))))
               (str engine ": the same class through the derived handle"))))))))

(deftest stop-on-the-derived-handle-throws-and-leaves-the-pool-open-and-stop-on-the-original-closes-it
  (on-each-engine
   (fn [{:keys [engine handle]}]
     (let [parked (:handle (dbt/parking handle (constantly false) 1))
           pool   ^HikariDataSource (:datasource handle)]
       (is (= ClassCastException (class (ts/thrown-any #(db/stop parked))))
           (str engine ": stop cannot close a datasource that is not the pool, and says so by throwing"))
       (is (false? (.isClosed pool)) (str engine ": and the pool behind it is still open"))
       (db/stop handle)
       (is (true? (.isClosed pool)) (str engine ": until the handle that was passed in is stopped"))))))

(deftest parking-refuses-its-arguments-by-name-and-echoes-nothing
  (on-each-engine
   (fn [{:keys [engine cfg handle]}]
     (let [e (ts/thrown #(dbt/parking {:datasource cfg} identity 1))]
       (is (= ["db-base: parking takes the handle start returned, whose :datasource is a javax.sql.DataSource"
               {:config-key [:datasource]}]
              (ts/pair e))
           (str engine ": a configuration map where the handle belongs"))
       (is (= [] (ts/leaks-in :config-as-handle e)) (str engine ": SPEC §6, and it is not echoed")))
     (doseq [accepts? [#"DELETE" "DELETE"]]
       (is (= ["db-base: parking takes a predicate of the statement's SQL" {:config-key [:accepts?]}]
              (ts/attempt #(dbt/parking handle accepts? 1)))
           (str engine ": refused as a predicate: " (pr-str accepts?))))
     (doseq [g [0 1.0 "5" 2147483648]]
       (is (= ["db-base: guard-ms must be an integer from 1 to 2147483647" {:config-key [:guard-ms] :value g}]
              (ts/attempt #(dbt/parking handle (constantly false) g)))
           (str engine ": guard-ms refused: " (pr-str g))))
     (is (= #{:handle :arrived :release! :exit} (set (keys (dbt/parking handle #{"DELETE FROM t"} 1))))
         (str engine ": a set is a predicate, as `accepts?` says — anything callable is"))
     (doseq [g [1 1N 2147483647]]
       (let [p (dbt/parking handle (constantly false) g)]
         (is (= #{:handle :arrived :release! :exit} (set (keys p)))
             (str engine ": guard-ms accepted, of any integer type as everywhere in this library: " (pr-str g))))))))

(deftest an-interrupt-ends-the-park-as-interrupted-sets-the-flag-again-and-the-statement-still-runs
  (on-each-engine
   (fn [{:keys [engine cfg handle]}]
     (let [{:keys [arrived release! exit] parked :handle} (dbt/parking handle (starts-with "DELETE") 10000)
           [thread a] (ts/running
                       #(with-open [^Connection c (.getConnection ^DataSource (:datasource parked))
                                    st (.prepareStatement c "DELETE FROM t WHERE id = 3")]
                          ;; Read and cleared here, on the parked thread, so the execute
                          ;; below does not run under the flag the park set again.
                          (let [flag (Thread/interrupted)]
                            [flag (.executeUpdate st)])))]
       (try
         (is (= "DELETE FROM t WHERE id = 3" (deref arrived 5000 ::hang)) (str engine ": it parked"))
         (.interrupt ^Thread thread)
         (is (= :interrupted (deref exit 5000 ::hang))
             (str engine ": the interrupt ended the park, and :exit says so"))
         (is (= [:ok [true 1]] (deref a 5000 ::hang))
             (str engine ": the flag was set again when prepare returned, and the statement ran"))
         (is (= [] (dbt/rows cfg "SELECT id FROM t WHERE id = 3")) (str engine ": to its end"))
         (finally (release!)))))))

(deftest sessions-and-session-read-the-session-table-through-a-connection-of-their-own
  (doseq [[engine url] (ts/engines)]
    (let [cfg    (assoc (config url) :sessions {:lock-wait-ms 1000})
          handle (db/start cfg)]
      ;; Rows written by statements of the test's own, the greater id first, so id order is
      ;; never insertion order — an engine answering in insertion order without an ORDER BY
      ;; would otherwise pass half the time — and one of them already expired.
      (ts/execute! url "INSERT INTO db_base_sessions (id, data, expires_at) VALUES ('s-2', '{:visitor \"bo\"}', 9999999999999)")
      (ts/execute! url "INSERT INTO db_base_sessions (id, data, expires_at) VALUES ('s-1', '{:visitor \"ada\"}', 1)")
      (db/stop handle)
      (is (= [{:id "s-1" :data "{:visitor \"ada\"}" :expires-at 1}
              {:id "s-2" :data "{:visitor \"bo\"}" :expires-at 9999999999999}]
             (dbt/sessions cfg))
          (str engine ": every row, ordered by id, the expired one included, the EDN as written —"
               " read with the pool already closed, so through a connection of its own"))
      (is (= {:id "s-2" :data "{:visitor \"bo\"}" :expires-at 9999999999999} (dbt/session cfg "s-2"))
          (str engine ": one row by its id"))
      (is (nil? (dbt/session cfg "no-such-session")) (str engine ": and nil for an id with no row")))))
