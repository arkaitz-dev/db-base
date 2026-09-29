(ns demo.system
  "The host's own wiring: the components this application has, and the map web-base
  receives. Functions cannot live in EDN, so `config.edn` names `:demo/web-config` and
  this builds it — web-base's own demo does the same, and this file is the place where a
  database handle meets an HTTP stack that knows nothing about databases.

  db-base arrives as one plugin value: §8's session store and the `/health` probe, both
  over the handle Integrant puts here through `#ig/ref`."
  (:require [demo.routes :as routes]
            [dev.arkaitz.db-base.web :as db-web]
            ;; Requiring these is what installs the keys `config.edn` names. Neither
            ;; library loads the other: this host loads both.
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo/web-config [_ {:keys [db session-lifetime-ms secure?]}]
  {:routes  (routes/routes db)
   ;; What this buys, and it is the whole reason the host asked: a session that can be
   ;; ended from the server. Under the cookie store `delete-session` seals a fresh empty
   ;; value and the old one stays valid forever, so a copied cookie outlives any logout;
   ;; and the sealed payload carries no timestamp, so `Max-Age` is the only expiry there
   ;; is and an honest client is the only thing enforcing it. The store is built by the
   ;; plugin, a plain function call and not a second Integrant key: it has no lifecycle
   ;; of its own, and §10's "one key, never two" survives §8.
   :plugins [(db-web/plugin db {:session {:lifetime-ms  session-lifetime-ms
                                          ;; The cookie's expiry is a courtesy to the
                                          ;; browser; the row's decides, and it is the
                                          ;; same number so the two do not disagree.
                                          :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})]})
