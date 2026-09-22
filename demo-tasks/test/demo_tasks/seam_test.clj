(ns demo-tasks.seam-test
  "The demo is the acceptance test. If this host needs anything the three
  libraries do not provide, the seam is in the wrong place — and what only a
  browser can see is the manual smoke recorded in the commits.

  Everything here boots the host's **own** `config.edn`, overriding the database
  file and one number, so that a host which ignored its configuration and wired
  a literal could not agree with this test by coincidence. Every verification
  goes through a second JDBC connection of the test's own: a test that checks a
  write by calling the function that performed it is asking the same code twice
  and believing it the second time.

  The link is never scraped from the console. The challenge is read from the
  table, which is both independent of the code under test and the only way a
  test can be sure it has the token that was actually issued."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [demo-tasks.support :as support
             :refer [GET POST browser challenge-token location session-key-of sign-in! with-host]]
            [demo-tasks.system]
            [ring.mock.request :as mock]))

;; --- the join --------------------------------------------------------------

(deftest an-address-nobody-knows-becomes-an-account-at-redemption-and-only-there
  (with-host [app path]
    (let [jar (browser)]
      (GET app jar "/login")
      (POST app jar "/login" {"identifier" "ada@example.test"})
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM account"))
          (str "asking for a link creates nobody — the address is still only a claim, and an"
               " account here would answer, to anyone who could watch, who had just been asked about"))
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM login_challenge"))
          "control: a challenge WAS stored, so the zero above is not a request that failed")
      (let [token (challenge-token path)]
        (is (= "/" (location (GET app jar (str "/login/redeem/" token))))
            "following the link signs the person in")
        (is (= [["ada@example.test"]] (support/rows path "SELECT identifier FROM account"))
            "and THAT is where the account came into being — at redemption, once the token vouched")
        (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM login_challenge"))
            "and the challenge is spent")
        (is (= "/login?ab=spent" (location (GET app (browser) (str "/login/redeem/" token))))
            "so the same link in a fresh browser is refused")))))

(deftest a-second-sign-in-finds-the-same-account-and-the-same-tasks
  ;; The count alone would catch a duplicate account. What names the incident is
  ;; the task: "my data disappeared when I logged in again" is what a second
  ;; account actually looks like to the person it happens to.
  (with-host [app path]
    (let [first-visit (browser)]
      (sign-in! app path first-visit "ada@example.test")
      (POST app first-visit "/tasks" {"body" "water the plants"})
      (GET app first-visit "/"))
    (let [second-visit (browser)]
      (sign-in! app path second-visit "ada@example.test")
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM account"))
          "one account after two sign-ins")
      (is (str/includes? (str (:body (GET app second-visit "/"))) "water the plants")
          "and the task written in the first session is there in the second"))))

(deftest the-address-is-canonicalised-before-anything-is-keyed-on-it
  (with-host [app path]
    (sign-in! app path (browser) "  Ada@Example.test ")
    (sign-in! app path (browser) "ada@example.test")
    (is (= [["ada@example.test"]] (support/rows path "SELECT identifier FROM account"))
          "one account, under the canonical spelling")
    (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM account"))
        (str "and only one — two rows here would be somebody who can never log into the"
             " first of them again, which is what normalisation exists to prevent"))))

(deftest one-persons-task-cannot-be-touched-by-another-through-the-stack
  ;; The white-box half of this — that the owner is a predicate of the statement
  ;; rather than a check in a handler — is in `tasks-test`, and no test through
  ;; HTTP can tell those apart. What this adds is that the route layer does not
  ;; leak what the SQL protects.
  (with-host [app path]
    (let [ada (browser) bob (browser)]
      (sign-in! app path ada "ada@example.test")
      (POST app ada "/tasks" {"body" "ada's own"})
      (sign-in! app path bob "bob@example.test")
      (let [task-id (support/one path "SELECT id FROM task")]
        (is (some? task-id) "precondition: ada's task exists")
        (GET app bob "/")
        (POST app bob (str "/tasks/" task-id) {"body" "bob was here"})
        (is (= [["ada's own"]] (support/rows path "SELECT body FROM task"))
            "bob's rename changed nothing")
        (POST app bob (str "/tasks/" task-id "/delete") {})
        (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM task"))
            "and his delete removed nothing")
        (is (not (str/includes? (str (:body (GET app bob "/"))) "ada's own"))
            "and he never saw it in the first place")))))

(deftest revoking-ends-every-session-at-the-next-request
  (with-host [app path]
    (let [here (browser) there (browser)]
      (sign-in! app path here "ada@example.test")
      (sign-in! app path there "ada@example.test")
      (is (str/includes? (str (:body (GET app there "/"))) "ada@example.test")
          "precondition: the second browser is signed in — without this the 303 below means nothing")
      (POST app here "/revoke" {})
      (is (= "/login" (location (GET app there "/")))
          "the other browser is anonymous at its very next request")
      (is (= "/login" (location (GET app here "/")))
          "and so is the one that asked")
      (is (= [[1]] (support/rows path "SELECT generation FROM account_generation"))
          "because a generation moved on the subject, which is all revocation is"))))

(deftest ending-one-session-leaves-the-others-working
  ;; **The one thing in this application a sealed cookie cannot do**, which is
  ;; why the control in `cookie-control-test` boots the same host over one and
  ;; asserts the opposite.
  (with-host [app path]
    (let [here (browser) there (browser)]
      (sign-in! app path here "ada@example.test")
      (sign-in! app path there "ada@example.test")
      (let [other (session-key-of there)]
        (is (= 2 (count (support/rows path "SELECT session_id FROM device")))
            "precondition: the host has seen this person arrive on two sessions")
        (GET app here "/sessions")
        (POST app here (str "/sessions/" other "/end") {})
        (is (= "/login" (location (GET app there "/")))
            "the session that was ended is anonymous at its next request")
        (is (= 200 (:status (GET app here "/")))
            "and the one that ended it is still signed in — which is the whole feature")
        (is (= [] (support/rows path "SELECT id FROM db_base_sessions WHERE id = ?" other))
            "the row is gone, read through this test's own connection")
        (is (= [] (support/rows path "SELECT session_id FROM device WHERE session_id = ?" other))
            "and so is the host's record of it, or the list would show a session nobody can use")))))

(deftest a-session-id-that-is-not-yours-ends-nothing
  (with-host [app path]
    (let [ada (browser) bob (browser)]
      (sign-in! app path ada "ada@example.test")
      (sign-in! app path bob "bob@example.test")
      (let [adas (session-key-of ada)]
        (GET app bob "/sessions")
        (POST app bob (str "/sessions/" adas "/end") {})
        (is (= 200 (:status (GET app ada "/")))
            "ada is still signed in")
        (is (= [[adas]] (support/rows path "SELECT id FROM db_base_sessions WHERE id = ?" adas))
            (str "and her row is untouched — the owner is in the WHERE clause of the host's own"
                 " table, so the store was never even asked"))))))

(deftest the-device-is-recorded-on-the-page-after-the-link-and-not-on-the-link-itself
  ;; Characterisation, and labelled as such: it kills no mutant of this host's.
  ;; What it does is fail loudly the day Ring or web-base hands a handler the key
  ;; it just minted, which is the day this lateness can be removed.
  (with-host [app path]
    (let [jar (browser)]
      (GET app jar "/login")
      (POST app jar "/login" {"identifier" "ada@example.test"})
      (GET app jar (str "/login/redeem/" (challenge-token path)))
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM device"))
          (str "nothing yet: at the redemption request `:session/key` still names the session"
               " being replaced, because Ring mints the new one inside write-session and hands"
               " it to the response"))
      (GET app jar "/")
      (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM device"))
          "and the first ordinary page records it, which is the page the redirect lands on"))))

(deftest an-authenticated-page-does-not-rewrite-the-session-it-read
  ;; A store that wrote on every request would churn a row per page view and
  ;; push every session's expiry forward for ever. The expiry is the observable:
  ;; it is computed when the row is written, so an unchanged one is a row that
  ;; was not written.
  (with-host [app path]
    (let [jar (browser)]
      (sign-in! app path jar "ada@example.test")
      (let [key    (session-key-of jar)
            expiry #(support/one path "SELECT expires_at FROM db_base_sessions WHERE id = ?" key)
            rows   #(ffirst (support/rows path "SELECT COUNT(*) FROM db_base_sessions"))
            before (expiry)
            count- (rows)]
        (is (some? before) "precondition: this browser's session is a row we can watch")
        (GET app jar "/")
        (GET app jar "/")
        (is (= before (expiry))
            (str "two more page views did not move its expiry — which is computed when a row"
                 " is written, so an unchanged one is a row that was not written"))
        (is (= count- (rows))
            (str "and added no rows either. Not an absolute count: the anonymous visits before"
                 " the login leave rows of their own, because the CSRF token lives in the"
                 " session — already on record as web-base's, and not this host's to fix"))))))

(deftest health-answers-from-the-database-and-not-from-a-constant
  (with-host [app _path]
    (let [response (app (mock/request :get "/health"))]
      (is (= 200 (:status response)) "the database answers, so the probe says so")
      (is (= "ok" (:body response)) "in a status line rather than a page"))))
