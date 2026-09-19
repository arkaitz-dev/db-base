(ns db-base-test.migration-fns
  "Functions the EDN migration fixtures under test/resources/db-base-test name. Each
  records that it ran, so a test sees a migration run or not run. Beside the test
  namespaces rather than in src, and outside the library's prefix, because these var
  roots hold atoms."
  (:import [java.sql Connection]
           [javax.sql DataSource]))

(def up-calls
  "One entry per call: the datasource ragtime handed the function."
  (atom []))

(def down-calls (atom 0))

(def thrown-error (atom nil))

(defn up! [ds] (swap! up-calls conj ds))

(defn down! [_] (swap! down-calls inc))

(defn throw-error! [_] (throw (reset! thrown-error (Error. "sentinel from a migration"))))

(defn interrupt!
  "Returns normally with the calling thread's interrupt flag set, which is how an
  interrupt reaches a boot between two migrations."
  [_]
  (.interrupt (Thread/currentThread)))

(def gate-calls
  "How many times the gated migration has run: a lock that does not hold makes this two."
  (atom 0))

(def arrived
  "Delivered by the gated migration once it is inside the lock, so a second boot can be
  started while the first provably holds it."
  (atom (promise)))

(def release
  "Delivered by the test when it wants the gated migration to finish."
  (atom (promise)))

(def gate-exit
  "What ended the wait: `true` when the test released it, the keyword when its own bound
  did — without this, a test that never releases passes in green thirty seconds later."
  (atom nil))

(defn reset-gate! []
  (reset! gate-calls 0)
  (reset! gate-exit nil)
  (reset! arrived (promise))
  (reset! release (promise)))

(defn gate!
  "Creates the table the later migrations need, says it is running, and waits — bounded,
  so a test that never releases it fails instead of hanging."
  [^DataSource ds]
  ;; Counted before anything can throw, so the count says how many times it was entered
  ;; and not how many times the CREATE happened to succeed.
  (swap! gate-calls inc)
  (with-open [^Connection c (.getConnection ds)
              st (.createStatement c)]
    (.execute st "CREATE TABLE m_probe (n INTEGER)"))
  (deliver @arrived true)
  (reset! gate-exit (deref @release 30000 :released-by-its-own-bound)))

(defn forbid-recording!
  "Runs, leaves a table behind as its mark, and makes its own recording fail: from here
  on the control table takes no id that sorts at or past 002. H2 only, because SQLite
  cannot add a constraint to a table that exists."
  [^DataSource ds]
  (with-open [^Connection c (.getConnection ds)
              st (.createStatement c)]
    (.execute st "CREATE TABLE m_ran (n INTEGER)")
    (.execute st "ALTER TABLE ragtime_migrations ADD CONSTRAINT db_base_test_before_002 CHECK (id < '002')")))
