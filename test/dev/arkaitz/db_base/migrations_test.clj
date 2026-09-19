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

(defn- engines []
  [["H2" (ts/h2-memory-url ts/url-sentinel)]
   ["SQLite" (ts/sqlite-file-url ts/url-sentinel)]])

(defn- config
  "Secrets in every field that has one, so the leak check has something to find. The
  borrow timeout is wide because nothing here asserts a duration and the first SQLite
  connection of a JVM unpacks a native library: measured 655 ms cold against 0 ms warm,
  and a loaded machine turned 1000 ms into a boot that failed for the wrong reason."
  [url prefix]
  {:jdbc-url url :user ts/user-sentinel :password ts/password-sentinel
   :pool {:max 2 :timeout-ms 5000}
   :migrations {:dir (str "db-base-test/" prefix) :lock-wait-ms 1000}})

(defn- query [url sql]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.createStatement c)
              ^ResultSet rs (.executeQuery st sql)]
    (let [n (.getColumnCount (.getMetaData rs))]
      (loop [acc []]
        (if (.next rs) (recur (conj acc (mapv #(.getObject rs (int %)) (range 1 (inc n))))) acc)))))

(defn- execute! [url sql]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.createStatement c)]
    (.execute st sql)))

(defn- recorded [url] (mapv first (query url "SELECT id FROM ragtime_migrations ORDER BY id")))

(defn- tables
  "Table names in lower case: H2 reports them upper-cased."
  [url]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              ^ResultSet rs (.getTables (.getMetaData c) nil nil "%" (into-array String ["TABLE"]))]
    (loop [acc #{}] (if (.next rs) (recur (conj acc (.toLowerCase (.getString rs "TABLE_NAME")))) acc))))

(defn- boot
  "Starts, returns the handle without its datasource, and stops."
  [cfg]
  (let [handle (db/start cfg)]
    (try (dissoc handle :datasource) (finally (db/stop handle)))))

(defn- pair [e] [(ex-message e) (ex-data e)])

(defn- refused [prefix message & [migration-id]]
  [(str "db-base: " message)
   (cond-> {:config-key [:migrations :dir] :value (str "db-base-test/" prefix)}
     migration-id (assoc :migration-id migration-id))])

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
      (db/stop (db/start (config url "three")))
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
            e      (ts/thrown #(db/start (config url prefix)))]
        (is (instance? ExceptionInfo e) (str label ": start did not refuse: " (pr-str e)))
        (is (= expected (pair e)) label)
        (is (cause? (ex-cause e)) (str label ": cause " (pr-str (ex-cause e))))
        (is (= before (ts/pool-number)) (str label ": refused before any pool was constructed"))
        (is (= [] (ts/leaks-in label e)) (str label ": SPEC §6: a secret was echoed"))))))

(deftest start-applies-the-pending-migrations-in-source-order-and-a-second-boot-applies-none
  (is (some? (io/resource "db-base-test/three/sub/009-z.up.sql"))
      "precondition: a migration file sits in a subdirectory of the prefix, where it must not count")
  (doseq [[engine url] (engines)]
    (let [handle (db/start (config url "three"))]
      (try
        (is (= [[:datasource :migrations-applied] 3] [(sort (keys handle)) (:migrations-applied handle)])
            (str engine ": the first boot's handle carries the count it applied"))
        (finally (db/stop handle))))
    (is (= ["001-a" "002-b" "003-c"] (recorded url))
        (str engine ": ragtime_migrations records the three direct children, not the one in a subdirectory"))
    (is (= [[7 "seven"]] (query url "SELECT n, label FROM m_probe"))
        (str engine ": they ran in the source's order: the table, then its column, then the row that needs it"))
    (is (= {:migrations-applied 0} (boot (config url "three"))) (str engine ": a second boot applies nothing"))
    (is (= [["001-a" "002-b" "003-c"] [[7 "seven"]]] [(recorded url) (query url "SELECT n, label FROM m_probe")])
        (str engine ": and runs nothing again — m_probe has no key, so a rerun would add a row, not fail"))))

(deftest migration-ids-are-applied-in-the-order-of-their-names-as-strings
  (doseq [[engine url] (engines)]
    (is (= {:migrations-applied 2} (boot (config url "string-order")))
        (str engine ": 10-b creates the table 9-a writes to, which is the string order, not the numeric one"))
    (is (= [["10-b" "9-a"] [[9]]] [(recorded url) (query url "SELECT n FROM m_probe")]) engine)))

(deftest a-migration-that-fails-leaves-the-ones-before-it-recorded-names-itself-and-closes-the-pool
  (doseq [[engine url] (engines)]
    (let [before (ts/pool-number)
          e      (ts/thrown #(db/start (config url "failing")))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) (str engine ": precondition: a pool was constructed, so its closing means something"))
      (is (= (refused "failing" "migration 002-b failed" "002-b") (pair e)) engine)
      (is (instance? SQLException (ex-cause e)) (str engine ": the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": the pool is closed"))
      (is (= [["001-a"] []] [(recorded url) (query url "SELECT n FROM m_probe")])
          (str engine ": 001 is applied and recorded, 002 is not recorded, and nothing after it ran"))
      (is (= [true false] [(contains? (tables url) "m_probe") (contains? (tables url) "m_down_ran")])
          (str engine ": 002's down was not run to unwind it — its down file would leave a table behind")))
    (is (= {:migrations-applied 2} (boot (config url "fixed")))
        (str engine ": the next boot, on a fixed 002, attempts 002 again and then 003"))
    (is (= [["001-a" "002-b" "003-c"] [[8 "eight"]]] [(recorded url) (query url "SELECT n, label FROM m_probe")])
        engine))
  (testing "an Error from a migration is not wrapped, and the pool is still closed"
    (reset! fns/thrown-error nil)
    (let [url    (ts/h2-memory-url ts/url-sentinel)
          before (ts/pool-number)
          r      (try (let [h (db/start (config url "erroring"))] (db/stop h) ::no-throw)
                      (catch Throwable t t))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) "precondition: a pool was constructed")
      (is (and (some? @fns/thrown-error) (identical? @fns/thrown-error r))
          (str "the migration's own Error arrives unchanged: " (pr-str r)))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) "the pool is closed")))
  (testing "a migration that runs and cannot then be recorded names itself, and the next boot runs it again"
    ;; H2 only: SQLite cannot add a constraint to a table that already exists.
    (let [url (ts/h2-memory-url ts/url-sentinel)
          e   (ts/thrown #(db/start (config url "unrecordable")))
          n   (ts/pool-number)]
      (is (= (refused "unrecordable" "migration 002-b failed" "002-b") (pair e)) "the failure names the migration")
      (is (instance? SQLException (ex-cause e)) (str "the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) "the pool is closed")
      (is (= [["001-a"] true] [(recorded url) (contains? (tables url) "m_ran")])
          "002 ran — it left its table — and is not recorded, which is what leaves it pending")))
  (testing "an interrupt reaches the boot between two migrations and passes through, pool closed"
    (let [url    (ts/h2-memory-url ts/url-sentinel)
          before (ts/pool-number)
          r      (try (let [h (db/start (config url "interrupting"))] (db/stop h) ::no-throw)
                      (catch Throwable t t))
          n      (ts/pool-number)
          ;; Cleared here, and reported, so the flag cannot reach the next test.
          flag   (Thread/interrupted)]
      (is (= (inc (or before 0)) n) "precondition: a pool was constructed")
      (is (instance? InterruptedException r) (str "the interrupt arrives unwrapped: " (pr-str r)))
      (is (true? flag) "and the flag it arrived with is still set for the caller")
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000))
          "the pool is closed even though the closing thread was interrupted")
      (is (= [["001-a"] false] [(recorded url) (contains? (tables url) "m_probe")])
          "001 stayed applied and recorded, and 002, which would create a table, never ran"))))

(deftest a-trailing-separator-leaves-nothing-behind-and-a-blank-statement-beside-a-real-one-still-runs
  (is (= [["001-a" ["CREATE TABLE m_probe (n INTEGER)"]]]
         (mapv (juxt ragtime-protocols/id :up) (ragtime-jdbc/load-resources "db-base-test/half-blank")))
      (str "witness: an up file ending in a separator loads as one statement — ragtime's split drops the "
           "empty tail — so it is not the case that distinguishes 'every statement is blank' from 'any is'"))
  (doseq [[engine url] (engines)]
    (is (= {:migrations-applied 1} (boot (config url "half-blank")))
        (str engine ": and the migration runs"))
    (is (= [["001-a"] true] [(recorded url) (contains? (tables url) "m_probe")]) engine))
  (testing "a blank statement beside a real one is still a migration to run"
    ;; H2 only: it runs a blank statement without a word, where SQLite refuses it. The
    ;; claim here is this library's — that such a migration is not 'nothing to run' — and
    ;; what the engine then does with the blank one is the engine's.
    (let [url (ts/h2-memory-url ts/url-sentinel)]
      (is (= [["001-a" ["CREATE TABLE m_probe (n INTEGER)" "   "]]]
             (mapv (juxt ragtime-protocols/id :up) (ragtime-jdbc/load-resources "db-base-test/blank-among")))
          "witness: the fixture really holds a blank statement beside a real one")
      (is (= {:migrations-applied 1} (boot (config url "blank-among"))) "it is accepted and applied")
      (is (= [["001-a"] true] [(recorded url) (contains? (tables url) "m_probe")])
          "and the real statement ran"))))

(deftest a-recorded-migration-the-source-lacks-or-a-new-one-sorting-before-the-last-applied-stops-the-boot
  (doseq [[engine url] (engines)]
    (is (= {:migrations-applied 3} (boot (config url "three"))) (str engine ": precondition: three are applied"))
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
            e      (ts/thrown #(db/start (config url prefix)))
            n      (ts/pool-number)]
        (is (= (inc (or before 0)) n) (str engine ": " label ": precondition: a pool was constructed"))
        (is (= expected (pair e)) (str engine ": " label))
        (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": " label ": the pool is closed"))
        (is (= [["001-a" "002-b" "003-c"] [[7 "seven"]]] [(recorded url) (query url "SELECT n, label FROM m_probe")])
            (str engine ": " label ": nothing was applied or recorded, not even the migration sorting last"))))))

(deftest recorded-ids-that-share-a-timestamp-and-come-back-out-of-order-are-not-a-conflict
  (doseq [[engine url] (engines)]
    (execute! url "CREATE TABLE ragtime_migrations (id VARCHAR(255) PRIMARY KEY, created_at VARCHAR(32))")
    (doseq [id ["003-c" "001-a" "002-b"]]
      (execute! url (str "INSERT INTO ragtime_migrations (id, created_at) VALUES ('" id "', '2026-09-14T10:00:00.000')")))
    (let [applied (vec (ragtime-protocols/applied-migration-ids (store url)))]
      (is (= ["003-c" "001-a" "002-b"] applied)
          (str engine ": witness: the engine returns the tied rows in insertion order, not sorted, so there is a disorder to survive"))
      (is (= ::strategy/migration-conflict
             (:reason (ex-data (ts/thrown #(doall (strategy/raise-error applied ["001-a" "002-b" "003-c"]))))))
          (str engine ": positive control: ragtime's own check reads that order as a conflict")))
    (is (= {:migrations-applied 0} (boot (config url "three")))
        (str engine ": start reads it as three applied migrations and applies nothing"))
    (is (= [true false] [(contains? (tables url) "ragtime_migrations") (contains? (tables url) "m_probe")])
        (str engine ": and ran none of them — the table the first would create is absent, and the check that says so sees the control table"))
    (is (= (refused "between" "migration 002a-x is new but sorts before 003-c, which is already applied" "002a-x")
           (pair (ts/thrown #(db/start (config url "between")))))
        (str engine ": a pending migration between two applied ones is still refused, from the same disordered history"))))

(deftest a-down-never-runs-and-an-edn-function-runs-with-the-handles-datasource
  (doseq [[engine url] (engines)]
    (reset! fns/up-calls [])
    (reset! fns/down-calls 0)
    (let [handle (db/start (config url "fns"))]
      (try
        (is (= [1 [(:datasource handle)] 0]
               [(:migrations-applied handle) @fns/up-calls @fns/down-calls])
            (str engine ": up ran once, handed the handle's own datasource, and down did not run"))
        (finally (db/stop handle))))
    (is (= {:migrations-applied 0} (boot (config url "fns"))) (str engine ": a second boot applies nothing"))
    (is (= [1 0] [(count @fns/up-calls) @fns/down-calls]) (str engine ": and runs neither up nor down again"))
    (ragtime/rollback-last (store url) (ragtime/into-index (ragtime-jdbc/load-resources "db-base-test/fns")))
    (is (= [1 []] [@fns/down-calls (recorded url)])
        (str engine ": positive control: down is reachable, and ragtime's rollback runs it"))))

(deftest migrations-none-leaves-the-count-absent-and-creates-no-control-table
  (doseq [[engine url] (engines)]
    (is (= {} (boot (assoc (config url "three") :migrations :none))) (str engine ": the handle has no count"))
    (is (not (contains? (tables url) "ragtime_migrations")) (str engine ": :none created no control table"))
    (boot (config url "three"))
    (is (contains? (tables url) "ragtime_migrations")
        (str engine ": positive control: a boot with migrations creates the table the check looks for"))))

(deftest a-control-table-that-cannot-be-read-stops-the-boot-with-the-pool-closed
  (doseq [[engine url] (engines)]
    (execute! url "CREATE TABLE ragtime_migrations (x INTEGER)")
    (let [before (ts/pool-number)
          e      (ts/thrown #(db/start (config url "three")))
          n      (ts/pool-number)]
      (is (= (inc (or before 0)) n) (str engine ": precondition: a pool was constructed"))
      (is (= (refused "three" "the control table ragtime_migrations could not be read or created") (pair e)) engine)
      (is (instance? SQLException (ex-cause e)) (str engine ": the driver's refusal is the cause: " (pr-str (ex-cause e))))
      (is (= [] (ts/leaks-in engine e)) (str engine ": SPEC §6: a secret was echoed"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000)) (str engine ": the pool is closed"))
      (is (= [true false] [(contains? (tables url) "ragtime_migrations") (contains? (tables url) "m_probe")])
          (str engine ": no migration ran, and the check that says so sees the table that is there")))))

(deftest an-up-split-across-numbered-files-is-one-migration-and-a-down-beside-it-is-accepted
  (is (some? (io/resource "db-base-test/numbered/001-a.down.sql"))
      "precondition: a down file sits beside them, so accepting it is a claim the file check can break")
  (doseq [[engine url] (engines)]
    (is (= {:migrations-applied 1} (boot (config url "numbered")))
        (str engine ": the two numbered up files are one migration, not two"))
    (is (= [["001-a"] [[1]]] [(recorded url) (query url "SELECT n FROM m_probe")])
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
            e      (ts/thrown #(db/start (config (ts/h2-memory-url ts/url-sentinel) "three")))]
        (is (= (refused "three" "the migrations under db-base-test/three could not be loaded") (pair e))
            "a prefix that cannot be listed is this library's refusal, not a stray exception")
        (is (instance? IllegalArgumentException (ex-cause e))
            (str "the reader's own failure is the cause: " (pr-str (ex-cause e))))
        (is (= before (ts/pool-number)) "and no pool was constructed"))
      (finally (.setContextClassLoader (Thread/currentThread) previous)))))
