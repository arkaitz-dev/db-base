(ns dev.arkaitz.db-base.session-schema-test
  "SPEC §8's half of the migration story: this library runs its own migrations, into its
  own control table, before the host's. §7's suite owns the host's run and is not
  repeated here; what these tests carry is everything that only exists because there are
  **two** runs.

  The one that matters most is order, and it is observed as a dependency rather than as a
  sequence: a host migration that writes into `db_base_sessions` cannot succeed unless
  this library's DDL already ran and committed on that database. Both tables existing and
  both counts being right are order-invariant — a swapped implementation produces exactly
  those — so neither is asserted as evidence of order.

  Every claim runs on H2 and on SQLite, a strict engine beside a permissive one (§3).
  Ask `ts/tables` with an exact name: H2 lists its own tables there too, and one of them
  is called `sessions`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.test-support :as ts])
  (:import [java.sql Connection DriverManager ResultSet SQLException]))

(def ^:private lock-table-ddl
  "The shape `start` creates when it is the one to get there first. Spelled here because
  these tests plant a row before any boot, which means creating the table themselves."
  (str "CREATE TABLE db_base_migration_lock (id VARCHAR(64) NOT NULL PRIMARY KEY,"
       " holder VARCHAR(36) NOT NULL, acquired_at BIGINT NOT NULL)"))

(def ^:private dead-holder "DEAD-HOLDER-7f3a")
(def ^:private dead-at 1700000000000)

(defn- plant-lock-row!
  "A row that a holder which never came back left behind, under the control table `id`
  names. The table is created only when it is not already there — `start` makes it on its
  first run — and that is asked as a question rather than absorbed as a caught exception,
  so a plant that fails still fails loudly."
  [url id]
  (when-not (contains? (ts/tables url) "db_base_migration_lock")
    (ts/execute! url lock-table-ddl))
  (ts/execute! url (str "INSERT INTO db_base_migration_lock (id, holder, acquired_at) VALUES ('"
                        id "', '" dead-holder "', " dead-at ")")))

(defn- fresh
  "A database of `engine`'s own, never the one the caller is already using. Chosen by
  label rather than by position: a control that picked an index would keep passing if
  `ts/engines` were ever reordered, while claiming to speak for the other engine."
  [engine]
  (second (first (filter #(= engine (first %)) (ts/engines)))))

(def ^:private long-value
  "Longer than any bounded column anyone would write by hand. Not a limit and not a
  measurement of one: its only job is to exceed a bound, so that a `data` column declared
  as a bounded `VARCHAR` instead of §8's `CLOB` cannot take it. Measured 2026-09-21: H2
  refuses this into a `VARCHAR(8)` and SQLite stores it whole — so the failure mode really
  does depend on the engine, which is §8's argument, and the strict engine of §3's pair is
  the one that carries this assertion."
  (apply str (repeat 10000 "x")))

(defn- write-session-row!
  "One row, through a prepared statement, so a value of any length reaches the column as
  itself rather than as SQL text."
  [url id data expires-at]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.prepareStatement c (str "INSERT INTO db_base_sessions (id, data, expires_at)"
                                           " VALUES (?, ?, ?)"))]
    (.setString st 1 id)
    (.setString st 2 data)
    (.setLong st 3 (long expires-at))
    (.executeUpdate st)))

(defn- sessions-config
  "The host's run turned off, so what is left is this library's own."
  [url wait-ms]
  (assoc (ts/config url "three") :migrations :none :sessions {:lock-wait-ms wait-ms}))

(defn- recorded-by-the-library [url]
  (ts/query url "SELECT id FROM db_base_migrations ORDER BY id"))

(defn- session-rows
  "The rows of `db_base_sessions`, read the way §8's store will have to read them.
  Measured, and the reason this helper exists rather than `ts/query`: H2 hands a `CLOB`
  back from `getObject` as a `java.sql.Clob` while SQLite hands back a String, so a
  reader written against either engine alone is a reader that breaks on the other."
  [url]
  (with-open [^Connection c (DriverManager/getConnection url ts/user-sentinel ts/password-sentinel)
              st (.createStatement c)
              ^ResultSet rs (.executeQuery st "SELECT id, data, expires_at FROM db_base_sessions ORDER BY id")]
    (loop [acc []]
      (if (.next rs)
        (recur (conj acc [(.getString rs 1) (.getString rs 2) (.getLong rs 3)]))
        acc))))

(deftest a-map-creates-the-table-records-its-id-and-a-second-boot-applies-none
  (doseq [[engine url] (ts/engines)]
    (is (= {:session-migrations-applied 1} (ts/boot (sessions-config url 1000)))
        (str engine ": the first boot applies this library's one migration, and the handle"
             " says so under a key of its own — `:migrations-applied` is the host's and is"
             " absent here, because this host asked for no run of its own"))
    (is (= [["001-sessions"]] (recorded-by-the-library url))
        (str engine ": recorded in this library's own control table. The count above is the"
             " library reporting on itself; this is the database, and a run that applied"
             " without recording would report 1 just the same and fail on the next boot"))
    (is (= [false true] [(contains? (ts/tables url) "ragtime_migrations")
                         (contains? (ts/tables url) "db_base_sessions")])
        (str engine ": and it recorded in ITS table only. A single shared control table"
             " passes every count above and shows up exactly here"))
    ;; The table is asked for by what §8 says it must HOLD, not by its existence: a DDL
    ;; that created `db_base_sessions` with plausible-looking wrong types is a table that
    ;; exists and a store that loses data. A short probe row cannot tell those apart,
    ;; because `getString` and `getLong` normalise every candidate type — measured: with
    ;; `'d'` and `0` in the row, changing `CLOB` to `VARCHAR(8)` left this whole suite
    ;; green.
    (write-session-row! url "k-7f3a" long-value 1700000000000)
    (is (= [["k-7f3a" long-value 1700000000000]] (session-rows url))
        (str engine ": and the row comes back as it went in. Two types are pinned by this"
             " one round trip: a bounded `VARCHAR` for the data cannot take 10,000"
             " characters, and an `INTEGER` cannot hold an epoch in milliseconds. Both"
             " survive on the permissive engine and are refused by the strict one, which"
             " is what §3 keeps a strict engine in the pair for"))
    (is (instance? SQLException (ts/thrown-any #(write-session-row! url "k-7f3a" "again" 0)))
        (str engine ": and the id is the primary key §8 says it is — a second row under one"
             " id is refused by the engine, which is the store's whole integrity. The class"
             " is asserted and not merely the presence of something: `thrown-any` answers"
             " the keyword ::no-throw when nothing throws, and that is `some?` too"))
    (is (= {:session-migrations-applied 0} (ts/boot (sessions-config url 1000)))
        (str engine ": the second boot applies none. Zero is the assertion and not a floor:"
             " re-applying would meet `CREATE TABLE db_base_sessions` on a table that is"
             " already there, which §7 turns into a failed boot"))
    (is (= [["001-sessions"]] (recorded-by-the-library url))
        (str engine ": and recorded nothing more"))
    ;; §7's rule, which CLAUDE.md lists as a trap, asked here of THIS library's run: a
    ;; boot with nothing pending never asks for the lock, so a row left behind under its
    ;; control table cannot brick an ordinary restart. Pinned for the host's run in
    ;; `migrations_test`; without this it was unpinned for the library's.
    (plant-lock-row! url "db_base_migrations")
    (is (= {:session-migrations-applied 0} (ts/boot (sessions-config url 0)))
        (str engine ": and a boot with nothing left to apply never asks for the lock, so a"
             " row a dead holder left under this library's own control table does not stop"
             " it — with a wait of 0, which would fail at once if it asked"))))

(deftest none-creates-neither-table-and-leaves-no-count-on-the-handle
  (doseq [[engine url] (ts/engines)]
    (is (= {} (ts/boot (assoc (ts/config url "three") :migrations :none :sessions :none)))
        (str engine ": the handle has no `:session-migrations-applied` at all. Asserted as"
             " the whole map, because a key holding nil would pass a `nil?` check while"
             " telling a host that no migration ran when the run never happened"))
    (is (= [false false] [(contains? (ts/tables url) "db_base_sessions")
                          (contains? (ts/tables url) "db_base_migrations")])
        (str engine ": and neither table was made. Asked by exact name: H2 has a table of"
             " its own called `sessions`, so a looser question answers yes on H2 and no on"
             " SQLite for reasons that have nothing to do with this library"))
    (testing "control: the same question on the same engine, with the run asked for"
      (let [asked (fresh engine)]
        (ts/boot (sessions-config asked 1000))
        (is (= [true true] [(contains? (ts/tables asked) "db_base_sessions")
                            (contains? (ts/tables asked) "db_base_migrations")])
            (str engine ": both are there when it is asked for, so the pair of falses above"
                 " is this library not running rather than `ts/tables` not looking"))))))

(deftest the-librarys-run-goes-first-so-the-hosts-migration-can-use-its-table
  (doseq [[engine url] (ts/engines)]
    ;; `needs-sessions` is one statement: INSERT INTO db_base_sessions. It can only
    ;; succeed if this library's DDL already ran AND committed on this database, which is
    ;; what makes this a test of order rather than of co-occurrence.
    (let [cfg (assoc (ts/config url "needs-sessions") :sessions {:lock-wait-ms 1000})]
      (is (= {:session-migrations-applied 1 :migrations-applied 1} (ts/boot cfg))
          (str engine ": both runs applied one migration"))
      (is (= [["ORDER-PROBE-7f3a"]] (ts/query url "SELECT id FROM db_base_sessions"))
          (str engine ": and the host's migration wrote into this library's table, which it"
               " could not have done had the two runs been the other way round")))
    (testing "control: that fixture really does depend on the table"
      (let [other (fresh engine)
            cfg   (assoc (ts/config other "needs-sessions") :sessions :none)]
        (is (= ["db-base: migration 001-a failed"
                {:config-key [:migrations :dir] :value "db-base-test/needs-sessions"
                 :migration-id "001-a"}]
               (ts/pair (ts/thrown #(db/start cfg))))
            (str engine ": without this library's run the very same migration fails naming"
                 " itself, so the green above is the table being there and not the fixture"
                 " succeeding for some other reason"))))))

(deftest the-two-runs-take-lock-rows-of-their-own-and-give-back-only-those
  (doseq [[engine url] (ts/engines)]
    (plant-lock-row! url "ragtime_migrations")
    (is (= {:session-migrations-applied 1} (ts/boot (sessions-config url 0)))
        (str engine ": a row held forever under the HOST's control table does not stop this"
             " library's run, because the lock is keyed by the control table's name"))
    (is (= [["ragtime_migrations" dead-holder dead-at]] (ts/lock-rows url))
        (str engine ": and the foreign row is still there, untouched, with the library's own"
             " gone. What this kills is a release that deletes without scoping to the run —"
             " one lock id shared by both, or a `DELETE` with no predicate at all. It does"
             " NOT kill a release scoped by holder alone: this boot's holder is a fresh"
             " UUID, so such a release leaves this row standing too, and the case that"
             " would catch it (one id, two holders over time) belongs to §7's suite"))))

(deftest a-row-under-a-runs-own-control-table-does-stop-that-run
  (doseq [[engine url] (ts/engines)]
    (plant-lock-row! url "db_base_migrations")
    (is (= [(str "db-base: another instance holds the migration lock: " dead-holder
                 ", taken at " dead-at " (epoch milliseconds), and 0 ms of"
                 " [:sessions :lock-wait-ms] were not enough. If that instance is gone, the"
                 " repair is: DELETE FROM db_base_migration_lock WHERE id = 'db_base_migrations'")
            {:config-key [:sessions :lock-wait-ms] :value 0
             :holder dead-holder :acquired-at dead-at}]
           (ts/pair (ts/thrown #(db/start (sessions-config url 0)))))
        (str engine ": the mirror of the test above, and the reason it is not vacuous. The"
             " refusal names this library's own key and its own control table, and carries"
             " no `:dir`: nothing the host wrote about a prefix is at fault here"))))

(deftest a-failing-library-migration-stops-the-boot-with-the-pool-closed-and-no-lock-row
  (doseq [[engine url] (ts/engines)]
    ;; A table already there under this library's name: somebody made it by hand. The
    ;; migration is data inside src, so this is how its failure is reached without
    ;; editing the code under test.
    (ts/execute! url "CREATE TABLE db_base_sessions (something_else INTEGER)")
    (is (contains? (ts/tables url) "db_base_sessions")
        (str engine ": precondition, said in its own words — the table this test plants is"
             " really there. Without it, a plant that silently did nothing would make the"
             " boot below succeed and the refusal assertion fail with a message about the"
             " refusal rather than about the arrange"))
    (let [before  (ts/pool-number)
          outcome (ts/thrown #(db/start (assoc (ts/config url "three")
                                               :sessions {:lock-wait-ms 1000})))
          n       (ts/pool-number)]
      (is (= ["db-base: migration 001-sessions failed"
              {:config-key [:sessions] :migration-id "001-sessions"}]
             (ts/pair outcome))
          (str engine ": the boot stops naming the migration and the key the host wrote to"
               " ask for the run. No `:value`: unlike the host's prefix, nothing the host"
               " configured is at fault"))
      (is (instance? SQLException (ex-cause outcome))
          (str engine ": with the engine's own refusal kept as the cause"))
      (is (= (inc (or before 0)) n)
          (str engine ": precondition — a pool was constructed, so the assertion below is"
               " about a pool that existed"))
      ;; Split by thread class on purpose, because HikariCP's own contract is split:
      ;; `shutdown` awaits the connection adder and closer, and calls `shutdownNow` on the
      ;; housekeeper WITHOUT awaiting it. Reading all three with no wait passed ten runs
      ;; out of ten here, and ten runs is a sample rather than a bound — this library's own
      ;; `close-uninterrupted!` records the asymmetry, so a red on the housekeeper would
      ;; have named a defect that was not there.
      (is (= [] (filterv #(not (str/ends-with? % ":housekeeper"))
                         (mapv #(.getName ^Thread %) (ts/hikari-threads n))))
          (str engine ": the pool was closed BEFORE the failure left. Read with no wait at"
               " all, which is what HikariCP's close promises for these two: a close moved"
               " off the failing path, onto a thread of its own or behind a delay, reds"
               " here and a join would have accepted it"))
      (is (= [] (ts/threads-alive-after-join n #":housekeeper$" 5000))
          (str engine ": and its housekeeper is gone too, given the guard its own shutdown"
               " needs — that one is interrupted rather than awaited, so the number is a"
               " hang guard and no claim above depends on it"))
      (is (= [] (ts/lock-rows url))
          (str engine ": with this library's lock row given back on the way out"))
      (is (= [false false] [(contains? (ts/tables url) "ragtime_migrations")
                            (contains? (ts/tables url) "m_probe")])
          (str engine ": and the host's run never happened. This is what `before the host's`"
               " buys: a schema this library could not make is not one the host builds on"))
      (is (= [] (recorded-by-the-library url))
          (str engine ": nothing recorded either — the control table exists, because the run"
               " got as far as the lock, and it is empty")))))

(deftest the-hosts-own-reset-leaves-this-librarys-history-standing
  (doseq [[engine url] (ts/engines)]
    (is (= {:session-migrations-applied 1 :migrations-applied 3}
           (ts/boot (assoc (ts/config url "three") :sessions {:lock-wait-ms 1000})))
        (str engine ": precondition — both runs applied, so the drop below has something to"
             " take away"))
    (ts/execute! url (str "INSERT INTO db_base_sessions (id, data, expires_at)"
                          " VALUES ('survives-7f3a', 'd', 0)"))
    ;; A host's reset: its control table and its own tables, and nothing of this library's.
    (ts/execute! url "DROP TABLE ragtime_migrations")
    (ts/execute! url "DROP TABLE m_probe")
    (is (= [["001-sessions"]] (recorded-by-the-library url))
        (str engine ": this library's history survived the host's reset, which is the whole"
             " reason §8 insists on two control tables"))
    (is (= {:session-migrations-applied 0 :migrations-applied 3}
           (ts/boot (assoc (ts/config url "three") :sessions {:lock-wait-ms 1000})))
        (str engine ": so the next boot re-applies the host's three and none of this"
             " library's. Under one shared table that boot does not merely count wrong — it"
             " throws, because `CREATE TABLE db_base_sessions` meets a table that is there"))
    (is (= [["survives-7f3a" "d" 0]] (session-rows url))
        (str engine ": and the rows this library keeps were never at risk"))))

(deftest a-history-this-version-does-not-carry-stops-the-boot-naming-the-schema
  ;; The downgrade: a database migrated by a newer db-base, then booted by an older one.
  ;; It is the only case that reads what the library's run calls its source, and §8's
  ;; second silent break-way — a library upgrade whose migration sorts below one already
  ;; applied — is the same refusal seen from the other side.
  (doseq [[engine url] (ts/engines)]
    (is (= {:session-migrations-applied 1} (ts/boot (sessions-config url 1000)))
        (str engine ": precondition — this version's own migration is applied and recorded"))
    (ts/execute! url (str "INSERT INTO db_base_migrations (id, created_at)"
                          " VALUES ('002-from-the-future', '2026-09-21T00:00:00')"))
    (is (= [(str "db-base: migration 002-from-the-future is recorded in db_base_migrations"
                 " but not found under the schema this version of db-base ships")
            {:config-key [:sessions] :migration-id "002-from-the-future"}]
           (ts/pair (ts/thrown #(db/start (sessions-config url 1000)))))
        (str engine ": an id this version has never heard of stops the boot, naming the"
             " control table it was read from and the schema it was looked for in. That"
             " phrase is what the library's run calls its source, and nothing else in the"
             " suite reads it"))
    (is (= [] (ts/lock-rows url))
        (str engine ": and the disagreement is reported before the lock, so a boot that had"
             " nothing to apply leaves nothing behind"))))
