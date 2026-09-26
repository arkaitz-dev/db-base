(ns demo.system
  "The host's own wiring: the components this application has, and the map web-base
  receives. Functions cannot live in EDN, so `config.edn` names `:demo/web-config` and
  this builds it — web-base's own demo does the same, and this file is the place where a
  database handle meets an HTTP stack that knows nothing about databases.

  The two libraries never mention each other. web-base is given a map; db-base is given
  a map; Integrant is what puts the second's handle into the first's, and `#ig/ref` is
  the whole of the coupling."
  (:require [demo.routes :as routes]
            [dev.arkaitz.db-base.session :as session]
            ;; Requiring these is what installs the keys `config.edn` names. Neither
            ;; library loads the other: this host loads both.
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo/web-config [_ {:keys [db session-lifetime-ms secure?]}]
  {:routes      (routes/routes db)
   :sessionless (routes/sessionless db)
   ;; A store, not a key. Built by a plain function call and NOT by a second Integrant
   ;; key: the store has no lifecycle of its own — the pool it borrows from does, and
   ;; that one is already a component — and §10's "one key, never two" has to survive
   ;; §8 as much as it survived §7.
   ;;
   ;; What this buys, and it is the whole reason the host asked: a session that can be
   ;; ended from the server. Under the cookie store `delete-session` seals a fresh empty
   ;; value and the old one stays valid forever, so a copied cookie outlives any logout;
   ;; and the sealed payload carries no timestamp, so `Max-Age` is the only expiry there
   ;; is and an honest client is the only thing enforcing it.
   :session {:store        (session/store db {:lifetime-ms session-lifetime-ms :readers {}})
             ;; The cookie's own expiry is a courtesy to the browser; the row's is the
             ;; one that decides, and it is the same number so the two do not disagree.
             :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})
