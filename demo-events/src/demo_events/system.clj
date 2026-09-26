(ns demo-events.system
  "The host's wiring. Code cannot live in EDN, so the parts of the three maps that are
  functions are built here from keys `config.edn` names."
  (:require [demo-events.routes :as routes]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base.session :as session]
            [dev.arkaitz.auth-base.integrant]
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo-events/port [_ port] port)

(defmethod ig/init-key :demo-events/auth-config [_ {:keys [db port ttl-ms]}]
  {:store      (auth-jdbc/store (auth-jdbc/check! (:datasource db)))
   :deliver!   (fn [identifier link]
                 (println)
                 (println "  a sign-in link for" identifier)
                 (println " " link)
                 (println))
   :link       {:base-url (str "http://localhost:" port) :redeem-path "/login/redeem"}
   :ttl-ms     ttl-ms
   :on-unknown (fn [identifier] (auth-jdbc/register! (:datasource db) identifier))})

(defmethod ig/init-key :demo-events/web-config [_ {:keys [db ceremony session-lifetime-ms secure?]}]
  {:routes      (routes/routes db ceremony)
   :sessionless (routes/sessionless db)
   :subject-fn (auth/subject-fn ceremony)
   :login-path "/login"
   :session    {:store        (session/store db {:lifetime-ms session-lifetime-ms :readers {}})
                :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})
