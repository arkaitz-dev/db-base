(ns dev.arkaitz.db-base.integrant-test
  "SPEC §10: the optional Integrant key. Two claims that nothing else in the suite can
  make, because integrant dispatches whatever key it is handed and says nothing about
  what a library installed:

  **One key, never two.** The pool and its migrations are one component; two keys would
  let a host wire the pool and omit the migrations, deleting §7's guarantee with no
  symptom until the first request meets a missing table.

  **The key is `start` and `stop`, not a plausible map, and not a bare close.** `halt-key!` has a `:default`
  in integrant 1.0.1 that does nothing at all, so a missing method leaves `ig/halt!`
  returning happily over a pool that is still open — which is why nothing here is
  asserted by the absence of an exception."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.db-base :as db]
            ;; The namespace under test: requiring it is what installs the methods.
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.db-base.test-support :as ts]
            [integrant.core :as ig])
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.sql Connection]))

(defn- keys-of
  "Every dispatch value `multi` answers to, bar integrant's own `:default`. Not filtered
  by namespace: a second key of ours spelled `:db-base/pool` would slip through such a
  filter and take §7's guarantee with it. Nothing but this library installs a method in
  this JVM — its test classpath carries no other library that wires with Integrant — so
  what is left over is exactly what this library installed."
  [multi]
  (disj (set (keys (methods multi))) :default))

(deftest the-library-installs-exactly-one-integrant-key-with-both-of-its-methods
  ;; The planted names are ones this library would never install: `remove-method` erases
  ;; a dispatch value, not a definition, so a control that planted a name the library
  ;; really uses would delete the very method it is meant to be watching for — measured,
  ;; by two mutants that survived until these names were changed.
  (testing "control: a second key is seen, wherever it is spelled"
    (let [ours    :dev.arkaitz.db-base/planted-by-a-test
          renamed :planted-elsewhere/by-a-test]
      (try
        (defmethod ig/init-key ours [_ _] :planted)
        (defmethod ig/init-key renamed [_ _] :planted-elsewhere)
        (is (= #{:dev.arkaitz.db-base/database ours renamed} (keys-of ig/init-key))
            (str "both are counted — a second key spelled in another namespace wires a pool"
                 " with no migrations just as effectively as one spelled in ours"))
        (finally (remove-method ig/init-key ours)
                 (remove-method ig/init-key renamed)))))
  (is (= [#{:dev.arkaitz.db-base/database} #{:dev.arkaitz.db-base/database}]
         [(keys-of ig/init-key) (keys-of ig/halt-key!)])
      (str "SPEC §10: one key, never two, and both its methods. A second key lets a host"
           " wire the pool and omit the migrations; a missing halt-key! is worse than it"
           " looks, because integrant's default for it does nothing and says nothing")))

(deftest the-key-starts-a-pool-with-its-migrations-and-halting-closes-it
  (let [url    (ts/h2-memory-url ts/url-sentinel)
        config {:jdbc-url url :user ts/user-sentinel :password ts/password-sentinel
                :pool {:max 2 :timeout-ms 5000}
                :migrations {:dir "db-base-test/three" :lock-wait-ms 1000}
                :sessions :none}
        before (ts/pool-number)
        system (ig/init {:dev.arkaitz.db-base/database config})
        handle (get system :dev.arkaitz.db-base/database)
        n      (ts/pool-number)]
    (try
      (is (= (inc (or before 0)) n)
          "the key constructed a pool, rather than returning a map that looks like a handle")
      (is (= [[:datasource :migrations-applied] 3]
             [(vec (sort (keys handle))) (:migrations-applied handle)])
          "and it is `start`'s handle, reporting what this boot applied")
      (is (= [["001-a"] ["002-b"] ["003-c"]] (ts/query url "SELECT id FROM ragtime_migrations ORDER BY id"))
          (str "§7 ran through the key: the control table is read here through a connection of"
               " this test's own, not through the handle that would report it"))
      (is (= [[7 "seven"]] (ts/query url "SELECT n, label FROM m_probe"))
          "and the migrations did their work, not merely their bookkeeping")
      (is (= 2 (.getMaximumPoolSize ^HikariDataSource (:datasource handle)))
          "the pool the host asked for is the pool it got, not one the key chose")
      (is (= [[7 "seven"]]
             (with-open [^Connection c (.getConnection ^HikariDataSource (:datasource handle))
                         st (.createStatement c)
                         rs (.executeQuery st "SELECT n, label FROM m_probe")]
               (loop [acc []]
                 (if (.next rs) (recur (conj acc [(.getInt rs 1) (.getString rs 2)])) acc))))
          "the datasource the key handed over is a pool over that same database")
      (is (= [(str "HikariPool-" n ":housekeeper")]
             (filterv #(.endsWith ^String % ":housekeeper") (map #(.getName ^Thread %) (ts/hikari-threads n))))
          "precondition: its housekeeper runs, so its absence below means the halt did something")
      ;; Armed last, so the halt below meets an interrupted thread: HikariCP's own close
      ;; returns at once on one and leaves its threads running, which is why `stop` clears
      ;; the flag around it (measured, SPEC §6). A `halt-key!` that closed the pool itself
      ;; instead of calling `stop` would pass every other assertion here.
      (.interrupt (Thread/currentThread))
      (finally (ig/halt! system)))
    ;; Read, and so cleared, before the join below and before any later test sees it.
    (is (true? (Thread/interrupted))
        "halting leaves the interrupt flag as it found it, which is `stop`'s promise")
    (is (zero? (.getTotalConnections (.getHikariPoolMXBean ^HikariDataSource (:datasource handle))))
        "after halt the pool holds no connection")
    (is (instance? java.sql.SQLException
                   (ts/thrown-any #(.getConnection ^HikariDataSource (:datasource handle))))
        "and lends none: it is closed, not merely unused")
    (is (= [] (ts/threads-alive-after-join n #"" 5000))
        (str "SPEC §10: halting closed the pool, from an interrupted thread. integrant's own"
             " halt-key! default does nothing at all, and HikariCP's own close does nothing"
             " useful on an interrupted thread — so both a missing method and a halt that"
             " closes the pool itself instead of calling `stop` leave these threads alive"))
    ;; A throw above would otherwise leave this thread interrupted for every later test.
    (Thread/interrupted)))
