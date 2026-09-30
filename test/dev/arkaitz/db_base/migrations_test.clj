(ns dev.arkaitz.db-base.migrations-test
  "SPEC §7: `start` finds the migrations under a classpath prefix, applies what is
  pending in the source's order, records it in `ragtime_migrations`, and stops the boot
  — with the pool closed — when the source cannot serve, when a migration fails, or when
  the recorded history and the source disagree.

  Every claim that does not depend on the engine runs on H2 and on SQLite, a strict
  engine next to a permissive one (§3). The fixtures live under
  test/resources/db-base-test and speak ANSI SQL. They are built so that order shows:
  002 adds the column 003 writes, so applying them the other way round fails instead of
  leaving the same rows. The lock is not here: it arrives with its own concurrency test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [db-base-test.migration-fns :as fns]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts]
            [next.jdbc :as jdbc]
            [ragtime.core :as ragtime]
            [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.protocols :as ragtime-protocols]
            [ragtime.strategy :as strategy]
            [resauce.core :as resauce])
  (:import [clojure.lang ExceptionInfo]
           [java.io FileNotFoundException]
           [java.sql Connection DriverManager ResultSet SQLException]))

(defn- recorded [url] (mapv first (ts/query url "SELECT id FROM ragtime_migrations ORDER BY id")))

(defn- refused [prefix message & [migration-id]]
  [(str "db-base: " message)
   (cond-> {:config-key [:migrations :dir] :value (str "db-base-test/" prefix)}
     migration-id (assoc :migration-id migration-id))])

(defn- bounded
  "What `f` answers, or `::ts/hang` after 15 s. A hang guard only — a wait for the lock
  that lost its deadline would otherwise stop the suite instead of failing it — and no
  claim below depends on the number."
  [f]
  (first (ts/elapsed-ms 15000 f)))

(defn- waiting-for-the-lock?
  "Whether that thread is inside the wait for the lock, read from its own stack: the
  overlap is observed, not assumed."
  [^Thread thread]
  (boolean (some #(str/starts-with? (.getClassName ^StackTraceElement %) "dev.arkaitz.db_base$acquire_lock")
                 (.getStackTrace thread))))

(defn- wait-until
  "Polls `f` until it answers, or gives up after `ms`. A guard, never a criterion."
  [ms f]
  (let [deadline (+ (System/nanoTime) (* 1000000 (long ms)))]
    (loop []
      (cond (f) true
            (< (System/nanoTime) deadline) (do (Thread/sleep 20) (recur))
            :else false))))

(defn- store [url]
  (ragtime-jdbc/sql-database (jdbc/get-datasource {:jdbcUrl url :user ts/user-sentinel
                                                   :password ts/password-sentinel})
                             {:migrations-table "ragtime_migrations"}))

(deftest a-source-that-cannot-serve-migrations-is-refused-before-any-pool-is-constructed
  (is (= [true true true false]
         [(boolean (seq (resauce/resource-dir "db-base-test/empty")))
          (boolean (seq (resauce/resource-dir "db-base-test/uppercase")))
          (boolean (seq (resauce/resource-dir "db-base-test/only-a-subdirectory")))
          (boolean (seq (resauce/resource-dir "db-base-test/thre")))])
      "precondition: the prefixes that must exist are on the classpath and the misspelt one is not")
  (is (and (find-ns 'db-base-test.migration-fns) (nil? (resolve 'db-base-test.migration-fns/no-such-fn)))
      "precondition: the missing function's namespace exists and the function does not")
  (is (= 2 (count (resauce/resource-dir "db-base-test/two-roots")))
      "precondition: two classpath roots carry that prefix, which is how two jars reach a host")
  (let [url (ts/h2-memory-url ts/url-sentinel)]
    (let [before (ts/pool-number)]
      (db/stop (db/start (ts/config url "three")))
      (is (= (inc (or before 0)) (ts/pool-number))
          "positive control: a source that can serve constructs a pool, so an unmoved counter below means something"))
    (doseq [[label prefix expected cause?]
            [["two files ragtime would pass over: the first in order is the one named" "empty"
              (refused "empty" (str "README.md under db-base-test/empty is not a migration ragtime would"
                                    " load: name SQL files NNN-name.up.sql and EDN files NNN-name.edn,"
                                    " or keep it out of the prefix"))
              nil?]
             ["an up file whose extension is upper case" "uppercase"
              (refused "uppercase" (str "001-a.up.SQL under db-base-test/uppercase is not a migration"
                                        " ragtime would load: name SQL files NNN-name.up.sql and EDN"
                                        " files NNN-name.edn, or keep it out of the prefix"))
              nil?]
             ["an SQL file with neither up nor down in its name" "misnamed"
              (refused "misnamed" (str "001-a.sql under db-base-test/misnamed is not a migration ragtime"
                                       " would load: name SQL files NNN-name.up.sql and EDN files"
                                       " NNN-name.edn, or keep it out of the prefix"))
              nil?]
             ["a prefix whose only child is a subdirectory" "only-a-subdirectory"
              (refused "only-a-subdirectory"
                       "no migrations found under the classpath prefix db-base-test/only-a-subdirectory")
              nil?]
             ["a misspelt prefix" "thre"
              (refused "thre" "no migrations found under the classpath prefix db-base-test/thre") nil?]
             ["two migrations loading under one id, and two ids doing it" "duplicate"
              (refused "duplicate" "two migrations under db-base-test/duplicate load under the id 001-a" "001-a")
              nil?]
             ["an EDN migration whose id is blank" "blank-id"
              (refused "blank-id" (str "a migration under db-base-test/blank-id has no id: name SQL files"
                                       " NNN-name.up.sql, and give every migration in an EDN vector its own id"))
              nil?]
             ["several migrations in one EDN file, none of them named" "no-id"
              (refused "no-id" (str "a migration under db-base-test/no-id has no id: name SQL files"
                                    " NNN-name.up.sql, and give every migration in an EDN vector its own id"))
              nil?]
             ["an EDN migration that does not parse" "malformed"
              (refused "malformed" "the migrations under db-base-test/malformed could not be loaded")
              #(and (instance? RuntimeException %) (.contains (str (ex-message %)) "EOF while reading"))]
             ["an EDN migration naming a namespace that does not exist" "unresolvable"
              (refused "unresolvable" "the migrations under db-base-test/unresolvable could not be loaded")
              #(instance? FileNotFoundException %)]
             ["an EDN migration naming a function that does not exist" "missing-fn"
              (refused "missing-fn" (str "migration 001-a under db-base-test/missing-fn has nothing to run"
                                         " up: its up is missing, blank, or names a function that does not"
                                         " exist")
                       "001-a")
              nil?]
             ["two classpath roots carrying one migration name" "two-roots"
              (refused "two-roots" "two migrations under db-base-test/two-roots load under the id 001-a" "001-a")
              nil?]
             ["a down file with no up" "down-only"
              (refused "down-only" (str "migration 001-a under db-base-test/down-only has nothing to run"
                                        " up: its up is missing, blank, or names a function that does not"
                                        " exist")
                       "001-a")
              nil?]
             ["an up file with nothing but a blank line, which H2 would run without a word" "blank-up"
              (refused "blank-up" (str "migration 001-a under db-base-test/blank-up has nothing to run"
                                       " up: its up is missing, blank, or names a function that does not"
                                       " exist")
                       "001-a")
              nil?]]]
      (let [before (ts/pool-number)
            e      (ts/thrown #(db/start (ts/config url prefix)))]
        (is (instance? ExceptionInfo e) (str label ": start did not refuse: " (pr-str e)))
        (is (= expected (ts/pair e)) label)
        (is (cause? (ex-cause e)) (str label ": cause " (pr-str (ex-cause e))))
        (is (= before (ts/pool-number)) (str label ": refused before any pool was constructed"))
        (is (= [] (ts/leaks-in label e)) (str label ": SPEC §6: a secret was echoed"))))))

(deftest start-applies-the-pending-migrations-in-source-order-and-a-second-boot-applies-none
  (is (some? (io/resource "db-base-test/three/sub/009-z.up.sql"))
      "precondition: a migration file sits in a subdirectory of the prefix, where it must not count")
  (doseq [[engine url] (ts/engines)]
    (let [handle (db/start (ts/config url "three"))]
      (try
        (is (= [[:datasource :migrations-applied] 3] [(sort (keys handle)) (:migrations-applied handle)])
            (str engine ": the first boot's handle carries the count it applied"))
        (finally (db/stop handle))))
    (is (= ["001-a" "002-b" "003-c"] (recorded url))
        (str engine ": ragtime_migrations records the three direct children, not the one in a subdirectory"))
    (is (= [[7 "seven"]] (ts/query url "SELECT n, label FROM m_probe"))
        (str engine ": they ran in the source's order: the table, then its column, then the row that needs it"))
    (is (= {:migrations-applied 0} (ts/boot (ts/config url "three"))) (str engine ": a second boot applies nothing"))
    (is (= [["001-a" "002-b" "003-c"] [[7 "seven"]]] [(recorded url) (ts/query url "SELECT n, label FROM m_probe")])
        (str engine ": and runs nothing again — m_probe has no key, so a rerun would add a row, not fail"))))

(deftest migration-ids-are-applied-in-the-order-of-their-names-as-strings
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 2} (ts/boot (ts/config url "string-order")))
        (str engine ": 10-b creates the table 9-a writes to, which is the string order, not the numeric one"))
    (is (= [["10-b" "9-a"] [[9]]] [(recorded url) (ts/query url "SELECT n FROM m_probe")]) engine)))

(deftest a-migration-that-fails-leaves-the-ones-before-it-recorded-names-itself-and-closes-the-pool
  (doseq [[engine url] (ts/engines)]
    (let [before (ts/pool-number)
          e      (ts/thrown #(db/start (ts/config url "failing")))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) (str engine ": precondition: a pool was constructed, so its closing means something"))
      (is (= (refused "failing" "migration 002-b failed" "002-b") (ts/pair e)) engine)
      (is (instance? SQLException (ex-cause e)) (str engine ": the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": the pool is closed"))
      (is (= [["001-a"] []] [(recorded url) (ts/query url "SELECT n FROM m_probe")])
          (str engine ": 001 is applied and recorded, 002 is not recorded, and nothing after it ran"))
      (is (= [true false] [(contains? (ts/tables url) "m_probe") (contains? (ts/tables url) "m_down_ran")])
          (str engine ": 002's down was not run to unwind it — its down file would leave a table behind"))
      (is (= [] (ts/lock-rows url)) (str engine ": and the lock is given back after the failure")))
    (is (= {:migrations-applied 2} (ts/boot (ts/config url "fixed")))
        (str engine ": the next boot, on a fixed 002, attempts 002 again and then 003"))
    (is (= [["001-a" "002-b" "003-c"] [[8 "eight"]]] [(recorded url) (ts/query url "SELECT n, label FROM m_probe")])
        engine))
  (testing "an Error from a migration is not wrapped, and the pool is still closed"
    (reset! fns/thrown-error nil)
    (let [url    (ts/h2-memory-url ts/url-sentinel)
          before (ts/pool-number)
          r      (try (let [h (db/start (ts/config url "erroring"))] (db/stop h) ::no-throw)
                      (catch Throwable t t))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) "precondition: a pool was constructed")
      (is (and (some? @fns/thrown-error) (identical? @fns/thrown-error r))
          (str "the migration's own Error arrives unchanged: " (pr-str r)))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) "the pool is closed")
      (is (= [] (ts/lock-rows url)) "and the lock is given back, even after an Error")))
  (testing "a migration that runs and cannot then be recorded names itself, and the next boot runs it again"
    ;; H2 only: SQLite cannot add a constraint to a table that already exists.
    (let [url (ts/h2-memory-url ts/url-sentinel)
          e   (ts/thrown #(db/start (ts/config url "unrecordable")))
          n   (ts/pool-number)]
      (is (= (refused "unrecordable" "migration 002-b failed" "002-b") (ts/pair e)) "the failure names the migration")
      (is (instance? SQLException (ex-cause e)) (str "the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) "the pool is closed")
      (is (= [["001-a"] true] [(recorded url) (contains? (ts/tables url) "m_ran")])
          "002 ran — it left its table — and is not recorded, which is what leaves it pending")
      (is (= [] (ts/lock-rows url)) "and the lock is given back")))
  (testing "an interrupt reaches the boot between two migrations and passes through, pool closed"
    (let [url    (ts/h2-memory-url ts/url-sentinel)
          before (ts/pool-number)
          r      (try (let [h (db/start (ts/config url "interrupting"))] (db/stop h) ::no-throw)
                      (catch Throwable t t))
          n      (ts/pool-number)
          ;; Cleared here, and reported, so the flag cannot reach the next test.
          flag   (Thread/interrupted)]
      (is (= (inc (or before 0)) n) "precondition: a pool was constructed")
      (is (instance? InterruptedException r) (str "the interrupt arrives unwrapped: " (pr-str r)))
      (is (true? flag) "and the flag it arrived with is still set for the caller")
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000))
          "the pool is closed even though the closing thread was interrupted")
      (is (= [["001-a"] false] [(recorded url) (contains? (ts/tables url) "m_probe")])
          "001 stayed applied and recorded, and 002, which would create a table, never ran")
      (is (= [] (ts/lock-rows url))
          "and the lock is given back by a thread that was interrupted while it held it"))))

(deftest a-trailing-separator-leaves-nothing-behind-and-a-blank-statement-beside-a-real-one-still-runs
  (is (= [["001-a" ["CREATE TABLE m_probe (n INTEGER)"]]]
         (mapv (juxt ragtime-protocols/id :up) (ragtime-jdbc/load-resources "db-base-test/half-blank")))
      (str "witness: an up file ending in a separator loads as one statement — ragtime's split drops the "
           "empty tail — so it is not the case that distinguishes 'every statement is blank' from 'any is'"))
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 1} (ts/boot (ts/config url "half-blank")))
        (str engine ": and the migration runs"))
    (is (= [["001-a"] true] [(recorded url) (contains? (ts/tables url) "m_probe")]) engine))
  (testing "a blank statement beside a real one is still a migration to run"
    ;; H2 only: it runs a blank statement without a word, where SQLite refuses it. The
    ;; claim here is this library's — that such a migration is not 'nothing to run' — and
    ;; what the engine then does with the blank one is the engine's.
    (let [url (ts/h2-memory-url ts/url-sentinel)]
      (is (= [["001-a" ["CREATE TABLE m_probe (n INTEGER)" "   "]]]
             (mapv (juxt ragtime-protocols/id :up) (ragtime-jdbc/load-resources "db-base-test/blank-among")))
          "witness: the fixture really holds a blank statement beside a real one")
      (is (= {:migrations-applied 1} (ts/boot (ts/config url "blank-among"))) "it is accepted and applied")
      (is (= [["001-a"] true] [(recorded url) (contains? (ts/tables url) "m_probe")])
          "and the real statement ran"))))

(deftest a-recorded-migration-the-source-lacks-or-a-new-one-sorting-before-the-last-applied-stops-the-boot
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 3} (ts/boot (ts/config url "three"))) (str engine ": precondition: three are applied"))
    (doseq [[label prefix expected]
            [["a recorded migration the source no longer has" "missing-one"
              (refused "missing-one" (str "migration 002-b is recorded in ragtime_migrations but not found"
                                          " under db-base-test/missing-one")
                       "002-b")]
             ["a new migration sorting before every applied one" "out-of-order"
              (refused "out-of-order" "migration 000-z is new but sorts before 003-c, which is already applied"
                       "000-z")]
             ["a new migration sorting between two applied ones" "between"
              (refused "between" "migration 002a-x is new but sorts before 003-c, which is already applied"
                       "002a-x")]]]
      (let [before (ts/pool-number)
            e      (ts/thrown #(db/start (ts/config url prefix)))
            n      (ts/pool-number)]
        (is (= (inc (or before 0)) n) (str engine ": " label ": precondition: a pool was constructed"))
        (is (= expected (ts/pair e)) (str engine ": " label))
        (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": " label ": the pool is closed"))
        (is (= [["001-a" "002-b" "003-c"] [[7 "seven"]]] [(recorded url) (ts/query url "SELECT n, label FROM m_probe")])
            (str engine ": " label ": nothing was applied or recorded, not even the migration sorting last"))))))

(deftest recorded-ids-that-share-a-timestamp-and-come-back-out-of-order-are-not-a-conflict
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url "CREATE TABLE ragtime_migrations (id VARCHAR(255) PRIMARY KEY, created_at VARCHAR(32))")
    (doseq [id ["003-c" "001-a" "002-b"]]
      (ts/execute! url (str "INSERT INTO ragtime_migrations (id, created_at) VALUES ('" id "', '2026-09-14T10:00:00.000')")))
    (let [applied (vec (ragtime-protocols/applied-migration-ids (store url)))]
      (is (= ["003-c" "001-a" "002-b"] applied)
          (str engine ": witness: the engine returns the tied rows in insertion order, not sorted, so there is a disorder to survive"))
      (is (= ::strategy/migration-conflict
             (:reason (ex-data (ts/thrown #(doall (strategy/raise-error applied ["001-a" "002-b" "003-c"]))))))
          (str engine ": positive control: ragtime's own check reads that order as a conflict")))
    (is (= {:migrations-applied 0} (ts/boot (ts/config url "three")))
        (str engine ": start reads it as three applied migrations and applies nothing"))
    (is (= [true false] [(contains? (ts/tables url) "ragtime_migrations") (contains? (ts/tables url) "m_probe")])
        (str engine ": and ran none of them — the table the first would create is absent, and the check that says so sees the control table"))
    (is (= (refused "between" "migration 002a-x is new but sorts before 003-c, which is already applied" "002a-x")
           (ts/pair (ts/thrown #(db/start (ts/config url "between")))))
        (str engine ": a pending migration between two applied ones is still refused, from the same disordered history"))))

(deftest a-down-never-runs-and-an-edn-function-runs-with-the-handles-datasource
  (doseq [[engine url] (ts/engines)]
    (reset! fns/up-calls [])
    (reset! fns/down-calls 0)
    (let [handle (db/start (ts/config url "fns"))]
      (try
        (is (= [1 [(:datasource handle)] 0]
               [(:migrations-applied handle) @fns/up-calls @fns/down-calls])
            (str engine ": up ran once, handed the handle's own datasource, and down did not run"))
        (finally (db/stop handle))))
    (is (= {:migrations-applied 0} (ts/boot (ts/config url "fns"))) (str engine ": a second boot applies nothing"))
    (is (= [1 0] [(count @fns/up-calls) @fns/down-calls]) (str engine ": and runs neither up nor down again"))
    (ragtime/rollback-last (store url) (ragtime/into-index (ragtime-jdbc/load-resources "db-base-test/fns")))
    (is (= [1 []] [@fns/down-calls (recorded url)])
        (str engine ": positive control: down is reachable, and ragtime's rollback runs it"))))

(deftest migrations-none-leaves-the-count-absent-and-creates-no-control-table
  (doseq [[engine url] (ts/engines)]
    (is (= {} (ts/boot (assoc (ts/config url "three") :migrations :none))) (str engine ": the handle has no count"))
    (is (not (contains? (ts/tables url) "ragtime_migrations")) (str engine ": :none created no control table"))
    (ts/boot (ts/config url "three"))
    (is (contains? (ts/tables url) "ragtime_migrations")
        (str engine ": positive control: a boot with migrations creates the table the check looks for"))))

(deftest a-control-table-that-cannot-be-read-stops-the-boot-with-the-pool-closed
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url "CREATE TABLE ragtime_migrations (x INTEGER)")
    (let [before (ts/pool-number)
          e      (ts/thrown #(db/start (ts/config url "three")))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) (str engine ": precondition: a pool was constructed"))
      (is (= (refused "three" "the control table ragtime_migrations could not be read or created") (ts/pair e)) engine)
      (is (instance? SQLException (ex-cause e)) (str engine ": the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": the pool is closed"))
      (is (= [true false] [(contains? (ts/tables url) "ragtime_migrations") (contains? (ts/tables url) "m_probe")])
          (str engine ": no migration ran, and the check that says so sees the table that is there"))
      (is (= [] (ts/lock-rows url)) (str engine ": and the lock is given back")))))

(deftest an-up-split-across-numbered-files-is-one-migration-and-a-down-beside-it-is-accepted
  (is (some? (io/resource "db-base-test/numbered/001-a.down.sql"))
      "precondition: a down file sits beside them, so accepting it is a claim the file check can break")
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 1} (ts/boot (ts/config url "numbered")))
        (str engine ": the two numbered up files are one migration, not two"))
    (is (= [["001-a"] [[1]]] [(recorded url) (ts/query url "SELECT n FROM m_probe")])
        (str engine ": recorded once, and both of its statements ran, the table before the row"))))

(deftest a-prefix-that-cannot-be-listed-is-refused-as-ex-info-naming-it
  (let [previous (.getContextClassLoader (Thread/currentThread))
        ;; resauce asks the context class loader and then dispatches on each URL's scheme;
        ;; it has no method for this one, so listing throws where reading would not.
        loader   (proxy [ClassLoader] []
                   (getResources [_]
                     (java.util.Collections/enumeration
                      [(java.net.URL. "ftp://example.invalid/db-base-test/three/")])))]
    (try
      (.setContextClassLoader (Thread/currentThread) loader)
      (let [before (ts/pool-number)
            e      (ts/thrown #(db/start (ts/config (ts/h2-memory-url ts/url-sentinel) "three")))]
        (is (= (refused "three" "the migrations under db-base-test/three could not be loaded") (ts/pair e))
            "a prefix that cannot be listed is this library's refusal, not a stray exception")
        (is (instance? IllegalArgumentException (ex-cause e))
            (str "the reader's own failure is the cause: " (pr-str (ex-cause e))))
        (is (= before (ts/pool-number)) "and no pool was constructed"))
      (finally (.setContextClassLoader (Thread/currentThread) previous)))))

(deftest two-boots-that-overlap-migrate-once-and-the-second-waits-its-turn
  (doseq [[engine url] (ts/engines)]
    (fns/reset-gate!)
    (let [t0            (System/currentTimeMillis)
          [_ first-run] (ts/running #(ts/boot (ts/config url "gated")))]
      (is (= true (deref @fns/arrived 20000 ::never))
          (str engine ": precondition: the first boot is inside a migration, holding the lock"))
      ;; Its wait is wide because the test spends the time before `release`: a third boot,
      ;; a pool close and its assertions. Nothing here asserts a duration — what proves it
      ;; waited is its own stack, and what proves the wait is bounded is the third boot.
      (let [waiting (assoc-in (ts/config url "gated") [:migrations :lock-wait-ms] 20000)
            [second-thread second-run] (ts/running #(ts/boot waiting))]
        (is (wait-until 20000 #(waiting-for-the-lock? second-thread))
            (str engine ": precondition: the second boot is waiting for the lock, not migrating"))
        (is (= 1 (count (ts/lock-rows url)))
            (str engine ": precondition: one lock row exists while both boots are running"))
        (testing "a third boot that will not wait at all"
          (let [[_ holder at] (first (ts/lock-rows url))
                before        (ts/pool-number)
                e             (bounded #(ts/thrown (fn [] (db/start (assoc-in (ts/config url "gated")
                                                                              [:migrations :lock-wait-ms] 0)))))
                t1            (System/currentTimeMillis)
                n             (ts/pool-number)]
            (is (= (inc (or before 0)) n) (str engine ": precondition: it did construct a pool"))
            (is (= [(str "db-base: another instance holds the migration lock: " holder ", taken at " at
                         " (epoch milliseconds), and 0 ms of [:migrations :lock-wait-ms] were not enough."
                         " If that instance is gone, the repair is: DELETE FROM db_base_migration_lock"
                         " WHERE id = 'ragtime_migrations'")
                    {:config-key [:migrations :lock-wait-ms] :value 0 :dir "db-base-test/gated"
                     :holder holder :acquired-at at}]
                   (ts/pair e))
                (str engine ": it names the holder, when it took the lock, and the repair"))
            (is (<= t0 (long at) t1) (str engine ": the recorded instant is the first boot's own: " at))
            (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
            (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": its pool is closed"))
            (is (= [["ragtime_migrations" holder at]] (ts/lock-rows url))
                (str engine ": and it left the lock row alone, holder and instant included"))))
        (deliver @fns/release true)
        (is (= [[:ok {:migrations-applied 3}] [:ok {:migrations-applied 0}]]
               [(deref first-run 30000 ::hang) (deref second-run 30000 ::hang)])
            (str engine ": the first applied the three, the second waited and then found nothing to do"))
        (is (= [1 true] [@fns/gate-calls (true? @fns/gate-exit)])
            (str engine ": the gated migration ran once, and the test released it rather than its own bound"))
        (is (= [["001-a" "002-b" "003-c"] [[7 "seven"]] []]
               [(recorded url) (ts/query url "SELECT n, label FROM m_probe") (ts/lock-rows url)])
            (str engine ": recorded once, written once, and the lock given back"))))))

(deftest a-lock-row-left-by-a-holder-that-died-stops-every-boot-until-the-repair-it-names
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url (str "CREATE TABLE db_base_migration_lock (id VARCHAR(64) NOT NULL PRIMARY KEY,"
                       " holder VARCHAR(36) NOT NULL, acquired_at BIGINT NOT NULL)"))
    (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                       " VALUES ('ragtime_migrations', 'DEAD-HOLDER-7f3a', 1700000000000)"))
    (let [cfg      (assoc-in (ts/config url "three") [:migrations :lock-wait-ms] 200)
          expected [(str "db-base: another instance holds the migration lock: DEAD-HOLDER-7f3a, taken at"
                         " 1700000000000 (epoch milliseconds), and 200 ms of [:migrations :lock-wait-ms]"
                         " were not enough. If that instance is gone, the repair is: DELETE FROM"
                         " db_base_migration_lock WHERE id = 'ragtime_migrations'")
                    {:config-key [:migrations :lock-wait-ms] :value 200 :dir "db-base-test/three"
                     :holder "DEAD-HOLDER-7f3a" :acquired-at 1700000000000}]]
      (doseq [attempt ["the first boot after it died" "the boot after that"]]
        (let [before (ts/pool-number)
              e      (bounded #(ts/thrown (fn [] (db/start cfg))))
              n      (ts/pool-number)]
          (is (= (inc (or before 0)) n) (str engine ": " attempt ": precondition: a pool was constructed"))
          (is (= expected (ts/pair e)) (str engine ": " attempt))
          (is (= [] (ts/leaks-in engine e)) (str engine ": " attempt ": SPEC §6: a secret was echoed"))
          (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000))
              (str engine ": " attempt ": the pool is closed"))))
      (is (= [false false] [(contains? (ts/tables url) "ragtime_migrations") (contains? (ts/tables url) "m_probe")])
          (str engine ": a boot the lock stopped created no control table and ran nothing"))
      (let [repair (re-find #"DELETE FROM .*$" (str (ex-message (bounded #(ts/thrown (fn [] (db/start cfg)))))))]
        (is (some? repair) (str engine ": precondition: the message carries a statement to run"))
        (ts/execute! url repair)
        (is (= [{:migrations-applied 3} []] [(ts/boot (ts/config url "three")) (ts/lock-rows url)])
            (str engine ": the repair the message names is the one that works, and the boot gives the lock back"))))))

(deftest with-nothing-left-to-do-a-lock-row-left-behind-does-not-stop-a-boot
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 3} (ts/boot (ts/config url "three"))) (str engine ": precondition: everything is applied"))
    (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                       " VALUES ('ragtime_migrations', 'DEAD-HOLDER-7f3a', 1700000000000)"))
    (is (= {:migrations-applied 0} (bounded #(ts/boot (assoc-in (ts/config url "three") [:migrations :lock-wait-ms] 0))))
        (str engine ": a boot with nothing to do never asks for the lock"))
    (is (= [["ragtime_migrations" "DEAD-HOLDER-7f3a" 1700000000000]] (ts/lock-rows url))
        (str engine ": and leaves the row exactly as it found it"))
    (is (= (str "db-base: another instance holds the migration lock: DEAD-HOLDER-7f3a, taken at 1700000000000"
                " (epoch milliseconds), and 0 ms of [:migrations :lock-wait-ms] were not enough. If that"
                " instance is gone, the repair is: DELETE FROM db_base_migration_lock WHERE id ="
                " 'ragtime_migrations'")
           (str (ex-message (bounded #(ts/thrown (fn [] (db/start (assoc-in (ts/config url "plus-one")
                                                                            [:migrations :lock-wait-ms] 0))))))))
        (str engine ": positive control: that same row does stop a boot that has a migration to apply"))))

(deftest a-recorded-migration-the-source-lacks-stops-a-boot-with-nothing-else-to-do
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 5} (ts/boot (ts/config url "between"))) (str engine ": precondition: five are applied"))
    ;; A row someone else holds, and a wait of zero: a boot that asked for the lock before
    ;; reading the history would fail naming that holder instead of the disagreement.
    (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                       " VALUES ('ragtime_migrations', 'DEAD-HOLDER-7f3a', 1700000000000)"))
    (let [e (bounded #(ts/thrown (fn [] (db/start (assoc-in (ts/config url "three")
                                                            [:migrations :lock-wait-ms] 0)))))]
      (is (= (refused "three" (str "migration 002a-x is recorded in ragtime_migrations but not found under"
                                   " db-base-test/three")
                      "002a-x")
             (ts/pair e))
          (str engine ": nothing is pending, and a history the source lacks still stops the boot"))
      (is (= [["ragtime_migrations" "DEAD-HOLDER-7f3a" 1700000000000]] (ts/lock-rows url))
          (str engine ": and the lock was never asked for to say it — the row it would have"
               " failed against is as it was")))))

(deftest a-lock-table-that-cannot-be-read-or-created-stops-the-boot
  (doseq [[engine url break!]
          [["H2" (ts/h2-memory-url ts/url-sentinel)
            #(ts/execute! % "CREATE FORCE VIEW db_base_migration_lock AS SELECT * FROM no_such_table")]
           ["SQLite" (ts/sqlite-file-url ts/url-sentinel)
            #(do (ts/execute! % "CREATE TABLE gone (id VARCHAR(64))")
                 (ts/execute! % "CREATE VIEW db_base_migration_lock AS SELECT id FROM gone")
                 (ts/execute! % "DROP TABLE gone"))]]]
    (break! url)
    (is (= :threw (try (ts/query url "SELECT COUNT(*) FROM db_base_migration_lock") :read
                       (catch Exception _ :threw)))
        (str engine ": precondition: the name is taken by something no SELECT can read"))
    (let [before (ts/pool-number)
          e      (bounded #(ts/thrown (fn [] (db/start (ts/config url "three")))))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) (str engine ": precondition: a pool was constructed"))
      (is (= (refused "three" "the lock table db_base_migration_lock could not be taken, read or created")
             (ts/pair e))
          engine)
      (is (instance? SQLException (ex-cause e)) (str engine ": the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": the pool is closed"))
      (is (= [false false] [(contains? (ts/tables url) "ragtime_migrations") (contains? (ts/tables url) "m_probe")])
          (str engine ": nothing was created or run")))))

(deftest a-boot-that-loses-the-race-to-create-the-lock-table-carries-on
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url (str "CREATE TABLE db_base_migration_lock (id VARCHAR(64) NOT NULL PRIMARY KEY,"
                       " holder VARCHAR(36) NOT NULL, acquired_at BIGINT NOT NULL)"))
    (let [probes   (atom 0)
          able-var (ns-resolve 'dev.arkaitz.db-base 'readable?)
          honest   @able-var]
      ;; The first probe lies, so the CREATE meets a table that is already there — what the
      ;; loser of a race between two cold boots sees. The second probe, an honest one,
      ;; absorbs it. The var is put back from this thread, so a boot that hung would not
      ;; leave the lie behind for the rest of the suite.
      (alter-var-root able-var
                      (constantly (fn [ds table] (if (= 1 (swap! probes inc)) false (honest ds table)))))
      (try (is (= {:migrations-applied 3} (bounded #(ts/boot (ts/config url "three"))))
               (str engine ": the create that loses the race does not stop the boot"))
           (finally (alter-var-root able-var (constantly honest))))
      (is (= [2 []] [@probes (ts/lock-rows url)])
          (str engine ": witness: the probe was asked twice, and the lock was taken and given back")))))

(deftest a-boot-gives-back-its-own-lock-row-and-no-other
  (doseq [[engine url] (ts/engines)]
    (fns/reset-gate!)
    (let [[_ run] (ts/running #(ts/boot (ts/config url "gated")))]
      (is (= true (deref @fns/arrived 20000 ::never))
          (str engine ": precondition: the boot is inside a migration, holding the lock"))
      (is (= 1 (count (ts/lock-rows url)))
          (str engine ": precondition: its row is there for another connection to read"))
      ;; An operator who thinks that boot died runs the repair the message names, and a
      ;; second instance takes the lock. The row this one gives back must be its own — and
      ;; a holder that kept a transaction open would not let this connection delete it.
      ;; That it gives its own row back at all is the overlap test's claim, not this one's:
      ;; here its row is gone before it tries.
      (ts/execute! url "DELETE FROM db_base_migration_lock WHERE id = 'ragtime_migrations'")
      (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                         " VALUES ('ragtime_migrations', 'TOOK-IT-AFTER-THE-REPAIR', 1700000000001)"))
      (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                         " VALUES ('other_migrations', 'ANOTHER-CONTROL-TABLE', 1700000000002)"))
      (deliver @fns/release true)
      (is (= [:ok {:migrations-applied 3}] (deref run 30000 ::hang))
          (str engine ": the boot finishes and reports what it applied"))
      (is (= [["other_migrations" "ANOTHER-CONTROL-TABLE" 1700000000002]
              ["ragtime_migrations" "TOOK-IT-AFTER-THE-REPAIR" 1700000000001]]
             (sort-by first (ts/lock-rows url)))
          (str engine ": and deleted neither the row taken after the repair nor another"
               " control table's")))))

(deftest a-boot-that-fails-gives-back-its-own-lock-row-and-no-other
  ;; The failure path's twin of the test above. A boot that dies at a migration releases
  ;; through `release-after-failure!`, and that release has to be scoped to its own holder
  ;; as tightly as the success path's: an operator who took this boot for dead ran the
  ;; repair, a second instance holds the same id now, and the first one's failure must
  ;; not take that lock away from under it. The second row is planted rather than taken
  ;; by a live boot — the migration gate is one per JVM — which is all a DELETE can tell
  ;; apart anyway: a row, an id and a holder.
  (doseq [[engine url] (ts/engines)]
    (fns/reset-gate!)
    (let [[_ run] (ts/running #(ts/boot (ts/config url "gated-failing")))]
      (is (= true (deref @fns/arrived 20000 ::never))
          (str engine ": precondition: the boot is inside a migration, holding the lock"))
      (is (= 1 (count (ts/lock-rows url)))
          (str engine ": precondition: its row is there for another connection to read"))
      (ts/execute! url "DELETE FROM db_base_migration_lock WHERE id = 'ragtime_migrations'")
      (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                         " VALUES ('ragtime_migrations', 'TOOK-IT-AFTER-THE-REPAIR', 1700000000001)"))
      (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                         " VALUES ('other_migrations', 'ANOTHER-CONTROL-TABLE', 1700000000002)"))
      (let [planted (sort-by first (ts/lock-rows url))]
        (is (= 2 (count planted)) (str engine ": witness: both rows are there before the boot is let go"))
        (deliver @fns/release true)
        (let [result      (deref run 30000 ::hang)
              [outcome e] (when (vector? result) result)]
          (is (not= ::hang result) (str engine ": the boot came back"))
          ;; The gate's own bound would also let the boot go, and after that its release
          ;; would run before the rows above were planted — green whatever it deletes.
          (is (true? @fns/gate-exit) (str engine ": witness: released by the test, not by the gate's bound"))
          (is (= :threw outcome) (str engine ": the boot fails"))
          (is (= (refused "gated-failing" "migration 002-b failed" "002-b") (ts/pair e))
              (str engine ": at 002-b, so it was the failure path's release that ran"))
          (is (= planted (sort-by first (ts/lock-rows url)))
              (str engine ": and it deleted neither the row taken after the repair nor another"
                   " control table's")))))))

(deftest a-lock-that-cannot-be-given-back-stops-the-boot-after-the-migrations-applied
  (doseq [[engine url] (ts/engines)]
    (fns/reset-gate!)
    (let [[_ run] (ts/running #(ts/boot (ts/config url "gated")))]
      (is (= true (deref @fns/arrived 20000 ::never))
          (str engine ": precondition: the boot is inside a migration, holding the lock"))
      (ts/execute! url "DROP TABLE db_base_migration_lock")
      (deliver @fns/release true)
      (let [[outcome e] (deref run 30000 ::hang)
            holder      (:holder (ex-data e))]
        (is (= :threw outcome) (str engine ": the boot fails rather than returning a handle"))
        (is (some? (parse-uuid (str holder)))
            (str engine ": it names the holder whose row it is: " (pr-str holder)))
        (is (= [(str "db-base: the migration run finished, but this boot's lock row could not be given"
                     " back. If it is still there, the repair is: DELETE FROM db_base_migration_lock"
                     " WHERE id = 'ragtime_migrations' AND holder = '" holder "'")
                {:config-key [:migrations :dir] :value "db-base-test/gated" :holder holder}]
               (ts/pair e))
            (str engine ": SPEC §6: it is this library's ex-info, and what it advises is true of a"
                 " table that is gone as well as of a row that is still there"))
        (is (instance? SQLException (ex-cause e))
            (str engine ": the driver's refusal is the cause: " (pr-str (ex-cause e))))
        (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
        (is (= [] (ts/threads-alive-after-join (ts/pool-number) #":housekeeper$" 5000))
            (str engine ": and its pool is closed")))
      (is (= [["001-a" "002-b" "003-c"] [[7 "seven"]]]
             [(recorded url) (ts/query url "SELECT n, label FROM m_probe")])
          (str engine ": the migrations applied and were recorded — the boot stops, the schema stands")))))

(deftest a-lock-given-back-as-the-wait-runs-out-is-taken-not-refused
  (doseq [[engine url] (ts/engines)]
    (fns/reset-gate!)
    (ts/execute! url (str "CREATE TABLE db_base_migration_lock (id VARCHAR(64) NOT NULL PRIMARY KEY,"
                       " holder VARCHAR(36) NOT NULL, acquired_at BIGINT NOT NULL)"))
    (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                       " VALUES ('ragtime_migrations', 'HOLDER-ABOUT-TO-FINISH', 1700000000000)"))
    (let [reads   (atom 0)
          row-var (ns-resolve 'dev.arkaitz.db-base 'first-row)
          honest  @row-var
          t0      (System/currentTimeMillis)]
      ;; The holder gives the lock back in the instant between the last refused INSERT and the
      ;; read that would name it: the race is made to happen, not waited for. The var is put
      ;; back as soon as the boot is past that read, so nothing of this test can outlive it.
      (alter-var-root row-var
                      (constantly (fn [ds sql & params]
                                    (when (str/includes? sql "SELECT holder")
                                      (swap! reads inc)
                                      (ts/execute! url (str "DELETE FROM db_base_migration_lock"
                                                         " WHERE id = 'ragtime_migrations'")))
                                    (apply honest ds sql params))))
      (let [[_ run] (ts/running #(ts/boot (assoc-in (ts/config url "gated") [:migrations :lock-wait-ms] 0)))]
        (try (is (= true (deref @fns/arrived 20000 ::never))
                 (str engine ": precondition: the boot is inside a migration rather than refused"))
             (finally (alter-var-root row-var (constantly honest))))
        (let [rows (ts/lock-rows url)
              t1   (System/currentTimeMillis)
              [id holder at] (first rows)]
          (is (= [1 "ragtime_migrations" true] [(count rows) id (not= "HOLDER-ABOUT-TO-FINISH" holder)])
              (str engine ": it took the lock the holder had just given back, in its own name: "
                   (pr-str rows)))
          (is (<= t0 (long (or at 0)) t1)
              (str engine ": and recorded when it took it, not when the other did: " (pr-str at))))
        (deliver @fns/release true)
        (is (= [:ok {:migrations-applied 3}] (deref run 30000 ::hang))
            (str engine ": the three run under that lock"))
        (is (= [1 ["001-a" "002-b" "003-c"] []] [@reads (recorded url) (ts/lock-rows url)])
            (str engine ": witness: the read that names the holder ran once, and the lock was given back"))))))

(deftest a-history-that-comes-back-out-of-order-still-applies-what-is-pending
  (doseq [[engine url] (ts/engines)]
    (is (= {:migrations-applied 3} (ts/boot (ts/config url "three"))) (str engine ": precondition: three applied"))
    ;; The same disorder a millisecond tie produces (measured, SPEC §7), written by hand so
    ;; it is there on both engines: the history comes back in an order the source does not have.
    (ts/execute! url "DELETE FROM ragtime_migrations")
    (doseq [id ["003-c" "001-a" "002-b"]]
      (ts/execute! url (str "INSERT INTO ragtime_migrations (id, created_at) VALUES ('" id
                         "', '2026-09-14T10:00:00.000')")))
    (is (= ["003-c" "001-a" "002-b"] (vec (ragtime-protocols/applied-migration-ids (store url))))
        (str engine ": witness: ragtime reads the history out of the source's order"))
    (is (= ::strategy/migration-conflict
           (:reason (ex-data (ts/thrown #(doall (strategy/raise-error ["003-c" "001-a" "002-b"]
                                                                      ["001-a" "002-b" "003-c" "004-d"]))))))
        (str engine ": positive control: ragtime's own strategy calls that order a conflict"))
    (is (= {:migrations-applied 1} (bounded #(ts/boot (ts/config url "plus-one"))))
        (str engine ": start applies the one that is pending instead of refusing"))
    (is (= [["001-a" "002-b" "003-c" "004-d"] [[7 "seven"] [4 "four"]] []]
           [(recorded url) (ts/query url "SELECT n, label FROM m_probe") (ts/lock-rows url)])
        (str engine ": and only that one ran, recorded, with the lock given back"))
    (ts/execute! url "DELETE FROM ragtime_migrations")
    (doseq [id ["002-b" "003-c" "001-a" "004-d"]]
      (ts/execute! url (str "INSERT INTO ragtime_migrations (id, created_at) VALUES ('" id
                         "', '2026-09-14T10:00:00.000')")))
    (is (= (refused "between" "migration 002a-x is new but sorts before 004-d, which is already applied"
                    "002a-x")
           (ts/pair (bounded #(ts/thrown (fn [] (db/start (assoc-in (ts/config url "between")
                                                                 [:migrations :lock-wait-ms] 0)))))))
        (str engine ": and which one is the last applied is a question about the source's order,"
             " not about the order the history arrives in"))))

(deftest the-history-is-read-as-a-set-whatever-order-it-arrives-in
  (let [plan     @(ns-resolve 'dev.arkaitz.db-base 'plan-migrations)
        ;; The run is built by the code under test rather than spelled here, so this stays
        ;; a test of how a history is read and not of the shape of a map (SPEC §7, §8).
        run      #(@(ns-resolve 'dev.arkaitz.db-base 'host-run) % 0)
        between  (vec (ragtime-jdbc/load-resources "db-base-test/between"))
        plus-one (vec (ragtime-jdbc/load-resources "db-base-test/plus-one"))]
    (is (= [["001-a" "002-b" "002a-x" "003-c" "004-d"] ["001-a" "002-b" "003-c" "004-d"]]
           [(mapv ragtime-protocols/id between) (mapv ragtime-protocols/id plus-one)])
        "precondition: one source sorts a pending migration between two applied ones, the other after all")
    ;; Ties in ragtime's created_at come back in whatever order the engine likes, so which
    ;; migration is the last applied is a question about the source's order, not about the
    ;; order the ids arrive in (SPEC §7).
    (doseq [order [["001-a" "002-b" "003-c"] ["003-c" "001-a" "002-b"] ["002-b" "003-c" "001-a"]
                   ["003-c" "002-b" "001-a"] ["001-a" "003-c" "002-b"] ["002-b" "001-a" "003-c"]]]
      (is (= (refused "between" "migration 002a-x is new but sorts before 003-c, which is already applied"
                      "002a-x")
             (ts/pair (ts/thrown #(plan (run "db-base-test/between") order between))))
          (str "refused whatever order the history arrives in: " (pr-str order)))
      (is (= ["004-d"] (plan (run "db-base-test/plus-one") order plus-one))
          (str "and what sorts after every applied one is pending in that same order: " (pr-str order))))))

(deftest a-lock-row-a-failed-release-left-behind-waits-for-a-boot-that-has-something-to-apply
  (doseq [[engine url] (ts/engines)]
    (let [give-back (ns-resolve 'dev.arkaitz.db-base 'release-lock!)
          e         (bounded #(ts/thrown
                               (fn []
                                 (with-redefs-fn {give-back (fn [_ _]
                                                              (throw (SQLException.
                                                                      "sentinel: the delete refused")))}
                                   (fn [] (db/start (ts/config url "three")))))))
          holder    (:holder (ex-data e))]
      (is (= [["ragtime_migrations" holder]] (mapv #(subvec % 0 2) (ts/lock-rows url)))
          (str engine ": the row it could not give back is still there, in its own name"))
      (is (= (str "db-base: the migration run finished, but this boot's lock row could not be given"
                  " back. If it is still there, the repair is: DELETE FROM db_base_migration_lock"
                  " WHERE id = 'ragtime_migrations' AND holder = '" holder "'")
             (ex-message e))
          (str engine ": and the boot says so, naming the row an operator would check"))
      (is (= ["001-a" "002-b" "003-c"] (recorded url))
          (str engine ": with everything applied and recorded, which is why the boot is the only loss"))
      (is (= {:migrations-applied 0} (bounded #(ts/boot (assoc-in (ts/config url "three")
                                                               [:migrations :lock-wait-ms] 0))))
          (str engine ": SPEC §7: the restarts that follow have nothing to apply, so they neither"
               " wait for that row nor report it"))
      (let [refusal (bounded #(ts/thrown (fn [] (db/start (assoc-in (ts/config url "plus-one")
                                                                    [:migrations :lock-wait-ms] 0)))))
            repair  (re-find #"DELETE FROM .*$" (str (ex-message refusal)))]
        (is (= holder (:holder (ex-data refusal)))
            (str engine ": and the first boot that does have one to run names that same holder"))
        (is (some? repair) (str engine ": precondition: its message carries a statement to run"))
        (ts/execute! url repair)
        (is (= [{:migrations-applied 1} []] [(ts/boot (ts/config url "plus-one")) (ts/lock-rows url)])
            (str engine ": the repair it names is the one that works, and the boot gives the lock back"))))))

;; --- :libraries -----------------------------------------------------------------

(defn- with-library [url host-prefix]
  (assoc (ts/config url host-prefix)
         :libraries [{:dir "db-base-test/lib-accounts" :table "lib_accounts_migrations" :lock-wait-ms 1000}]))

(deftest a-librarys-migrations-run-before-the-hosts-under-their-own-history
  (doseq [[engine url] (ts/engines)]
    (let [handle (db/start (with-library url "lib-host"))]
      (try
        (is (= [{"lib_accounts_migrations" 1} 1]
               [(:library-migrations-applied handle) (:migrations-applied handle)])
            (str engine ": the library's run applied its one, and the host's its one"))
        (finally (db/stop handle))))
    (is (= [["from-host"]] (ts/query url "SELECT subject FROM lib_account"))
        (str engine ": the host's migration wrote into the library's table, so the library's ran first"))
    (is (= [["001-accounts"] ["001-accounts"]]
           [(mapv first (ts/query url "SELECT id FROM lib_accounts_migrations"))
            (recorded url)])
        (str engine ": one id in two histories — each run keeps its own, so neither mistakes the other's"))
    (is (= {:library-migrations-applied {"lib_accounts_migrations" 0} :migrations-applied 0}
           (ts/boot (with-library url "lib-host")))
        (str engine ": a second boot applies nothing in either"))))

(deftest a-library-prefix-that-serves-nothing-is-refused-before-any-pool-naming-its-entry
  (let [url    (ts/h2-memory-url "lib-empty")
        before (ts/pool-number)
        e      (ts/thrown #(db/start (assoc (ts/config url "three")
                                            :libraries [{:dir "db-base-test/no-such-prefix" :table "lib_x_migrations" :lock-wait-ms 0}])))]
    (is (= ["db-base: no migrations found under the classpath prefix db-base-test/no-such-prefix"
            {:config-key [:libraries 0 :dir] :value "db-base-test/no-such-prefix"}]
           (ts/pair e)))
    (is (= before (ts/pool-number)) "refused before any pool was constructed")))

(deftest a-library-run-waits-on-its-own-lock-row-and-a-stuck-one-names-its-own-key
  (doseq [[engine url] (ts/engines)]
    (ts/execute! url (str "CREATE TABLE db_base_migration_lock (id VARCHAR(64) NOT NULL PRIMARY KEY,"
                       " holder VARCHAR(36) NOT NULL, acquired_at BIGINT NOT NULL)"))
    (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at)"
                       " VALUES ('lib_accounts_migrations', 'DEAD-LIBRARY-HOLDER', 1700000000000)"))
    (let [cfg (assoc (ts/config url "three")
                     :libraries [{:dir "db-base-test/lib-accounts" :table "lib_accounts_migrations" :lock-wait-ms 150}])
          e   (bounded #(ts/thrown (fn [] (db/start cfg))))]
      (is (= [(str "db-base: another instance holds the migration lock: DEAD-LIBRARY-HOLDER, taken at"
                   " 1700000000000 (epoch milliseconds), and 150 ms of [:libraries 0 :lock-wait-ms]"
                   " were not enough. If that instance is gone, the repair is: DELETE FROM"
                   " db_base_migration_lock WHERE id = 'lib_accounts_migrations'")
              {:config-key [:libraries 0 :lock-wait-ms] :value 150 :dir "db-base-test/lib-accounts"
               :holder "DEAD-LIBRARY-HOLDER" :acquired-at 1700000000000}]
             (ts/pair e))
          (str engine ": the library's run waited its own wait on its own row, and says which key to raise"))
      (is (not (contains? (ts/tables url) "lib_account"))
          (str engine ": and ran nothing")))))

(deftest a-librarys-migrations-run-after-the-session-table
  (doseq [[engine url] (ts/engines)]
    (is (= {:session-migrations-applied 1 :session-data-max 4000
            :library-migrations-applied {"lib_seed_migrations" 1}}
           (ts/boot (assoc (ts/config url "three")
                           :migrations :none
                           :sessions {:lock-wait-ms 1000}
                           :libraries [{:dir "db-base-test/lib-after-sessions" :table "lib_seed_migrations"
                                        :lock-wait-ms 1000}])))
        (str engine ": both ran"))
    (is (= [["from-a-library"]] (ts/query url "SELECT id FROM db_base_sessions"))
        (str engine ": the library's migration wrote into the session table, which was already there"))))
