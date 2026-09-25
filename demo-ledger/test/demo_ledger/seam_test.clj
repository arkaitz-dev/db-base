(ns demo-ledger.seam-test
  "The host through its own `config.edn`, driven by a browser of the test's own: what
  the three libraries do together, read back through a second connection."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo-ledger.support :as support
             :refer [GET POST browser location sign-in! with-host]]
            [demo-ledger.system]))

(deftest the-ledger-through-http
  (with-host [app path]
    (let [ada (browser)]
      (sign-in! app path ada "ada@example.test")
      (let [created (POST app ada "/groups" {"name" "Trip"})
            g       (support/one path "SELECT id FROM ledger_group")]
        (is (= (str "/groups/" g) (location created)) "creating a group lands on its page")
        (let [page (GET app ada (str "/groups/" g))]
          (is (= 200 (:status page)))
          (is (str/includes? (str (:body page)) "Trip")))
        (testing "an amount typed with a comma is whole cents in the table"
          (is (= (str "/groups/" g) (location (POST app ada (str "/groups/" g "/expenses")
                                                    {"description" "Dinner" "amount" "12,50"}))))
          (is (= [[1250]] (support/rows path "SELECT amount_cents FROM expense")))
          (is (= [[1250]] (support/rows path "SELECT share_cents FROM expense_share")))
          (is (str/includes? (str (:body (GET app ada (str "/groups/" g)))) "12.50")))
        (testing "an amount the form does not accept goes back to the form and writes nothing"
          (is (= (str "/groups/" g "?error=amount")
                 (location (POST app ada (str "/groups/" g "/expenses")
                                 {"description" "Tip" "amount" "12.345"}))))
          (is (= 1 (support/one path "SELECT COUNT(*) FROM expense")))
          (is (str/includes? (str (:body (GET app ada (str "/groups/" g "?error=amount"))))
                             "not one this form accepts")))
        (testing "somebody else signed in cannot see the group or add to it"
          (let [bob (browser)]
            (sign-in! app path bob "bob@example.test")
            (is (= 200 (:status (GET app bob "/"))) "witness: bob is signed in, so a 404 below is not the gate")
            (is (= 404 (:status (GET app bob (str "/groups/" g)))) "the group page is not found for him")
            (GET app bob "/")
            (is (= 404 (:status (POST app bob (str "/groups/" g "/expenses")
                                      {"description" "x" "amount" "1.00"}))))
            (is (= 1 (support/one path "SELECT COUNT(*) FROM expense")) "and nothing was written")))
        (testing "control: nobody signed in is sent to the login, which is the gate and not membership"
          (is (= "/login" (location (GET app (browser) (str "/groups/" g))))))
        (testing "an invitation typed with capitals and spaces reaches the account it names, and only it"
          (GET app ada (str "/groups/" g))
          (is (= (str "/groups/" g) (location (POST app ada (str "/groups/" g "/invitations")
                                                    {"identifier" "  Bob@Example.TEST "}))))
          (is (= [["bob@example.test"]] (support/rows path "SELECT identifier FROM invitation"))
              "stored the way auth-base spells the account")
          (let [carol (browser)]
            (sign-in! app path carol "carol@example.test")
            (is (= "/" (location (POST app carol (str "/invitations/" g "/accept")
                                       {"identifier" "bob@example.test"}))))
            (is (= [[0] [1]] [[(support/one path "SELECT COUNT(*) FROM membership WHERE group_id = ? AND subject <> ?"
                                            g (support/one path "SELECT subject FROM account WHERE identifier = ?" "ada@example.test"))]
                              [(support/one path "SELECT COUNT(*) FROM invitation")]])
                "carol, posting bob's address, joined nothing and spent nothing — the invitation is read off her own account"))
          (let [bob (browser)]
            (sign-in! app path bob "bob@example.test")
            (is (str/includes? (str (:body (GET app bob "/"))) "Trip") "bob is offered the group")
            (is (= (str "/groups/" g) (location (POST app bob (str "/invitations/" g "/accept") {}))))
            (is (= 200 (:status (GET app bob (str "/groups/" g)))) "and, having joined, sees it")))
        (testing "the payer deletes an expense through its button"
          (let [e (support/one path "SELECT id FROM expense")]
            (GET app ada (str "/groups/" g))
            (is (= (str "/groups/" g) (location (POST app ada (str "/groups/" g "/expenses/" e "/delete") {}))))
            (is (= [[0] [0]] [[(support/one path "SELECT COUNT(*) FROM expense")]
                              [(support/one path "SELECT COUNT(*) FROM expense_share")]])
                "the expense and its share are gone")))))))
