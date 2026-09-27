(ns demo-ledger.routes
  "Where the three libraries meet: auth-base's routes, this host's, one layout."
  (:require [clojure.string :as str]
            [demo-ledger.ledger :as ledger]
            [demo-ledger.money :as money]
            [demo-ledger.views :as views]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.error :as error]
            [dev.arkaitz.web-base.response :as response]))

(defn- subject [request] (:wb/subject request))

(defn- param [request k]
  (some-> (get-in request [:params k]) str/trim not-empty))

(defn- group-id [request] (get-in request [:path-params :id]))

(defn- not-found!
  "The same answer for a group that does not exist and for one that is not the
  asker's: telling them apart would confirm, to anyone guessing ids, which are real."
  []
  (error/throw! {:status 404}))

(defn- home [db request]
  (let [s (subject request)
        identifier (auth-jdbc/identifier-for (:datasource db) s)]
    (response/ok (views/home request identifier (ledger/groups-of db s)
                             (ledger/invitations-for db identifier)))))

(defn- create-group [db request]
  (if-let [group-name (param request "name")]
    (response/see-other (str "/groups/" (ledger/create-group! db (subject request) group-name)))
    (response/see-other "/")))

(defn- group-page [db request]
  (let [s  (subject request)
        id (group-id request)]
    (if-let [group (ledger/group-for db s id)]
      (response/ok (views/group-page request group
                                     (ledger/members db s id)
                                     (ledger/expenses db s id)
                                     (ledger/balances db s id)
                                     (:wb/form request)))
      (not-found!))))

(defn- add-expense [db request]
  (let [id          (group-id request)
        description (param request "description")
        cents       (money/parse-cents (param request "amount"))]
    (cond
      ;; The group's own page again, with what was typed and why it was refused —
      ;; a 422 at this URL, where a redirect would have lost the values.
      (not (and description cents)) (wb/rerender request (str "/groups/" id)
                                                 {:values {:description (param request "description")
                                                           :amount      (param request "amount")}
                                                  :errors #{:amount}})
      (ledger/add-expense! db (subject request) id description cents) (response/see-other (str "/groups/" id))
      :else (not-found!))))

(defn- delete-expense [db request]
  (ledger/delete-expense! db (subject request) (get-in request [:path-params :expense]))
  (response/see-other (str "/groups/" (group-id request))))

(defn- invite
  "The address is stored the way the ceremony will ask for the account — through
  its own `normalise`, so a host that configured another rule changes it once."
  [db ceremony request]
  (let [id (group-id request)]
    (if (and (param request "identifier")
             (ledger/invite! db (subject request) id (auth/normalise ceremony (param request "identifier"))))
      (response/see-other (str "/groups/" id))
      (not-found!))))

(defn- accept [db request]
  (let [s  (subject request)
        id (group-id request)]
    (if (ledger/accept! db s (auth-jdbc/identifier-for (:datasource db) s) id)
      (response/see-other (str "/groups/" id))
      (response/see-other "/"))))

(defn- health [db _]
  (if (db/ready? db 2)
    {:status 200 :headers {"content-type" "text/plain"} :body "ok"}
    {:status 503 :headers {"content-type" "text/plain"} :body "the database is not answering"}))

(defn routes [db ceremony]
  [["" {:wb/layouts [views/shell-layout]}
    (into (auth/routes ceremony {:view        views/login
                                 :login-path  "/login"
                                 :logout-path "/logout"
                                 :rate-limit  {:limit 5 :window-ms (* 15 60 1000)}})
          [["" {:wb/gate wb/subject-present?}
            ["/" {:get {:handler (partial home db)}}]
            ["/groups" {:post {:handler (partial create-group db)}}]
            ["/groups/:id" {:get {:handler (partial group-page db)}}]
            ["/groups/:id/expenses" {:post {:handler (partial add-expense db)}}]
            ["/groups/:id/expenses/:expense/delete" {:post {:handler (partial delete-expense db)}}]
            ["/groups/:id/invitations" {:post {:handler (partial invite db ceremony)}}]
            ["/invitations/:id/accept" {:post {:handler (partial accept db)}}]]])]])

(defn sessionless
  "What web-base answers before the session: a probe must not need one, and with a
  session in a row it could not get its answer out when the pool was down."
  [db]
  {"/health" (partial health db)})
