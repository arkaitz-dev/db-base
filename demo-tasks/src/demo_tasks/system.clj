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

  **What is deliberately absent: `auth/wrap-revoked`.** It is optional —
  `subject-fn` is the contract and a revoked session already yields no subject —
  and it would have to sit *inside* web-base's session middleware to see a
  session at all, which web-base's stack offers no way to do. The cost, worth
  naming because a server-side store is the first place it shows: a revoked
  session's row lingers until its expiry instead of being deleted at the next
  request. With a sealed cookie there is no row to linger, so this host is the
  first one that could have noticed."
  (:require [demo-tasks.accounts :as accounts]
            [demo-tasks.auth-store :as auth-store]
            [demo-tasks.routes :as routes]
            [dev.arkaitz.auth-base :as auth]
            [dev.arkaitz.db-base.session :as session]
            ;; Requiring these three is what installs the keys `config.edn`
            ;; names. No library loads another: this host loads all three.
            [dev.arkaitz.auth-base.integrant]
            [dev.arkaitz.db-base.integrant]
            [dev.arkaitz.web-base.integrant]
            [integrant.core :as ig]))

(defmethod ig/init-key :demo-tasks/auth-config [_ {:keys [db base-url ttl-ms]}]
  {:store    (auth-store/store db)
   ;; The console, because a demo that needed a mail server would be a demo
   ;; about mail servers. A real host swaps this one function and nothing else.
   :deliver!  (fn [identifier link]
                (println)
                (println "  a sign-in link for" identifier)
                (println " " link)
                (println))
   :link      {:base-url base-url :redeem-path "/login/redeem"}
   :ttl-ms    ttl-ms
   ;; The key this host exists to exercise: an address nobody has a record of
   ;; becomes an account here, at redemption, once a single-use token has
   ;; vouched for it. What it returns is what `subject-for` answers from then
   ;; on, which auth-base requires and which revocation depends on.
   :on-unknown (fn [identifier] (accounts/register! db identifier))})

(defmethod ig/init-key :demo-tasks/web-config [_ {:keys [db ceremony session-lifetime-ms secure?]}]
  {:routes     (routes/routes db ceremony)
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
   :session    {:store        (session/store db {:lifetime-ms session-lifetime-ms :readers {}})
                ;; The cookie's expiry is a courtesy to the browser; the row's is
                ;; the one that decides, and it is the same number so that the
                ;; two cannot disagree.
                :cookie-attrs {:secure secure? :max-age (quot session-lifetime-ms 1000)}}})
