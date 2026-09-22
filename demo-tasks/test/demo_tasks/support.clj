(ns demo-tasks.support
  "What every suite here needs: a database of its own, and a way to read it that
  does not go through the code under test.

  **The second connection is the point.** A test that verifies a write by
  calling the function that performed it is asking the same code twice and
  believing it the second time. Everything below reads with next.jdbc against a
  datasource of the test's own, so what it observes is what another process
  would see."
  (:require [clojure.java.io :as io]
            [dev.arkaitz.db-base :as db]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn temp-db-path []
  (let [file (java.io.File/createTempFile "demo-tasks-" ".db")]
    (.delete file)
    (.deleteOnExit file)
    (.getAbsolutePath file)))

(defn delete-db!
  "The file and the three siblings SQLite may leave beside it, each named in
  full: a cleanup written as a pattern can remove something it was not shown."
  [path]
  (doseq [suffix ["" "-journal" "-wal" "-shm"]]
    (.delete (io/file (str path suffix)))))

(defn config
  "What this host hands `db-base/start`, over `path` rather than the file its
  own resource names."
  [path]
  {:jdbc-url   (str "jdbc:sqlite:" path)
   :user       ""
   :password   ""
   :pool       {:max 2 :timeout-ms 5000}
   :migrations {:dir "demo-tasks/migration" :lock-wait-ms 5000}
   :sessions   {:lock-wait-ms 5000}})

(defn datasource [path] (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path)}))

(defn rows
  "Every row of `sql`, as vectors, through a connection of the test's own."
  [path sql & params]
  (vec (rest (jdbc/execute! (datasource path) (into [sql] params) {:builder-fn rs/as-arrays}))))

(defn one [path sql & params] (ffirst (apply rows path sql params)))

(defn with-db*
  "Boots the host's database over a temporary file, hands the handle and the
  path to `f`, and takes both down afterwards however it ends."
  [f]
  (let [path (temp-db-path)]
    (try
      (let [handle (db/start (config path))]
        (try (f handle path)
             (finally (db/stop handle))))
      (finally (delete-db! path)))))

(defmacro with-db
  "`(with-db [db path] …)` — the boot, the teardown and the temporary file."
  [[db path] & body]
  `(with-db* (fn [~db ~path] ~@body)))
