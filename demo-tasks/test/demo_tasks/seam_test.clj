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
            [hosts.support :as support
             :refer [GET POST browser challenge-token hop landed open-link! session-key-of sign-in! with-host]]
            [demo-tasks.system]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
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
      (let [token (challenge-token path "ada@example.test")]
        (is (= 200 (:status (open-link! app jar token))) "witness: the link was opened and its button pressed")
        (is (= "/" (:path @jar)) "following the link signs the person in")
        (is (= [["ada@example.test"]] (support/rows path "SELECT identifier FROM account"))
            "and THAT is where the account came into being — at redemption, once the token vouched")
        (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM login_challenge"))
            "and the challenge is spent")
        (is (= [200 "/login?ab=spent"] (let [fresh (browser)] [(:status (open-link! app fresh token)) (:path @fresh)]))
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
          "precondition: the second browser is signed in — without this the landing on /login below means nothing")
      (let [theirs (session-key-of there)]
        (POST app here "/revoke" {})
        (is (some? (support/session path theirs))
            "precondition: revoking moved a generation and deleted no row — the other session's is still there")
        (is (= [200 "/login?next=%2F"] (landed app there "/"))
            "the other browser is anonymous at its very next request")
        (is (nil? (support/session path theirs))
            "and that request threw its dead session away, rather than leaving the row until it expires"))
      (is (= [200 "/login?next=%2F"] (landed app here "/"))
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
        (is (= [200 "/login?next=%2F"] (landed app there "/"))
            "the session that was ended is anonymous at its next request")
        (is (= [200 "/"] (landed app here "/"))
            "and the one that ended it is still signed in — which is the whole feature")
        (is (nil? (support/session path other))
            "the row is gone, read through this test's own connection")
        (is (= [] (support/rows path "SELECT session_id FROM device WHERE session_id = ?" other))
            "and so is the host's record of it, or the list would show a session nobody can use")))))

(deftest logging-out-forgets-this-device-and-leaves-the-others
  ;; Found by using the running application rather than by reading it: the first
  ;; person to log out left a `device` row naming a session db-base had already
  ;; deleted, so the next visit would have listed a place they were not signed
  ;; in. Harmless to end and confusing to read, which is a poor advertisement
  ;; for the one feature a server-side session is here for.
  (with-host [app path]
    (let [here (browser) there (browser)]
      (sign-in! app path here "ada@example.test")
      (sign-in! app path there "ada@example.test")
      (let [leaving (session-key-of here)
              staying (session-key-of there)]
        (is (= 2 (count (support/rows path "SELECT session_id FROM device")))
            "precondition: two devices, or the disappearance below proves nothing")
        (POST app here "/logout" {})
        (is (= [] (support/rows path "SELECT session_id FROM device WHERE session_id = ?" leaving))
            "the device that logged out is forgotten")
        (is (nil? (support/session path leaving))
            "and so is its session, which is db-base's half of the same act")
        (is (= [[staying]] (support/rows path "SELECT session_id FROM device"))
            (str "while the other device is untouched — a logout that forgot everything would"
                 " be as wrong as one that forgot nothing"))
        (is (= [200 "/"] (landed app there "/"))
            "and that other browser is still signed in")))))

(deftest revoking-forgets-every-device-of-that-subject-and-nobody-elses
  (with-host [app path]
    (let [ada-here (browser) ada-there (browser) bob (browser)]
      (sign-in! app path ada-here "ada@example.test")
      (sign-in! app path ada-there "ada@example.test")
      (sign-in! app path bob "bob@example.test")
      (is (= 3 (count (support/rows path "SELECT session_id FROM device")))
          "precondition: three devices across two people")
      (let [bobs (session-key-of bob)]
        (POST app ada-here "/revoke" {})
        (is (= [[bobs]] (support/rows path "SELECT session_id FROM device"))
            (str "ada's two are forgotten and bob's is not — revocation ends every session of"
                 " ONE subject, and the host's records follow exactly that line"))
        (is (= [200 "/"] (landed app bob "/"))
            "and bob is still signed in, which is what says the delete carried an owner")))))

(deftest a-session-id-that-is-not-yours-ends-nothing
  (with-host [app path]
    (let [ada (browser) bob (browser)]
      (sign-in! app path ada "ada@example.test")
      (sign-in! app path bob "bob@example.test")
      (let [adas (session-key-of ada)]
        (GET app bob "/sessions")
        (POST app bob (str "/sessions/" adas "/end") {})
        (is (= [200 "/"] (landed app ada "/"))
            "ada is still signed in")
        (is (= adas (:id (support/session path adas)))
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
      (let [link (str "/login/redeem/" (challenge-token path "ada@example.test"))]
        (hop app jar :get link)
        (hop app jar :post link))
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
            expiry #(:expires-at (support/session path key))
            rows   #(count (support/sessions path))
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
                 " the login render its form and leave rows of their own, because the CSRF token lives in the"
                 " session — which a page with a form needs, and nothing else pays"))))))

(deftest health-answers-from-the-database-and-not-from-a-constant
  (with-host [app _path]
    (let [response (app (mock/request :get "/health"))]
      (is (= 200 (:status response)) "the database answers, so the probe says so")
      (is (= "ok" (:body response)) "in a status line rather than a page"))))

(deftest the-sixth-sign-in-from-one-address-shows-the-login-page-saying-why
  ;; auth-base 0.2.0 renders the host's own view for a refused sign-in, with
  ;; `:limited? true`; this host's limit is five per fifteen minutes.
  (with-host [app path]
    (let [jar (browser)]
      (GET app jar "/login")
      (dotimes [_ 5] (POST app jar "/login" {"identifier" "ada@example.test"}))
      (is (str/includes? (str (:body (:response @jar))) "a link is on its way")
          "control: the fifth was still accepted, so the refusal below is the sixth's")
      (is (= [[5]] (support/rows path "SELECT COUNT(*) FROM login_challenge")) "five challenges, one per accepted request")
      (let [refused (POST app jar "/login" {"identifier" "ada@example.test"})
            wait    (some-> (get-in refused [:headers "Retry-After"]) parse-long)]
        (is (= 429 (:status refused)) "the sixth is refused")
        (is (str/includes? (str (:body refused)) "Too many attempts from here")
            (str "with this host's login page saying why, not a bare status: " (:body refused)))
        (is (str/includes? (str (:body refused)) "name=\"identifier\"") "and the form is still there to try later")
        (is (and wait (< 0 wait) (<= wait 900)) (str "and Retry-After within the fifteen-minute window: " wait))
        (is (= [[5]] (support/rows path "SELECT COUNT(*) FROM login_challenge")) "and no challenge was stored for it")))))

(deftest a-copy-of-auth-bases-tables-that-drifted-stops-the-boot-naming-the-column
  (let [path (support/temp-db-path)]
    (try
      (let [system (ig/init (support/host-config path) [:dev.arkaitz.web-base/handler])]
        (ig/halt! system))
      (jdbc/execute! (support/datasource path) ["ALTER TABLE login_challenge RENAME COLUMN expires_at TO expiry"])
      (let [e (try (ig/init (support/host-config path) [:dev.arkaitz.web-base/handler]) nil
                   (catch clojure.lang.ExceptionInfo e e))
            ;; Integrant wraps what an init-key threw, and its own data carries the
            ;; configuration; the cause is what a host logs.
            causes (take-while some? (iterate ex-cause e))]
        (when-let [partial (:system (ex-data e))] (ig/halt! partial))
        (is (some? e) "the second boot was refused")
        (is (some #(str/includes? (str/lower-case (str (ex-message %))) "expires_at") causes)
            (str "by a cause naming the missing column: " (mapv ex-message causes)))
        (is (= :demo-tasks/auth-config (:key (ex-data e)))
            "at the key that builds the store, before anything served"))
      (finally (support/delete-db! path)))))

(deftest a-health-probe-leaves-no-session-row
  ;; A load balancer's probe must not cost a row, which on 2026-09-21 it did — three
  ;; probes, three rows. Two things in web-base 0.4.0 give this, and a cookieless probe
  ;; cannot tell them apart: the lazy CSRF token, and `/health` being sessionless. What
  ;; tells the mount apart — a probe carrying a cookie on a closed pool — is pinned once,
  ;; in demo/'s seam test; here the claim is the row.
  (with-host [app path]
    (let [answers (vec (repeatedly 3 #((juxt :status :body) (app (ring.mock.request/request :get "/health")))))]
      (is (= (repeat 3 [200 "ok"]) answers) "the probe is answered from the database")
      (is (= [] (support/sessions path))
          "and leaves no session row behind"))))

(defn- login-form
  "`[action field]` of the one form on `html` that holds an email input — its `action`
  and that input's `name`, read off the rendered page as a browser reads them — or nil
  when there is not exactly one such form."
  [html]
  (let [forms (filter #(str/includes? % "type=\"email\"") (re-seq #"(?s)<form\b[^>]*>.*?</form>" html))
        attr  (fn [tag k] (some->> tag (re-find (re-pattern (str "\\b" k "=\"([^\"]*)\""))) second))]
    (when (= 1 (count forms))
      [(attr (re-find #"<form\b[^>]*>" (first forms)) "action")
       (attr (re-find #"<input\b[^>]*type=\"email\"[^>]*>" (first forms)) "name")])))

(deftest the-login-form-posts-its-address-where-a-link-is-issued
  ;; Every other sign-in in this suite posts to a path and a field it spells itself, so
  ;; none of them reads the form the view draws. This one spells neither: it asks for a
  ;; gated page signed out, lands wherever the gate sends it, and sends what that page says.
  (with-host [app path]
    (let [jar            (browser)
          page           (GET app jar "/")
          [action field] (login-form (str (:body page)))]
      (is (= 200 (:status page)) "control: the gate's login page answers")
      (is (and (string? action) (string? field))
          (str "control: the page has one email form, with an action and a named input: " (:body page)))
      (is (= [[0]] (support/rows path "SELECT COUNT(*) FROM login_challenge"))
          "control: no challenge exists before the form is sent")
      ;; DELIBERATE: without both the POST has nowhere to go; the control above has
      ;; already failed, naming the page.
      (when (and action field)
        (let [answer (POST app jar action {field "ada@example.test"})]
          (is (= [[1]] (support/rows path "SELECT COUNT(*) FROM login_challenge WHERE identifier = ?"
                                     "ada@example.test"))
              (str "the address, posted where the form says under the name it says, issued one link — form: "
                   [action field] ", answered " (:status answer) " at " (:path @jar))))))))
