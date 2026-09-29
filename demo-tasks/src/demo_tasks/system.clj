(ns demo-tasks.system
  "The host's own wiring: the components this application has, and the maps the
  three libraries receive.

  db-base is given a map and answers a handle; auth-base is given a map and answers a
  ceremony; web-base is given a map — with the two libraries' plugins in it — and
  answers a handler. `#ig/ref` is the whole of the coupling, and requiring each
  library's `.integrant` namespace is what installs the keys `config.edn` names. Two
  keys here are this host's, and they exist for one reason: functions and protocol
  implementations cannot live in EDN, so the parts that are data sit in the resource
  and the parts that are code sit here.

  **`auth/wrap-revoked` is route middleware** on this host's routes and on the plugin's,
  which reitit runs inside web-base's session layer, so a revoked session's row is
  deleted at its next request instead of lingering until it expires. Routes outside the
  router — `/health`, the default 404 — are not covered, and a revoked session there
  still yields no subject."
  (:require [demo-tasks.routes :as routes]
            [demo-tasks.views :as views]
            [dev.arkaitz.auth-base.console :as console]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.auth-base.web :as auth-web]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.db-base.web :as db-web]
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
  ;; The store is built once, by db-base's plugin, and handed to two places: to web-base
  ;; as the plugin's `:session`, and to the routes, which need it to end one session by
  ;; id. **That is the whole of what db-base §12 said could not be done without a
  ;; listing surface.** `delete-session` is a function of Ring's port and not a door
  ;; db-base had to open.
  ;;
  ;; No `:readers`: this session holds nothing tagged — measured in the running host,
  ;; the row reads `:ab/subject "d4dccdae-…"`, a string with a UUID's spelling. db-base
  ;; §8's reader surface therefore still has no consumer anywhere.
  (let [db-plugin (db-web/plugin db {:session {:lifetime-ms  session-lifetime-ms
                                               ;; The cookie's expiry is a courtesy to the
                                               ;; browser; the row's decides, and it is the
                                               ;; same number so the two cannot disagree.
                                               :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})]
    {:routes  (routes/routes db ceremony (get-in db-plugin [:session :store]))
     :plugins [db-plugin
               (auth-web/plugin ceremony (assoc views/auth-paths
                                                :layouts    [views/shell-layout]
                                                :on-logout  (routes/on-logout db)
                                                :on-revoke  (routes/on-revoke db)))]
     :i18n    {:default-locale :en
               :dict           {:en {:ab {:sent-detail "In this demo the link is printed to the server's console."
                                          :note        (str "Any address works: the first time you follow a link, an"
                                                            " account is created for it. The answer is the same whether"
                                                            " the address is known or not — if it were not, this page"
                                                            " would be telling strangers who has an account.")}}}}}))

;; Expired sessions and sign-in challenges are rows nothing else removes: every read
;; already ignores them, so this is about disk. A demo runs no scheduler, so it gives
;; them back once, as it boots — the host template sweeps them on a schedule instead.
(defmethod ig/init-key :demo-tasks/reclaimed [_ {:keys [db]}]
  {:sessions   (session/reclaim-expired! db)
   :challenges (auth-jdbc/reclaim-expired! (:datasource db) (System/currentTimeMillis))})
