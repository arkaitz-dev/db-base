(ns demo-tasks.routes
  "This host's own routes, and the two hooks it hands auth-base's plugin so that its
  record of a device goes when the session does. Signing in and out is the plugin's."
  (:require [demo-tasks.devices :as devices]
            [demo-tasks.tasks :as tasks]
            [demo-tasks.views :as views]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
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

(defn- body-of
  "The submitted body, or nil when the parameter is missing or longer than `task.body`'s
  200 — counted as Java counts a String, which never undercounts PostgreSQL's VARCHAR:
  SQLite would store it whole and PostgreSQL refuse it with a 500, so the host decides
  first and writes nothing."
  [request]
  (let [body (get-in request [:params "body"])]
    (when (and (string? body) (<= (count body) 200)) body)))

(defn- add [db request]
  (when-let [body (body-of request)]
    (tasks/add-task! db (:wb/subject request) body))
  (response/see-other "/"))

(defn- rename [db request]
  (when-let [body (body-of request)]
    (tasks/rename-task! db (:wb/subject request) (get-in request [:path-params :id]) body))
  (response/see-other "/"))

(defn- toggle [db request]
  (tasks/toggle-task! db (:wb/subject request) (get-in request [:path-params :id]))
  (response/see-other "/"))

(defn- remove-task [db request]
  (tasks/delete-task! db (:wb/subject request) (get-in request [:path-params :id]))
  (response/see-other "/"))

(defn on-logout
  "auth-base's `:on-logout`: this host forgets its record of the device in the same act.
  Handed the request before the session goes, because it needs `:session/key`, which
  names the session about to be deleted, and `:wb/subject`.

  **Why it is worth the line.** Without it, logging out deletes the session row and
  leaves this host's record of it behind, so the next visit lists a device that names
  nothing."
  [db]
  (fn [request]
    (devices/forget! db (:wb/subject request) (:session/key request))))

(defn on-revoke
  "auth-base's `:on-revoke`: every session of this subject has ended, anywhere — auth-base
  moves a generation rather than enumerating sessions, which is why it works over any
  store — and this host's records of them go in the same act. Leaving them would show
  somebody a list of places they are signed in when they are signed in nowhere."
  [db]
  (fn [_request subject]
    (devices/forget-all! db subject)))

(defn- owned
  "The routes only a signed-in person reaches, under one gated parent: the gate is
  route data, so every route nested here inherits it, and one added later is private
  without anybody remembering to say so."
  [db store]
  [["" {:wb/gate wb/subject-present?}
    ["/" {:get {:handler (partial home db)}}]
    ["/sessions" {:get {:handler (partial sessions db)}}]
    ["/sessions/:id/end" {:post {:handler (partial end-session db store)}}]
    ["/tasks" {:post {:handler (partial add db)}}]
    ["/tasks/:id" {:post {:handler (partial rename db)}}]
    ["/tasks/:id/toggle" {:post {:handler (partial toggle db)}}]
    ["/tasks/:id/delete" {:post {:handler (partial remove-task db)}}]]])

(defn routes
  "This host's eight routes, under its layout. Sign-in, sign-out, sign out everywhere and
  `/health` are the plugins'."
  [db ceremony store]
  [["" {:wb/layouts  [views/shell-layout]
        ;; Route middleware, so reitit runs it inside web-base's session layer: a
        ;; revoked session's row is deleted at its next request instead of lingering
        ;; until it expires. The plugin puts the same on its own routes.
        :middleware [[auth/wrap-revoked ceremony]]}
    (owned db store)]])
