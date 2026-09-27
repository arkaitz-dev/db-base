(ns demo-tasks.system
  "The host's own wiring: the components this application has, and the maps the
  three libraries receive.

  **None of the three mentions another.** db-base is given a map and answers a
  handle; auth-base is given a map and answers a ceremony; web-base is given a
  map and answers a handler. `#ig/ref` is the whole of the coupling, and
  requiring each library's `.integrant` namespace is what installs the keys
  `config.edn` names. Two keys here are this host's, and they exist for one
  reason: functions and protocol implementations cannot live in EDN, so the
  parts that are data sit in the resource and the parts that are code sit here.

  **`auth/wrap-revoked` is route middleware in `routes`**, which reitit runs inside
  web-base's session layer, so a revoked session's row is deleted at its next request
  instead of lingering until it expires. This docstring once said web-base's stack
  offered no place for it; that was wrong, and auth-base's README shows the route form.
  Routes outside the router — `/health`, the default 404 — are not covered, and a
  revoked session there still yields no subject."
  (:require [demo-tasks.routes :as routes]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.console :as console]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base.session :as session]
            ;; Requiring these three is what installs the keys `config.edn`
            ;; names. No library loads another: this host loads all three.
            [dev.arkaitz.auth-base.integrant]
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo-tasks/port [_ port] port)

(defmethod ig/init-key :demo-tasks/auth-config [_ {:keys [db port ttl-ms]}]
  ;; The store is auth-base's own, over the pool db-base opened; this host keeps
  ;; the three tables as its migrations 001–003, copied from `auth-jdbc/ddl`, and
  ;; `check!` is what makes a copy that drifted fail here rather than at a login.
  {:store    (auth-jdbc/store (auth-jdbc/check! (:datasource db)))
   ;; The console, because a demo that needed a mail server would be a demo
   ;; about mail servers. A real host swaps this one function and nothing else.
   :deliver!   console/deliver!
   ;; The port the server listens on, read from the same key the server reads,
   ;; so moving one cannot leave links pointing at the other.
   :link      {:base-url (str "http://localhost:" port) :redeem-path "/login/redeem"}
   :ttl-ms    ttl-ms
   ;; The key this host exists to exercise: an address nobody has a record of
   ;; becomes an account here, at redemption, once a single-use token has
   ;; vouched for it. What it returns is what `subject-for` answers from then
   ;; on, which auth-base requires and which revocation depends on.
   :on-unknown (fn [identifier] (auth-jdbc/register! (:datasource db) identifier))})

(defmethod ig/init-key :demo-tasks/web-config [_ {:keys [db ceremony session-lifetime-ms secure?]}]
  ;; Built once and handed to two places: to web-base as `:session :store`, and
  ;; to the routes, which need it to end one session by id. **That is the whole
  ;; of what db-base §12 said could not be done without a listing surface.** The
  ;; host constructed the store, so the host holds it; `delete-session` is a
  ;; function of Ring's port and not a door db-base had to open.
  (let [store (session/store db {:lifetime-ms session-lifetime-ms :readers {}})]
    {:routes      (routes/routes db ceremony store)
     :sessionless (routes/sessionless db)
     :subject-fn (auth/subject-fn ceremony)
     :login-path "/login"
     ;; A store, not a key: it has no lifecycle of its own — the pool it borrows
     ;; from does, and that one is already a component — and db-base's "one key,
     ;; never two" has to survive being used as much as being specified.
     ;;
     ;; `:readers {}` because this session holds nothing tagged — measured in the
     ;; running host, the row reads `:ab/subject "d4dccdae-…"`, a string with a
     ;; UUID's spelling. db-base §8's reader surface therefore still has no
     ;; consumer anywhere, which is worth knowing about that library rather than
     ;; hiding behind a `{}` that looks like a decision.
     :session    {:store        store
                  ;; The cookie's expiry is a courtesy to the browser; the row's
                  ;; is the one that decides, and it is the same number so that
                  ;; the two cannot disagree.
                  :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}}))
