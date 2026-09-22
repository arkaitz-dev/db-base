(ns demo-tasks.routes
  "The one file where all three libraries are named together, which is the
  clearest measure of how much a host actually has to do.

  auth-base's `routes` hands back reitit route data — plain vectors, and it
  brings no reitit dependency to do it — so the host nests them under its own
  layout beside its own routes. Neither library knows the other exists; this
  vector is the whole of the meeting."
  (:require [demo-tasks.accounts :as accounts]
            [demo-tasks.tasks :as tasks]
            [demo-tasks.views :as views]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]))

(defn- home
  "The list, for whoever is asking. `:wb/subject` is web-base's, and it holds
  whatever this host's `subject-fn` answered — which is auth-base's, which is
  the subject db-base's tables hold. Three libraries in one line, and none of
  them named each other to get here."
  [db request]
  (let [subject (:wb/subject request)]
    (response/ok (views/tasks-page request
                                   (accounts/identifier-for db subject)
                                   (tasks/list-tasks db subject)))))

(defn- add [db request]
  (tasks/add-task! db (:wb/subject request) (get-in request [:params "body"]))
  (response/see-other "/"))

(defn- rename [db request]
  (tasks/rename-task! db (:wb/subject request)
                      (get-in request [:path-params :id])
                      (get-in request [:params "body"]))
  (response/see-other "/"))

(defn- toggle [db request]
  (tasks/toggle-task! db (:wb/subject request) (get-in request [:path-params :id]))
  (response/see-other "/"))

(defn- remove-task [db request]
  (tasks/delete-task! db (:wb/subject request) (get-in request [:path-params :id]))
  (response/see-other "/"))

(defn- revoke
  "Ends every session this subject has, anywhere. auth-base does it by moving a
  generation on the subject rather than by enumerating sessions — which is why
  it works over any store, the sealed cookie included."
  [ceremony request]
  (when-let [subject (:wb/subject request)]
    (auth/revoke! ceremony subject))
  (response/see-other "/login"))

(defn- health
  "200 while the database answers within two seconds, 503 when it does not.
  `ready?` never throws for a database that is merely unreachable, so this
  needs no try."
  [db _request]
  (if (db/ready? db 2)
    {:status 200 :headers {"content-type" "text/plain"} :body "ok"}
    {:status 503 :headers {"content-type" "text/plain"} :body "the database is not answering"}))

(defn- owned
  "The routes only a signed-in person reaches. `:wb/gate` is route data rather
  than middleware, so the guard is visible beside the route it guards instead
  of being somewhere up a stack."
  [db ceremony]
  [["/" {:wb/gate wb/subject-present?
         :get {:handler (partial home db)}}]
   ["/tasks" {:wb/gate wb/subject-present?
              :post {:handler (partial add db)}}]
   ["/tasks/:id" {:wb/gate wb/subject-present?
                  :post {:handler (partial rename db)}}]
   ["/tasks/:id/toggle" {:wb/gate wb/subject-present?
                         :post {:handler (partial toggle db)}}]
   ["/tasks/:id/delete" {:wb/gate wb/subject-present?
                         :post {:handler (partial remove-task db)}}]
   ["/revoke" {:wb/gate wb/subject-present?
               :post {:handler (partial revoke ceremony)}}]])

(defn routes
  "auth-base's four routes and this host's six, under one layout — plus
  `/health`, outside it, because a probe wants a status line and not a page."
  [db ceremony]
  [["" {:wb/layouts [views/shell-layout]}
    (into (auth/routes ceremony {:view         views/login
                                 :login-path   "/login"
                                 :logout-path  "/logout"
                                 :after-login  "/"
                                 :after-logout "/login"
                                 ;; Keyed by source and never by address: a limit
                                 ;; counted per address would answer differently
                                 ;; for one somebody had just asked about, and
                                 ;; would let anyone lock a known user out of
                                 ;; their own login by spending their allowance.
                                 :rate-limit   {:limit 5 :window-ms (* 15 60 1000)}})
          (owned db ceremony))]
   ["/health" {:get {:handler (partial health db)}}]])
