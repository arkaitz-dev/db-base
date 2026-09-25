(ns demo-ledger.seam-test
  "The host through its own `config.edn`, driven by a browser of the test's own: what
  the three libraries do together, read back through a second connection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo-ledger.support :as support
             :refer [GET POST browser landed posted sign-in! with-host]]
            [demo-ledger.system]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]))

(deftest the-ledger-through-http
  (with-host [app path]
    (let [ada (browser)]
      (sign-in! app path ada "ada@example.test")
      (let [created (posted app ada "/groups" {"name" "Trip"})
            g       (support/one path "SELECT id FROM ledger_group")]
        (is (= [200 (str "/groups/" g)] created) "creating a group lands on its page")
        (let [page (GET app ada (str "/groups/" g))]
          (is (= 200 (:status page)))
          (is (str/includes? (str (:body page)) "Trip")))
        (testing "an amount typed with a comma is whole cents in the table"
          (is (= [200 (str "/groups/" g)] (posted app ada (str "/groups/" g "/expenses")
                                                  {"description" "Dinner" "amount" "12,50"})))
          (is (= [[1250]] (support/rows path "SELECT amount_cents FROM expense")))
          (is (= [[1250]] (support/rows path "SELECT share_cents FROM expense_share")))
          (is (str/includes? (str (:body (GET app ada (str "/groups/" g)))) "12.50")))
        (testing "an amount the form does not accept is the group's page again, 422, with what was typed"
          (is (= [422 (str "/groups/" g "/expenses")]
                 (posted app ada (str "/groups/" g "/expenses") {"description" "Tip" "amount" "12.345"}))
              "answered where it was posted, not redirected — a redirect would lose the values")
          (let [body (str (:body (:response @ada)))]
            (is (str/includes? body "not one this form accepts") "saying why")
            (is (and (str/includes? body "value=\"Tip\"") (str/includes? body "value=\"12.345\""))
                (str "with both values back in their fields: " (re-seq #"<input[^>]*>" body)))
            (is (str/includes? body "Dinner") "on the group's own page, its expenses listed"))
          (is (= 1 (support/one path "SELECT COUNT(*) FROM expense")) "and nothing was written")
          (is (not (str/includes? (str (:body (GET app ada (str "/groups/" g)))) "not one this form accepts"))
              "control: an ordinary visit carries no error"))
        (testing "somebody else signed in cannot see the group or add to it"
          (let [bob (browser)]
            (sign-in! app path bob "bob@example.test")
            (is (= [200 "/"] (landed app bob "/")) "witness: bob is signed in, so a 404 below is not the gate")
            (is (= 404 (:status (GET app bob (str "/groups/" g)))) "the group page is not found for him")
            (GET app bob "/")
            (is (= [404 (str "/groups/" g "/expenses")]
                   (posted app bob (str "/groups/" g "/expenses") {"description" "x" "amount" "1.00"})))
            (is (= 1 (support/one path "SELECT COUNT(*) FROM expense")) "and nothing was written")))
        (testing "control: nobody signed in is sent to the login, which is the gate and not membership"
          (is (= [200 "/login"] (landed app (browser) (str "/groups/" g)))))
        (testing "an invitation typed with capitals and spaces reaches the account it names, and only it"
          (GET app ada (str "/groups/" g))
          (is (= [200 (str "/groups/" g)] (posted app ada (str "/groups/" g "/invitations")
                                                  {"identifier" "  Bob@Example.TEST "})))
          (is (= [["bob@example.test"]] (support/rows path "SELECT identifier FROM invitation"))
              "stored the way auth-base spells the account")
          (let [carol (browser)]
            (sign-in! app path carol "carol@example.test")
            (is (= [200 "/"] (posted app carol (str "/invitations/" g "/accept")
                                     {"identifier" "bob@example.test"})))
            (is (= [[0] [1]] [[(support/one path "SELECT COUNT(*) FROM membership WHERE group_id = ? AND subject <> ?"
                                            g (support/one path "SELECT subject FROM account WHERE identifier = ?" "ada@example.test"))]
                              [(support/one path "SELECT COUNT(*) FROM invitation")]])
                "carol, posting bob's address, joined nothing and spent nothing — the invitation is read off her own account"))
          (let [bob (browser)]
            (sign-in! app path bob "bob@example.test")
            (is (str/includes? (str (:body (GET app bob "/"))) "Trip") "bob is offered the group")
            (is (= [200 (str "/groups/" g)] (posted app bob (str "/invitations/" g "/accept") {})))
            (is (= [200 (str "/groups/" g)] (landed app bob (str "/groups/" g))) "and, having joined, sees it")))
        (testing "the payer deletes an expense through its button"
          (let [e (support/one path "SELECT id FROM expense")]
            (GET app ada (str "/groups/" g))
            (is (= [200 (str "/groups/" g)] (posted app ada (str "/groups/" g "/expenses/" e "/delete") {})))
            (is (= [[0] [0]] [[(support/one path "SELECT COUNT(*) FROM expense")]
                              [(support/one path "SELECT COUNT(*) FROM expense_share")]])
                "the expense and its share are gone")))))))

(deftest the-sixth-sign-in-from-one-address-shows-the-login-page-saying-why
  (with-host [app path]
    (let [jar (browser)]
      (GET app jar "/login")
      (dotimes [_ 5] (POST app jar "/login" {"identifier" "ada@example.test"}))
      (is (str/includes? (str (:body (:response @jar))) "A link is on its way")
          "control: the fifth was still accepted, so the refusal below is the sixth's")
      (let [refused (POST app jar "/login" {"identifier" "ada@example.test"})
            wait    (some-> (get-in refused [:headers "Retry-After"]) parse-long)]
        (is (= 429 (:status refused)) "the sixth is refused")
        (is (str/includes? (str (:body refused)) "Too many attempts from here")
            (str "with this host's login page saying why: " (:body refused)))
        (is (and wait (< 0 wait) (<= wait 900)) (str "and Retry-After within the window: " wait))
        (is (= [[5]] (support/rows path "SELECT COUNT(*) FROM login_challenge")) "and no challenge was stored for it")))))

(deftest a-copy-of-auth-bases-tables-that-drifted-stops-the-boot-naming-the-column
  (let [path (support/temp-db-path)]
    (try
      (ig/halt! (ig/init (support/host-config path) [:dev.arkaitz.web-base/handler]))
      (jdbc/execute! (support/datasource path) ["ALTER TABLE login_challenge RENAME COLUMN expires_at TO expiry"])
      (let [e      (try (ig/init (support/host-config path) [:dev.arkaitz.web-base/handler]) nil
                        (catch clojure.lang.ExceptionInfo e e))
            causes (take-while some? (iterate ex-cause e))]
        (when-let [partial (:system (ex-data e))] (ig/halt! partial))
        (is (some? e) "the second boot was refused")
        (is (some #(str/includes? (str/lower-case (str (ex-message %))) "expires_at") causes)
            (str "by a cause naming the missing column: " (mapv ex-message causes)))
        (is (= :demo-ledger/auth-config (:key (ex-data e))) "at the key that builds the store"))
      (finally (support/delete-db! path)))))
