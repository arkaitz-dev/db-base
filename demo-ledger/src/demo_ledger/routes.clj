(ns demo-ledger.routes
  "Where the three libraries meet: auth-base's routes, this host's, one layout."
  (:require [clojure.string :as str]
            [demo-ledger.accounts :as accounts]
            [demo-ledger.ledger :as ledger]
            [demo-ledger.money :as money]
            [demo-ledger.views :as views]
            [dev.arkaitz.auth-base :as auth]
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
        identifier (accounts/identifier-for db s)]
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
                                     (get-in request [:params "error"])))
      (not-found!))))

(defn- add-expense [db request]
  (let [id          (group-id request)
        description (param request "description")
        cents       (money/parse-cents (param request "amount"))]
    (cond
      (not (and description cents)) (response/see-other (str "/groups/" id "?error=amount"))
      (ledger/add-expense! db (subject request) id description cents) (response/see-other (str "/groups/" id))
      :else (not-found!))))

(defn- delete-expense [db request]
  (ledger/delete-expense! db (subject request) (get-in request [:path-params :expense]))
  (response/see-other (str "/groups/" (group-id request))))

(defn- canonical
  "auth-base's default normalisation, repeated here because the ceremony keeps its
  own private: an invitation must name the address the way the account will. A
  ceremony configured with another `:normalise` would need this changed too — see
  FRICTION.md, F4."
  [identifier]
  (str/lower-case (str/trim identifier)))

(defn- invite [db request]
  (let [id (group-id request)]
    (if (and (param request "identifier")
             (ledger/invite! db (subject request) id (canonical (param request "identifier"))))
      (response/see-other (str "/groups/" id))
      (not-found!))))

(defn- accept [db request]
  (let [s  (subject request)
        id (group-id request)]
    (if (ledger/accept! db s (accounts/identifier-for db s) id)
      (response/see-other (str "/groups/" id))
      (response/see-other "/"))))

(defn- health [db _]
  (if (db/ready? db 2)
    {:status 200 :headers {"content-type" "text/plain"} :body "ok"}
    {:status 503 :headers {"content-type" "text/plain"} :body "the database is not answering"}))

(defn routes [db ceremony]
  (let [gated (fn [m] (assoc m :wb/gate wb/subject-present?))]
    [["" {:wb/layouts [views/shell-layout]}
      (into (auth/routes ceremony {:view        views/login
                                   :login-path  "/login"
                                   :logout-path "/logout"
                                   :rate-limit  {:limit 5 :window-ms (* 15 60 1000)}})
            [["/" (gated {:get {:handler (partial home db)}})]
             ["/groups" (gated {:post {:handler (partial create-group db)}})]
             ["/groups/:id" (gated {:get {:handler (partial group-page db)}})]
             ["/groups/:id/expenses" (gated {:post {:handler (partial add-expense db)}})]
             ["/groups/:id/expenses/:expense/delete" (gated {:post {:handler (partial delete-expense db)}})]
             ["/groups/:id/invitations" (gated {:post {:handler (partial invite db)}})]
             ["/invitations/:id/accept" (gated {:post {:handler (partial accept db)}})]])]
     ["/health" {:get {:handler (partial health db)}}]]))
