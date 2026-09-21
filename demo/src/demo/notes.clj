(ns demo.notes
  "The application's own storage, written with next.jdbc against the datasource db-base
  handed over. The library has no opinion here: it gives a `javax.sql.DataSource` and
  stops, which is why this namespace declares next.jdbc itself."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- ds [db] (:datasource db))

(defn list-notes [db]
  (jdbc/execute! (ds db)
                 ["SELECT id, body, written_at FROM note ORDER BY id DESC"]
                 {:builder-fn rs/as-unqualified-lower-maps}))

(defn add-note!
  "Writes one note and returns what the table then holds."
  [db body]
  (jdbc/execute-one! (ds db)
                     ["INSERT INTO note (body, written_at) VALUES (?, ?)"
                      body (System/currentTimeMillis)])
  (list-notes db))
