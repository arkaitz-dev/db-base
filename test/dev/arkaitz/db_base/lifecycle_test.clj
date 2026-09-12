(ns dev.arkaitz.db-base.lifecycle-test
  "SPEC §6: `start` returns a handle whose datasource is live, `stop` closes what
  `start` opened, and a database that cannot be reached fails the boot within a
  bounded time, with the pool closed and the underlying exception kept.

  Every bound here is derived, not chosen. HikariCP waits the whole
  `connectionTimeout` before failing a borrow, so that is the floor. A failed
  `start` then closes the pool, and `HikariPool.shutdown` awaits its
  connection-adder, without interrupting it, for up to the login timeout,
  `max(1 s, (500 + timeout) / 1000 s)` (PoolBase.java:643, HikariPool.java:219-220).
  How much of that wait is spent depends on where the adder is. On the silent
  socket it is stuck in a read nothing wakes, so the whole second is spent: 2000 ms
  for a 1000 ms timeout, measured at 2009–2016 over two runs, and that floor is what
  proves the pool closed before `start` threw rather than after. On a refused login
  it is only in HikariCP's retry backoff, so less is spent: 1304–1329 ms measured
  for the 1250 ms timeout used below. The 4000 ms ceiling is the silent socket's derived
  2000 plus one such cycle for a cold JVM, which is policy, and it is shared by the
  refused login, which lands far below it; the defects it exists to catch are
  HikariCP's 30 s default and the unbounded constructor, both an order of magnitude
  away. A third borrow from a full pool of two measured 1252–1262 ms at 1250.

  The login timeout lives on `DriverManager`, which the whole JVM shares, so a
  close waits for the one set by the last pool constructed. The runner is
  sequential and every test closes the pool it just built, and the timeouts below
  are kept within the same whole second so none of them moves another's bound.

  Witnesses come before the bound, so a timing red can only mean timing: the pool
  counter proves a pool was built, the socket's accept count proves the driver
  reached the silent server, and the positive control proves the wrong password
  is wrong — a fresh H2 memory database accepts any password by creating itself."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [clojure.lang ExceptionInfo]
           [java.sql Connection DriverManager ResultSet SQLException
            SQLInvalidAuthorizationSpecException SQLTransientConnectionException]
           [com.zaxxer.hikari HikariDataSource]
           [javax.sql DataSource]
           [org.h2.engine SysProperties]))

(defn- h2-memory-url [label]
  (str "jdbc:h2:mem:" label "-" (random-uuid) ";DB_CLOSE_DELAY=-1"))

(defn- query-ints [^Connection c sql]
  (with-open [s (.createStatement c)
              ^ResultSet rs (.executeQuery s sql)]
    (loop [acc []] (if (.next rs) (recur (conj acc (.getLong rs 1))) acc))))

(defn- execute! [^Connection c sql]
  (with-open [s (.createStatement c)] (.execute s sql)))

(deftest start-returns-a-handle-whose-datasource-is-live-and-stop-closes-it--h2
  ;; :max 2 and 1250 ms are HikariCP's default of neither, so a pool that ignored
  ;; either key could not pass by coincidence.
  (let [cfg    {:jdbc-url (h2-memory-url "lifecycle") :user "" :password ""
                :pool {:max 2 :timeout-ms 1250} :migrations :none}
        before (ts/pool-number)
        handle (db/start cfg)
        n      (ts/pool-number)
        ds     (:datasource handle)]
    (is (= (inc (or before 0)) n) "precondition: start constructed exactly one pool")
    (is (= [:datasource] (keys handle)) "the handle is {:datasource ds} and nothing else")
    (is (instance? DataSource ds))
    (is (= [(str "HikariPool-" n ":housekeeper")]
           (filter #(.endsWith ^String % ":housekeeper") (map #(.getName ^Thread %) (ts/hikari-threads n))))
        "precondition: the pool's housekeeper runs, so its absence after stop means something")
    (with-open [c1 (.getConnection ^DataSource ds)]
      (let [c2 (try (.getConnection ^DataSource ds) (catch SQLException e e))]
        ;; Asserted rather than bound by with-open, so a pool that ignored :max reds
        ;; here by name instead of as a timeout escaping the binding.
        (when (is (instance? Connection c2)
                  (str "[:pool :max] 2 reached the pool: a second connection is lent while one is held: "
                       (pr-str c2)))
          (with-open [^Connection c2 c2]
            (execute! c1 "CREATE TABLE lifecycle_probe (n INTEGER)")
            (execute! c1 "INSERT INTO lifecycle_probe VALUES (7)")
            (is (= [7] (query-ints c2 "SELECT n FROM lifecycle_probe"))
                "the datasource reaches a database that stores a row through one connection and returns it through another")
            (testing "with both connections of [:pool :max] 2 held"
              (let [[outcome ms] (ts/elapsed-ms 10000
                                                #(try (.close (.getConnection ^DataSource ds)) ::borrowed
                                                      (catch SQLTransientConnectionException _ ::timed-out)))]
                (is (= ::timed-out outcome) "[:pool :max] reached the pool: a third borrow does not succeed")
                (is (<= 1250 ms 2500)
                    (str "[:pool :timeout-ms] reached the pool and bounds the wait: " ms " ms (floor derived from "
                         "HikariCP's full wait, ceiling one timeout of margin over 1260 measured)"))))))))
    (is (= 2 (.getTotalConnections (.getHikariPoolMXBean ^HikariDataSource ds)))
        "precondition: the pool holds its two connections, so their absence after stop means something")
    (is (nil? (db/stop handle)))
    ;; HikariPool.shutdown empties the bag before it returns. A stop that only hands
    ;; the close to another thread still races this read, but loses it: killed 20 of
    ;; 20 runs when measured.
    (is (= 0 (.getTotalConnections (.getHikariPoolMXBean ^HikariDataSource ds)))
        "stop returned only once the pool had evicted every connection")
    (is (= SQLException (class (ts/thrown-any #(.getConnection ^DataSource ds))))
        "after stop the datasource refuses connections")
    (is (= [] (ts/threads-alive-after-join n #"" 5000))
        (str "after stop no thread of HikariPool-" n " survives"))
    (let [again (db/start cfg)]
      (try
        (with-open [c (.getConnection ^DataSource (:datasource again))]
          (is (= [1] (query-ints c "SELECT count(*) FROM lifecycle_probe"))
              "a stopped component starts again, and the database is still there"))
        (finally (db/stop again))))))

(defn- check-failed-start
  "The part of an unreachable-database failure both scenarios share. `floor-ms` is
  the scenario's derived floor (see the namespace docstring)."
  [label timeout-ms floor-ms before [e ms]]
  (is (not= ::ts/hang e) (str label ": start did not return within 10 s — a hang"))
  (is (instance? ExceptionInfo e) (str label ": start did not throw ex-info: " (pr-str e)))
  (when (instance? ExceptionInfo e)
    (let [n (ts/pool-number)]
      (is (= (inc (or before 0)) n)
          (str label ": precondition: a pool was constructed, so the failure is the pool's to bound"))
      (is (= [(str "db-base: no connection to the database within " timeout-ms " ms")
              {:config-key [:pool :timeout-ms]}]
             [(ex-message e) (ex-data e)])
          label)
      (is (instance? SQLTransientConnectionException (ex-cause e))
          (str label ": the pool's exception is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/leaks-in label e)) (str label ": SPEC §6: a secret was echoed"))
      (is (<= floor-ms ms 4000)
          (str label ": failure lands between the derived floor " floor-ms " ms and the policy ceiling: " ms " ms"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000))
          (str label ": the pool is closed"))
      n)))

(deftest an-unreachable-database-fails-start-within-a-bound-with-the-pool-closed--silent-socket
  (let [{:keys [port accepted close!]} (ts/silent-server)
        cfg    {:jdbc-url (str "jdbc:h2:tcp://127.0.0.1:" port "/mem:" ts/url-sentinel)
                :user ts/user-sentinel :password ts/password-sentinel
                :pool {:max 1 :timeout-ms 1000} :migrations :none}
        before (ts/pool-number)]
    (try
      (let [[e :as outcome] (ts/elapsed-ms 10000 #(ts/thrown (fn [] (db/start cfg))))]
        (is (= 1 (count @accepted))
            (str "witness: the driver reached the silent socket exactly once (one adder, blocked): "
                 (count @accepted)))
        (when (instance? ExceptionInfo e)
          (is (nil? (ex-cause (ex-cause e)))
              "witness: no driver exception exists — the socket stayed silent rather than refusing"))
        ;; 1000 ms of borrow plus the whole login-timeout second the close waits for the
        ;; stuck adder: a close run after start threw could not have cost that second.
        (when-let [n (check-failed-start "silent socket" 1000 2000 before outcome)]
          (is (= [[(str "HikariPool-" n ":connection-adder") true]]
                 (map (juxt #(.getName ^Thread %) #(.isDaemon ^Thread %)) (ts/hikari-threads n)))
              (str "the one thread no pool can interrupt, the adder stuck in the driver, outlives the "
                   "close as a daemon, so it never blocks JVM exit"))))
      (finally (close!)))))

(deftest an-unreachable-database-fails-start-within-a-bound-with-the-driver-exception-kept--wrong-password
  (let [url    (h2-memory-url ts/url-sentinel)
        right  (str ts/password-sentinel "-right")
        cfg    {:jdbc-url url :user ts/user-sentinel :password right
                :pool {:max 1 :timeout-ms 1250} :migrations :none}]
    (with-open [_ (DriverManager/getConnection url ts/user-sentinel right)])
    (is (= ::ts/no-throw (ts/attempt #(db/stop (db/start cfg))))
        "positive control: the right password starts, so the fixture database exists with it")
    ;; Guarded rather than merely asserted: with the delay on, the refused logins below
    ;; would slow whichever test connects next, and that red would not name this cause.
    (when (is (zero? SysProperties/DELAY_WRONG_PASSWORD_MIN)
              (str "precondition: H2's JVM-global brute-force delay is off — run through the :test "
                   "alias, whose :jvm-opts set -Dh2.delayWrongPasswordMin=0"))
      (let [before          (ts/pool-number)
            [e :as outcome] (ts/elapsed-ms 10000 #(ts/thrown (fn [] (db/start (assoc cfg :password
                                                                                     (str ts/password-sentinel "-wrong"))))))]
        (when-let [n (check-failed-start "wrong password" 1250 1250 before outcome)]
          (let [driver (ex-cause (ex-cause e))]
            (is (and (instance? SQLInvalidAuthorizationSpecException driver)
                     (= "28000" (.getSQLState ^SQLException driver)))
                (str "the driver's refusal is kept beneath the pool's exception, as SQLSTATE 28000: "
                     (pr-str driver))))
          (is (= [] (ts/threads-alive-after-join n #"" 5000))
              "nothing is stuck on a refusal: every thread of the pool is gone"))))))
