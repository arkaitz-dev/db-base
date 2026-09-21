(ns demo.system
  "The host's own wiring: the components this application has, and the map web-base
  receives. Functions cannot live in EDN, so `config.edn` names `:demo/web-config` and
  this builds it — web-base's own demo does the same, and this file is the place where a
  database handle meets an HTTP stack that knows nothing about databases.

  The two libraries never mention each other. web-base is given a map; db-base is given
  a map; Integrant is what puts the second's handle into the first's, and `#ig/ref` is
  the whole of the coupling."
  (:require [demo.routes :as routes]
            ;; Requiring these is what installs the keys `config.edn` names. Neither
            ;; library loads the other: this host loads both.
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo/web-config [_ {:keys [db session-key secure?]}]
  {:routes  (routes/routes db)
   ;; A key, not a store: §8's store is what replaces this line, and until it exists a
   ;; session lives in the cookie and does not survive a restart. web-base refuses to
   ;; invent the key, which is why it comes from the environment.
   :session {:key session-key :cookie-attrs {:secure secure?}}})
