(ns demo-ledger.system
  "The host's wiring. Code cannot live in EDN, so the parts of the three maps that are
  functions are built here from keys `config.edn` names."
  (:require [demo-ledger.routes :as routes]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.console :as console]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.auth-base.integrant]
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo-ledger/port [_ port] port)

(defmethod ig/init-key :demo-ledger/auth-config [_ {:keys [db port ttl-ms]}]
  {:store      (auth-jdbc/store (auth-jdbc/check! (:datasource db)))
   :deliver!   console/deliver!
   :link       {:base-url (str "http://localhost:" port) :redeem-path "/login/redeem"}
   :ttl-ms     ttl-ms
   :on-unknown (fn [identifier] (auth-jdbc/register! (:datasource db) identifier))})

(defmethod ig/init-key :demo-ledger/web-config [_ {:keys [db ceremony session-lifetime-ms secure?]}]
  {:routes      (routes/routes db ceremony)
   :sessionless (routes/sessionless db)
   :subject-fn (auth/subject-fn ceremony)
   :login-path "/login"
   :session    {:store        (session/store db {:lifetime-ms session-lifetime-ms :readers {}})
                :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})

;; Expired sessions and sign-in challenges are rows nothing else removes: every read
;; already ignores them, so this is about disk. A demo runs no scheduler, so it gives
;; them back once, as it boots — the host template sweeps them on a schedule instead.
(defmethod ig/init-key :demo-ledger/reclaimed [_ {:keys [db]}]
  {:sessions   (session/reclaim-expired! db)
   :challenges (auth-jdbc/reclaim-expired! (:datasource db) (System/currentTimeMillis))})
