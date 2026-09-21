(ns demo.handlers
  "Request in, response out. Every handler takes the database handle db-base returned,
  which is what a host does with it: hold it, and hand it to whatever needs a connection."
  (:require [demo.notes :as notes]
            [demo.views :as views]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.web-base.response :as response]))

(defn home [db request]
  (response/ok (views/notes-page (notes/list-notes db) (:migrations-applied db 0) request)))

(defn create [db request]
  (notes/add-note! db (get-in request [:params "body"]))
  (response/see-other "/"))

(defn health
  "200 while the database answers within two seconds, 503 when it does not. `ready?`
  never throws for a database that is merely unreachable, so this needs no try."
  [db _request]
  (if (db/ready? db 2)
    {:status 200 :headers {"content-type" "text/plain"} :body "ok"}
    {:status 503 :headers {"content-type" "text/plain"} :body "the database is not answering"}))
