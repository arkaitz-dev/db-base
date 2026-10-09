(ns dev.arkaitz.db-base.pg-test
  "The production engine, PostgreSQL, against this library unmodified — opt-in, through
  `clojure -M:pg-test`, and never on `:test`: §3 keeps the everyday suite on H2 and
  SQLite so that nothing PostgreSQL-shaped passes in silence, and this suite is the other
  half of that bargain. It repeats, as tests, what was run by hand on 2026-09-20.

  The connection comes from `pg.local.edn` at the repository root, which `.gitignore`
  keeps out of the repository. Without it every test here fails saying so: a suite that
  ran nothing must not look like one that passed. Each test works in a schema of its own,
  dropped afterwards."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [db-base-test.migration-fns :as fns]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.collision :as collision]
            [dev.arkaitz.db-base.session :as session]
            [next.jdbc :as jdbc]
            [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.protocols :as ragtime-protocols]
            [ring.middleware.session.store :as store])
  (:import [java.io FileNotFoundException]
           [java.sql DriverManager SQLException]))

(def ^:private local
  (try (edn/read-string (slurp "pg.local.edn")) (catch FileNotFoundException _ nil)))

(def ^:private missing
  "pg.local.edn is missing: this suite needs a PostgreSQL to run against (CLAUDE.md says how to start one)")

(defn- admin! [sql]
  (with-open [c (DriverManager/getConnection (:jdbc-url local) (:user local) (:password local))
              st (.createStatement c)]
    (.execute st sql)))

(defn- with-schema*
  "Calls `(f config)` with a configuration whose connections live in a fresh schema."
  [f]
  (let [schema (str "t_" (str/replace (str (random-uuid)) "-" "_"))]
    (admin! (str "CREATE SCHEMA " schema))
    (try
      (f {:jdbc-url   (str (:jdbc-url local) "?currentSchema=" schema)
          :user       (:user local)
          :password   (:password local)
          :pool       {:max 3 :timeout-ms 5000}
          :migrations {:dir "db-base-test/three" :lock-wait-ms 1000}
          :sessions   :none})
      (finally (admin! (str "DROP SCHEMA " schema " CASCADE"))))))

(defmacro ^:private with-schema [[config] & body]
  `(if-not local
     (is false missing)
     (with-schema* (fn [~config] ~@body))))

(defn- rows [config sql]
  (with-open [c (DriverManager/getConnection (:jdbc-url config) (:user config) (:password config))
              st (.createStatement c)
              rs (.executeQuery st sql)]
    (let [n (.getColumnCount (.getMetaData rs))]
      (loop [acc []] (if (.next rs) (recur (conj acc (mapv #(.getObject rs (int %)) (range 1 (inc n))))) acc)))))

(defn- boot [config dir]
  (let [handle (db/start (assoc-in config [:migrations :dir] (str "db-base-test/" dir)))]
    (try (dissoc handle :datasource) (finally (db/stop handle)))))

(deftest the-four-boots-of-2026-09-20
  (with-schema [config]
    (is (= {:migrations-applied 3} (boot config "three")) "the first boot applies three")
    (is (= {:migrations-applied 0} (boot config "three")) "the second applies none")
    (let [e (try (boot config "missing-one") nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= [(str "db-base: migration 002-b is recorded in ragtime_migrations but not found"
                   " under db-base-test/missing-one")
              {:config-key [:migrations :dir] :value "db-base-test/missing-one" :migration-id "002-b"}]
             [(ex-message e) (ex-data e)])
          "a history the source has lost is refused, naming what is missing"))
    (is (= {:migrations-applied 1} (boot config "plus-one")) "and one more applies one")
    (is (= [["001-a"] ["002-b"] ["003-c"] ["004-d"]] (rows config "SELECT id FROM ragtime_migrations ORDER BY id")))
    (is (= [] (rows config "SELECT id FROM db_base_migration_lock")) "every boot gave its lock back")))

(deftest a-control-table-in-another-schema-is-not-this-runs
  ;; helpdesk's H21: ragtime's check sees every schema, its SELECT only the search path.
  (with-schema [config]
    (let [other (str "o_" (str/replace (str (random-uuid)) "-" "_"))]
      (admin! (str "CREATE SCHEMA " other))
      (try
        (admin! (str "CREATE TABLE " other ".ragtime_migrations (id varchar(255) primary key, created_at varchar(32))"))
        (is (= "42P01" (try (ragtime-protocols/applied-migration-ids
                             (ragtime-jdbc/sql-database (jdbc/get-datasource {:jdbcUrl (:jdbc-url config) :user (:user config)
                                                                              :password (:password config)})
                                                        {:migrations-table "ragtime_migrations"}))
                            :read
                            (catch SQLException e (.getSQLState e))))
            "witness: ragtime alone takes the other schema's table for this one, and its read fails")
        (is (= [{:migrations-applied 3}
                [["001-a"] ["002-b"] ["003-c"]] [[0]]]
               [(boot config "three") (rows config "SELECT id FROM ragtime_migrations ORDER BY id")
                (rows config (str "SELECT COUNT(*) FROM " other ".ragtime_migrations"))])
            "the boot applies three, records them in this schema, and writes nothing in the other")
        (is (= [{:migrations-applied 0} []]
               [(boot config "three") (rows config "SELECT id FROM db_base_migration_lock")])
            "and a second boot applies nothing and leaves no lock row")
        (finally (admin! (str "DROP SCHEMA " other " CASCADE")))))))

(deftest two-boots-that-overlap-migrate-once
  (with-schema [config]
    (fns/reset-gate!)
    (let [gated  (assoc-in config [:migrations :dir] "db-base-test/gated")
          first  (future (try [:ok (boot config "gated")] (catch Throwable t [:threw t])))
          _      (is (= true (deref @fns/arrived 20000 ::never)) "precondition: the first holds the lock")
          second (future (try [:ok (boot (assoc-in gated [:migrations :lock-wait-ms] 20000) "gated")]
                              (catch Throwable t [:threw t])))]
      (Thread/sleep 500)
      (is (= 1 (count (rows config "SELECT id FROM db_base_migration_lock"))) "one lock row while both run")
      (deliver @fns/release true)
      (is (= [[:ok {:migrations-applied 3}] [:ok {:migrations-applied 0}]]
             [(deref first 30000 ::hang) (deref second 30000 ::hang)])
          "the first applied three; the second waited and found nothing to do")
      (is (= [1 true] [@fns/gate-calls (true? @fns/gate-exit)]) "the gated migration ran once, released by the test")
      (is (= [] (rows config "SELECT id FROM db_base_migration_lock")) "and the lock was given back"))))

(deftest the-session-store-never-upserts
  (with-schema [config]
    (let [handle (db/start (assoc config :migrations :none :sessions {:lock-wait-ms 5000}))
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (let [k (store/write-session s nil {:visitor "ada"})]
          (is (= {:visitor "ada"} (store/read-session s k)) "a row, read back")
          (is (= k (store/write-session s k {:visitor "ada" :seen 2})) "updated in place under its key")
          (is (= {:visitor "ada" :seen 2} (store/read-session s k)))
          (is (nil? (store/delete-session s k)) "deleted")
          (is (= k (store/write-session s k {:visitor "resurrected"})) "a write under a gone key reports success")
          (is (= [] (rows config "SELECT id FROM db_base_sessions")) "and brought nothing back — §8's whole point"))
        (finally (db/stop handle))))))

(deftest ready-and-pool-stats-on-the-real-driver
  (with-schema [config]
    (let [handle (db/start (assoc config :migrations :none))]
      (try
        (is (true? (db/ready? handle 2)) "pgjdbc answers isValid")
        (let [stats (db/pool-stats handle)]
          (is (= [0 0] [(:active stats) (:waiting stats)]) (str "nothing borrowed: " stats))
          (is (<= 1 (:total stats) 3) (str "and the pool holds between one and [:pool :max]: " stats)))
        (finally (db/stop handle))))))

(deftest inside-a-transaction-arbitrate-is-the-wrong-tool-and-the-conditional-write-the-right-one
  ;; F2 in FRICTION.md: SQLite keeps a transaction alive after a refused statement, so the
  ;; mistake passes every test there. PostgreSQL does not.
  (with-schema [config]
    (let [handle (db/start (assoc config :migrations :none))
          ds     (:datasource handle)]
      (try
        (jdbc/execute! ds ["CREATE TABLE membership (id INTEGER NOT NULL PRIMARY KEY)"])
        (jdbc/execute! ds ["INSERT INTO membership (id) VALUES (1)"])
        (let [e (try (jdbc/with-transaction [tx ds]
                       (collision/arbitrate!
                        #(jdbc/execute-one! tx ["INSERT INTO membership (id) VALUES (1)"])
                        #(jdbc/execute-one! tx ["SELECT id FROM membership WHERE id = 1"])))
                     nil
                     (catch SQLException e e))]
          (is (= "23505" (some-> e .getSQLState)) (str "the write's own refusal leaves: " (some-> e ex-message)))
          (is (= ["25P02"] (mapv #(.getSQLState ^SQLException %) (.getSuppressed e)))
              "because the look behind it met a transaction already aborted"))
        (jdbc/with-transaction [tx ds]
          (let [conditional ["INSERT INTO membership (id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM membership WHERE id = ?)"]]
            (is (= 0 (:next.jdbc/update-count (jdbc/execute-one! tx (conj conditional 1 1))))
                "the conditional write asks its own question and refuses nothing")
            (is (= 1 (:next.jdbc/update-count (jdbc/execute-one! tx (conj conditional 2 2))))
                "so the transaction is alive for the next statement")))
        (is (= [[1] [2]] (rows config "SELECT id FROM membership ORDER BY id")) "and it committed")
        (finally (db/stop handle))))))

(defn- session-of-length [n] {:v (apply str (repeat (- n 7) "x"))})

(deftest the-portable-session-table-holds-its-bound-and-the-store-refuses-past-it
  (with-schema [config]
    (let [handle (db/start (assoc config :migrations :none :sessions {:lock-wait-ms 5000}))
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (is (= [["001-sessions"]] (rows config "SELECT id FROM db_base_migrations")) "the portable table")
        (let [k (store/write-session s nil (session-of-length 4000))]
          (is (= (session-of-length 4000) (store/read-session s k)) "the widest session fits the real column"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a session of 4001 characters is longer than the 4000"
                              (store/write-session s nil (session-of-length 4001)))
            "and one past it is this library's refusal, not the engine's")
        (finally (db/stop handle))))))

(deftest the-postgresql-dialect-gives-the-session-unbounded-text
  (with-schema [config]
    (let [handle (db/start (assoc config :migrations :none :sessions {:lock-wait-ms 5000 :dialect :postgresql}))
          s      (session/store handle {:lifetime-ms 60000 :readers {}})]
      (try
        (is (= [["001-sessions-postgresql"]] (rows config "SELECT id FROM db_base_migrations")) "the dialect's own table")
        (is (= [["text"]] (rows config "SELECT data_type FROM information_schema.columns WHERE table_name = 'db_base_sessions' AND column_name = 'data' AND table_schema = current_schema()"))
            "and PostgreSQL says the column is text")
        (let [k (store/write-session s nil (session-of-length 10000))]
          (is (= (session-of-length 10000) (store/read-session s k)) "holding what the portable table never would"))
        (finally (db/stop handle))))))
