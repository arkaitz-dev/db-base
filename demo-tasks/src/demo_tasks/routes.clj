(ns demo-tasks.routes
  "The one file where all three libraries are named together, which is the
  clearest measure of how much a host actually has to do.

  auth-base's `routes` hands back reitit route data — plain vectors, and it
  brings no reitit dependency to do it — so the host nests them under its own
  layout beside its own routes. Neither library knows the other exists; this
  vector is the whole of the meeting."
  (:require [demo-tasks.devices :as devices]
            [demo-tasks.tasks :as tasks]
            [demo-tasks.views :as views]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [ring.middleware.session.store :as session-store]))

(defn- home
  "The list, for whoever is asking. `:wb/subject` is web-base's, and it holds
  whatever this host's `subject-fn` answered — which is auth-base's, which is
  the subject db-base's tables hold. Three libraries in one line, and none of
  them named each other to get here.

  It also notes the session this request arrived on. Here rather than at login,
  because at the login request `:session/key` still names the session being
  replaced: Ring mints the new one inside `write-session` and hands it to the
  response, never to the handler."
  [db request]
  (let [subject (:wb/subject request)]
    (devices/seen! db subject (:session/key request) (get-in request [:headers "user-agent"]))
    (response/ok (views/tasks-page request
                                   (auth-jdbc/identifier-for (:datasource db) subject)
                                   (tasks/list-tasks db subject)))))

(defn- sessions
  "Everywhere this person is signed in. The current one is marked, which is the
  only thing that makes the list actionable — ending one you are not looking
  through is the whole feature."
  [db request]
  (response/ok (views/sessions-page request
                                    (devices/list-devices db (:wb/subject request))
                                    (:session/key request))))

(defn- end-session
  "Ends one session of this subject's and leaves the others alone.

  Ownership decides first: `forget!` carries the subject in its WHERE clause, so
  a session id that is not this person's changes no row and the store is never
  asked. Only then is the session itself deleted — through `delete-session`, a
  function of Ring's port, on the store this host built itself. db-base is not
  asked to list anything and `db_base_sessions` is never read."
  [db store request]
  (let [subject    (:wb/subject request)
        session-id (get-in request [:path-params :id])]
    (when (= 1 (devices/forget! db subject session-id))
      (session-store/delete-session store session-id))
    (response/see-other "/sessions")))

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
  it works over any store, the sealed cookie included.

  The host's own records go in the same act. Leaving them would show somebody a
  list of places they are signed in when they are signed in nowhere."
  [db ceremony request]
  (when-let [subject (:wb/subject request)]
    (auth/revoke! ceremony subject)
    (devices/forget-all! db subject))
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
  "The routes only a signed-in person reaches, under one gated parent: the gate is
  route data, so every route nested here inherits it, and one added later is private
  without anybody remembering to say so."
  [db ceremony store]
  [["" {:wb/gate wb/subject-present?}
    ["/" {:get {:handler (partial home db)}}]
    ["/sessions" {:get {:handler (partial sessions db)}}]
    ["/sessions/:id/end" {:post {:handler (partial end-session db store)}}]
    ["/tasks" {:post {:handler (partial add db)}}]
    ["/tasks/:id" {:post {:handler (partial rename db)}}]
    ["/tasks/:id/toggle" {:post {:handler (partial toggle db)}}]
    ["/tasks/:id/delete" {:post {:handler (partial remove-task db)}}]
    ["/revoke" {:post {:handler (partial revoke db ceremony)}}]]])

(defn- auth-routes
  "auth-base's own four routes, with its logout wrapped so that this host forgets
  its record of the device in the same act.

  `auth/routes` would hand back exactly this vector; building it from
  `auth/handlers` is what makes room for the wrapper, and the shape is copied
  from that function so the two cannot drift apart without this comment being
  wrong. The wrapper runs BEFORE the logout, because it needs `:session/key` —
  which names the session about to be deleted — and `:wb/subject`, which the
  response is about to take away.

  **Why it is worth the extra six lines.** Without it, logging out deletes the
  session row and leaves this host's record of it behind, so the next visit
  lists a device that names nothing. Harmless to end, confusing to read, and a
  poor advertisement for the one feature that justifies a server-side session."
  [db ceremony opts]
  (let [{:keys [paths form issue redeem logout]} (auth/handlers ceremony opts)]
    [[(:login paths)  {:get {:handler form} :post {:handler issue}}]
     [(:redeem paths) {:get {:handler redeem}}]
     [(:logout paths) {:post {:handler (fn [request]
                                         (devices/forget! db
                                                          (:wb/subject request)
                                                          (:session/key request))
                                         (logout request))}}]]))

(defn routes
  "auth-base's four routes and this host's eight, under one layout. `/health` is
  `sessionless`'s, outside the session: a probe wants a status line, not a page."
  [db ceremony store]
  [["" {:wb/layouts [views/shell-layout]}
    (into (auth-routes db ceremony {:view         views/login
                                    :login-path   "/login"
                                    :logout-path  "/logout"
                                    :after-login  "/"
                                    :after-logout "/login"
                                    ;; Keyed by source and never by address: a
                                    ;; limit counted per address would answer
                                    ;; differently for one somebody had just
                                    ;; asked about, and would let anyone lock a
                                    ;; known user out of their own login by
                                    ;; spending their allowance.
                                    :rate-limit {:limit 5 :window-ms (* 15 60 1000)}})
          (owned db ceremony store))]])

(defn sessionless
  "What web-base answers before the session: a probe must not need one, and with a
  session in a row it could not get its answer out when the pool was down."
  [db]
  {"/health" (partial health db)})
