(ns demo-tasks.cookie-control-test
  "The control, and the reason `ending-one-session-leaves-the-others-working` is
  an observation rather than an assertion.

  **The same host, the same handler, the same routes — only the session storage
  changed.** There is no switch in the product for this: it builds web-base's
  handler over the very map the host's own component produced, with `:session`
  replaced by a signing key. If a sealed cookie could not be told from a row,
  everything here would be green too, and the seam test would be proving
  nothing about db-base §8.

  What this namespace expects is mostly **success**: registration, tasks,
  ownership and 'log out everywhere' all work over a cookie, because none of
  them needs the session to be somewhere a server can reach. The one that does
  not work is the one the feature was built for, and that failure is asserted
  positively rather than left as an absence."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hosts.support :as support :refer [GET POST browser landed session-key-of sign-in!]]
            [demo-tasks.system]
            [dev.arkaitz.web-base :as wb]
            [integrant.core :as ig]))

(defn- with-cookie-host*
  [f]
  (let [path (support/temp-db-path)]
    (try
      ;; The database still exists and still holds accounts, tasks and
      ;; generations. Only where the SESSION lives is different, which is what
      ;; makes this a control for one variable rather than a different host.
      (let [system (ig/init (support/host-config path) [:dev.arkaitz.auth-base/ceremony])
            built  (ig/init-key :demo-tasks/web-config
                                {:db                  (get system :dev.arkaitz.db-base/database)
                                 :ceremony            (get system :dev.arkaitz.auth-base/ceremony)
                                 :session-lifetime-ms support/session-lifetime-ms
                                 :secure?             false})
            app    (wb/handler
                    (assoc built :session
                           ;; The host's own cookie attributes are carried over, so
                           ;; "only the store changed" describes what this does
                           ;; rather than hoping for it.
                           {:key          "AAECAwQFBgcICQoLDA0ODw=="
                            :cookie-attrs (get-in built [:session :cookie-attrs])}))]
        (try (f app path) (finally (ig/halt! system))))
      (finally (support/delete-db! path)))))

(defmacro ^:private with-cookie-host
  [[app path] & body]
  `(with-cookie-host* (fn [~app ~path] ~@body)))

(deftest everything-that-does-not-need-a-row-still-works-over-a-cookie
  (with-cookie-host [app path]
    (let [jar (browser)]
      (sign-in! app path jar "ada@example.test")
      (testing "registration, which is the database's and not the session's"
        (is (= [["ada@example.test"]] (support/rows path "SELECT identifier FROM account"))
            "the account came into being at redemption, exactly as it does over a row")
        (is (= [] (support/sessions path))
            (str "and not one session row exists — which is the precondition that says this"
                 " really is the cookie store and not the host's own")))
      (testing "and the application itself"
        (POST app jar "/tasks" {"body" "water the plants"})
        (is (str/includes? (str (:body (GET app jar "/"))) "water the plants")
            "a task is written and listed")
        (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM task"))
            "and it is a row, because tasks were never the session's business")))))

(deftest logging-out-everywhere-works-over-a-cookie-too
  ;; This is the trap the seam test would fall into if it treated revocation as
  ;; evidence for §8. auth-base moves a generation on the SUBJECT, so it reaches
  ;; every session of theirs without any store being able to enumerate them —
  ;; which is exactly why it is not the feature that needed a row.
  (with-cookie-host [app path]
    (let [here (browser) there (browser)]
      (sign-in! app path here "ada@example.test")
      (sign-in! app path there "ada@example.test")
      (is (= [200 "/"] (landed app there "/"))
          "precondition: the second browser is signed in")
      (POST app here "/revoke" {})
      (is (= [200 "/login"] (landed app there "/"))
          (str "and revoking ends it at its next request, with the session sealed in its own"
               " browser and no server anywhere holding a copy"))
      (is (= [[1]] (support/rows path "SELECT generation FROM account_generation"))
          "because what moved was a number on the subject, in a table"))))

(deftest ending-one-session-does-NOT-end-it-over-a-cookie
  ;; The whole point of the control. Over db-base's store this is the feature;
  ;; here the same request, on the same host, through the same handler, leaves
  ;; the other browser signed in — because `delete-session` on a sealed cookie
  ;; can only hand back a fresh empty one, and the value the browser is holding
  ;; stays valid until it expires.
  (with-cookie-host [app path]
    (let [here (browser) there (browser)]
      (sign-in! app path here "ada@example.test")
      (sign-in! app path there "ada@example.test")
      (let [other (session-key-of there)]
        (is (some? other) "precondition: the other browser is holding a session cookie")
        (is (= [200 "/"] (landed app there "/"))
            "precondition: and it works")
        (GET app here "/sessions")
        (POST app here (str "/sessions/" other "/end") {})
        (is (= [200 "/"] (landed app there "/"))
            (str "STILL signed in. This is the assertion the seam test's is measured against:"
                 " the same call that ends a row cannot end a sealed value, so a green here"
                 " and a red there is the difference db-base §8 exists to make"))))))
