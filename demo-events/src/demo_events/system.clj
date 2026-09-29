(ns demo-events.system
  "The host's wiring. Code cannot live in EDN, so the parts of the three maps that are
  functions are built here from keys `config.edn` names."
  (:require [demo-events.routes :as routes]
            [demo-events.views :as views]
            [dev.arkaitz.auth-base.console :as console]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.auth-base.web :as auth-web]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.db-base.web :as db-web]
            [dev.arkaitz.auth-base.integrant]
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo-events/port [_ port] port)

(defmethod ig/init-key :demo-events/auth-config [_ {:keys [db port ttl-ms]}]
  {:store      (auth-jdbc/store (auth-jdbc/check! (:datasource db)))
   :deliver!   console/deliver!
   :link       {:base-url (str "http://localhost:" port) :redeem-path "/login/redeem"}
   :ttl-ms     ttl-ms
   :on-unknown (fn [identifier] (auth-jdbc/register! (:datasource db) identifier))})

(defmethod ig/init-key :demo-events/web-config [_ {:keys [db ceremony session-lifetime-ms secure?]}]
  {:routes  (routes/routes db)
   :plugins [(db-web/plugin db {:session {:lifetime-ms  session-lifetime-ms
                                          :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})
             (auth-web/plugin ceremony (assoc views/auth-paths :layouts [views/shell-layout]))]
   :i18n    {:default-locale :en
             :dict           {:en {:ab {:sent-detail "In this demo it is printed to the server's console."}}}}})

;; Expired sessions and sign-in challenges are rows nothing else removes: every read
;; already ignores them, so this is about disk. A demo runs no scheduler, so it gives
;; them back once, as it boots — the host template sweeps them on a schedule instead.
(defmethod ig/init-key :demo-events/reclaimed [_ {:keys [db]}]
  {:sessions   (session/reclaim-expired! db)
   :challenges (auth-jdbc/reclaim-expired! (:datasource db) (System/currentTimeMillis))})
