(ns demo.routes
  "What this host serves. In this step it is one route, and its only purpose is that the
  boot is observable from outside: `/health` answers from `ready?`, which is the question
  db-base exists to answer about a pool it is holding."
  (:require [dev.arkaitz.db-base :as db]))

(defn- health
  "200 while the database answers within two seconds, 503 when it does not. `ready?`
  never throws for a database that is merely unreachable, so this needs no try."
  [db]
  (fn [_]
    (if (db/ready? db 2)
      {:status 200 :headers {"content-type" "text/plain"} :body "ok"}
      {:status 503 :headers {"content-type" "text/plain"} :body "the database is not answering"})))

(defn routes [db]
  [["/health" {:get {:handler (health db)}}]])
