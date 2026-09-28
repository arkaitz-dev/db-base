(ns demo-ledger.ledger-test
  "The ledger's storage, read back through a connection of the test's own.

  Every actor is a registered account, outsiders included: every table that names a
  person references `account`, so an outsider without one would be refused by a
  foreign key and a mutant would die for the wrong reason."
  (:require [clojure.test :refer [deftest is testing]]
            [demo-ledger.ledger :as ledger]
            [demo-ledger.money :as money]
            [hosts.support :as support :refer [with-db]]
            [dev.arkaitz.auth-base.jdbc :as auth-jdbc]
            [dev.arkaitz.db-base :as db]
            [dev.arkaitz.db-base.testing :as dbt]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]))

(def ^:private ada "ada@example.test")
(def ^:private bob "bob@example.test")
(def ^:private carol "carol@example.test")

(defn- count-of [path table] (support/one path (str "SELECT COUNT(*) FROM " table)))

;; --- pure --------------------------------------------------------------------

(deftest split-keeps-every-cent-and-hands-the-remainder-to-the-first-members
  (doseq [[[amount n] expected] {[1000 3] [334 333 333]
                                 [5 3]    [2 2 1]
                                 [1 4]    [1 0 0 0]
                                 [100 4]  [25 25 25 25]
                                 [7 1]    [7]}]
    (is (= expected (ledger/split amount n)) (str "split " amount " among " n)))
  (is (= [] (vec (for [amount (range 1 301)
                       n      (range 1 8)
                       :let [parts (ledger/split amount n)]
                       :when (not (and (= n (count parts))
                                       (= amount (reduce + parts))
                                       (<= (- (apply max parts) (apply min parts)) 1)
                                       (apply >= parts)))]
                   [amount n parts])))
      "every amount from 1 to 300 among 1 to 7: n parts, the whole amount, within a cent, extras first"))

(deftest parse-cents-and-format-cents-accept-and-refuse-exactly-what-the-docstring-says
  (doseq [[s cents] {"12.5" 1250 "12,50" 1250 "12.05" 1205 "12" 1200 " 3.07 " 307
                     "0.01" 1 "99999999.99" 9999999999}]
    (is (= cents (money/parse-cents s)) (str "accepted: " (pr-str s))))
  (doseq [s [nil 12.5 "" "0" "0.00" "-5" "1.234" "1,000.50" "12." ".5" "1e3" "abc"
             "123456789" "abc12.50"]]
    (is (nil? (money/parse-cents s)) (str "refused: " (pr-str s))))
  (doseq [[cents s] {1250 "12.50" -1250 "-12.50" 5 "0.05" -5 "-0.05" 0 "0.00" 100 "1.00"}]
    (is (= s (money/format-cents cents)) (str "formatted: " cents))))

;; --- membership --------------------------------------------------------------

(deftest a-non-member-reads-nothing-and-writes-nothing
  (with-db [db path]
    (let [a     (auth-jdbc/register! (:datasource db) ada)
          b     (auth-jdbc/register! (:datasource db) bob)
          g     (ledger/create-group! db a "Trip")
          ghost (str (random-uuid))]
      (ledger/add-expense! db a g "Dinner" 900)
      (ledger/invite! db a g carol)
      (testing "witnesses: the member really is answered, so the empties below mean something"
        (is (= {:id g :name "Trip"} (ledger/group-for db a g)))
        (is (= ["Dinner"] (mapv :description (ledger/expenses db a g))))
        (is (= [ada] (mapv :identifier (ledger/members db a g))))
        (is (= 1 (count (ledger/balances db a g))))
        (is (= [g] (mapv :id (ledger/groups-of db a)))))
      (doseq [[who s id] [["bob, on ada's group" b g] ["ada, on a group that does not exist" a ghost]]]
        (is (nil? (ledger/group-for db s id)) (str who ": group-for"))
        (is (= [] (ledger/members db s id)) (str who ": members"))
        (is (= [] (ledger/expenses db s id)) (str who ": expenses"))
        (is (= [] (ledger/balances db s id)) (str who ": balances"))
        (is (nil? (ledger/add-expense! db s id "x" 1000)) (str who ": add-expense!"))
        (is (nil? (ledger/invite! db s id carol)) (str who ": invite!")))
      (is (= [] (ledger/groups-of db b)) "bob belongs to nothing")
      (is (= [1 1] [(count-of path "expense") (count-of path "invitation")])
          "and nothing either of them tried was written — the one expense and invitation are ada's"))))

(deftest what-each-member-owes-of-an-expense-is-their-own-share
  (with-db [db path]
    (let [a (auth-jdbc/register! (:datasource db) ada)
          b (auth-jdbc/register! (:datasource db) bob)
          g (ledger/create-group! db a "Trip")]
      (ledger/invite! db a g bob)
      (ledger/accept! db b bob g)
      (let [id (ledger/add-expense! db a g "Dinner" 1001)]
        (doseq [[who s] [["ada" a] ["bob" b]]]
          (let [listed (ledger/expenses db s g)]
            (is (= 1 (count listed)) (str who ": one row for one expense, not one per share"))
            (is (= (support/one path "SELECT share_cents FROM expense_share WHERE expense_id = ? AND subject = ?" id s)
                   (:my_share (first listed)))
                (str who ": my_share is the share row of that very person"))))
        (is (= 1001 (+ (:my_share (first (ledger/expenses db a g)))
                       (:my_share (first (ledger/expenses db b g)))))
            "and the two shares, 501 and 500 in whichever order, make the whole amount")))))

(deftest an-address-sees-its-own-invitations-and-nobody-elses
  (with-db [db path]
    (let [a (auth-jdbc/register! (:datasource db) ada)
          g (ledger/create-group! db a "Trip")
          h (ledger/create-group! db a "Rent")]
      (ledger/invite! db a g bob)
      (ledger/invite! db a h carol)
      (is (= [[g "Trip"]] (mapv (juxt :group_id :name) (ledger/invitations-for db bob))) "bob's")
      (is (= [[h "Rent"]] (mapv (juxt :group_id :name) (ledger/invitations-for db carol))) "carol's")
      (is (= [] (ledger/invitations-for db ada)) "and none for ada, who invited but was not invited"))))

(deftest an-accept-that-cannot-make-the-member-keeps-the-invitation
  ;; The membership insert refused — here by a subject with no account behind it — must
  ;; take the invitation's deletion back with it, or the invitation is spent and nobody
  ;; joined.
  (with-db [db path]
    (let [a (auth-jdbc/register! (:datasource db) ada)
          g (ledger/create-group! db a "Trip")]
      (ledger/invite! db a g bob)
      (is (instance? SQLException (try (ledger/accept! db "no-such-account" bob g) nil (catch SQLException e e)))
          "witness: the membership insert was refused")
      (is (= [[1] [1]] [[(count-of path "invitation")] [(count-of path "membership")]])
          "and the invitation is still there, with ada still the only member"))))

;; --- expenses ----------------------------------------------------------------

(deftest an-expense-and-its-shares-land-together-or-not-at-all
  (with-db [db path]
    (let [a (auth-jdbc/register! (:datasource db) ada)
          g (ledger/create-group! db a "Trip")]
      (testing "control: without the planted member the same call writes the expense and its shares"
        (let [id (ledger/add-expense! db a g "control" 1000)]
          (is (= [[1000]] (support/rows path "SELECT amount_cents FROM expense WHERE id = ?" id)))
          (is (= [[a 1000]] (support/rows path "SELECT subject, share_cents FROM expense_share WHERE expense_id = ?" id)))
          (jdbc/execute-one! (support/datasource path) ["DELETE FROM expense WHERE id = ?" id])))
      ;; A member with no account, planted past the foreign key: the expense insert
      ;; succeeds and the ghost's share is refused by `expense_share.subject`. The
      ;; ghost sorts after ada's subject or before it; either way one share is
      ;; refused after the expense row exists.
      (jdbc/execute-one! (support/datasource path {:foreign-keys? false})
                         ["INSERT INTO membership (group_id, subject, joined_at) VALUES (?, ?, 0)" g "ghost"])
      (is (instance? SQLException
                     (try (ledger/add-expense! db a g "doomed" 1000) nil (catch SQLException e e)))
          "witness: a share insert really was refused, so what follows is the transaction's doing")
      (is (= [0 0] [(count-of path "expense") (count-of path "expense_share")])
          "and neither the expense nor any share survived it"))))

(deftest cascade-happens-only-because-foreign-keys-is-in-the-url
  (let [shares-after-delete
        (fn [db path]
          (let [a  (auth-jdbc/register! (:datasource db) ada)
                b  (auth-jdbc/register! (:datasource db) bob)
                g  (ledger/create-group! db a "Trip")]
            (ledger/invite! db a g bob)
            (ledger/accept! db b bob g)
            (let [id (ledger/add-expense! db a g "Dinner" 1000)]
              [(count (support/rows path "SELECT subject FROM expense_share WHERE expense_id = ?" id))
               (ledger/delete-expense! db a id)
               (count (support/rows path "SELECT subject FROM expense_share WHERE expense_id = ?" id))])))]
    (with-db [db path]
      (is (= [2 1 0] (shares-after-delete db path))
          "the host's URL: two shares, the expense deleted, and the shares went with it"))
    (let [path (support/temp-db-path)]
      (try
        (let [handle (db/start (assoc (support/config path) :jdbc-url (support/url-without-foreign-keys path)))]
          (try
            (is (= [2 1 2] (shares-after-delete handle path))
                (str "control: the same schema without foreign_keys=true in the URL deletes the"
                     " expense and leaves both shares behind — the cascade above is that pragma's"))
            (finally (db/stop handle))))
        (finally (support/delete-db! path))))))

(deftest balances-are-exact-and-sum-to-zero
  (with-db [db path]
    (let [[a b c] (map #(auth-jdbc/register! (:datasource db) %) [ada bob carol])
          g       (ledger/create-group! db a "Trip")
          g2      (ledger/create-group! db a "Rent")]
      (doseq [[s who] [[b bob] [c carol]]]
        (ledger/invite! db a g who)
        (ledger/accept! db s who g))
      (ledger/invite! db a g2 bob)
      (ledger/accept! db b bob g2)
      ;; Amounts that divide evenly: which member gets a remainder cent is decided by the
      ;; order of subjects, which are random UUIDs, so an uneven split here would make
      ;; the expected balances depend on the run. The remainder is `split`'s test.
      (ledger/add-expense! db a g "Dinner" 900)
      (ledger/add-expense! db b g "Taxi" 600)
      (ledger/add-expense! db a g2 "Deposit" 701)
      (is (= [[3] [8]] [[(count-of path "expense")] [(count-of path "expense_share")]])
          "witness: three expenses and eight shares — three, three and two — were written")
      (is (= [[ada 400] [bob 100] [carol -500]]
             (mapv (juxt :identifier :balance) (ledger/balances db a g)))
          (str "ada paid 900 and owes 300+200, bob paid 600 and owes 300+200, carol owes"
               " 300+200 — and the other group's 701 is not in any of it"))
      (is (= 0 (reduce + (map :balance (ledger/balances db a g)))) "and they add up to zero"))))

(deftest accepting-consumes-the-invitation-once-and-tolerates-an-existing-member
  (with-db [db path]
    (let [a (auth-jdbc/register! (:datasource db) ada)
          b (auth-jdbc/register! (:datasource db) bob)
          g (ledger/create-group! db a "Trip")]
      (is (= :invited (ledger/invite! db a g bob)) "witness: invited")
      (is (= :already-invited (ledger/invite! db a g bob)) "and inviting again is not an error")
      (is (true? (ledger/accept! db b bob g)) "bob accepts")
      (is (= [[0] [1]] [[(support/one path "SELECT COUNT(*) FROM invitation WHERE identifier = ?" bob)]
                        [(support/one path "SELECT COUNT(*) FROM membership WHERE group_id = ? AND subject = ?" g b)]])
          "the invitation is spent and bob is a member")
      (is (not (ledger/accept! db b bob g)) "a second accept finds nothing to accept")
      (is (= 1 (support/one path "SELECT COUNT(*) FROM membership WHERE group_id = ? AND subject = ?" g b))
          "and bob is still one member, not two")
      (ledger/invite! db b g ada)
      (is (true? (ledger/accept! db a ada g))
          "ada, already a member, accepts an invitation to her own group without an error")
      (is (= [[0] [1]] [[(support/one path "SELECT COUNT(*) FROM invitation WHERE identifier = ?" ada)]
                        [(support/one path "SELECT COUNT(*) FROM membership WHERE group_id = ? AND subject = ?" g a)]])
          "and the invitation is spent while ada stays one member"))))

(deftest only-the-payer-deletes-an-expense
  (with-db [db path]
    (let [[a b c] (map #(auth-jdbc/register! (:datasource db) %) [ada bob carol])
          g       (ledger/create-group! db a "Trip")]
      (ledger/invite! db a g bob)
      (ledger/accept! db b bob g)
      (let [id     (ledger/add-expense! db a g "Dinner" 1000)
            counts #(vector (support/one path "SELECT COUNT(*) FROM expense WHERE id = ?" id)
                            (support/one path "SELECT COUNT(*) FROM expense_share WHERE expense_id = ?" id))]
        (is (= 0 (ledger/delete-expense! db b id)) "bob, a member who did not pay, removes nothing")
        (is (= 0 (ledger/delete-expense! db c id)) "carol, not a member, removes nothing")
        (is (= [1 2] (counts)) "and the expense and its two shares are still there")
        (is (= 1 (ledger/delete-expense! db a id)) "ada, who paid, removes it")
        (is (= [0 0] (counts)) "with its shares")))))

(deftest an-expense-whose-transaction-read-first-survives-a-writer-committing-meanwhile
  ;; `add-expense!` reads the members before it writes. In SQLite's default deferred
  ;; mode that read pins a snapshot, and a writer committing in between makes the
  ;; expense's insert fail with SQLITE_BUSY_SNAPSHOT — an intermittent 500 in a running
  ;; host (FRICTION.md, F7). `transaction_mode=IMMEDIATE` in the host's URL takes the
  ;; lock when the transaction begins, so the other writer waits instead.
  ;;
  ;; The other writer is given one second to finish on its own before the park is
  ;; released. That second decides nothing about the verdict: under the host's URL the
  ;; writer is still waiting when it runs out, and under a deferred URL it has long
  ;; finished — which is what makes the parked caller's failure observable.
  (with-db [db path]
    (let [a (auth-jdbc/register! (:datasource db) ada)
          g (ledger/create-group! db a "Trip")
          {:keys [arrived release! exit] parked :handle}
          (dbt/parking db #(.startsWith ^String % "INSERT INTO expense") 10000)
          expense (promise)
          other   (promise)]
      (doto (Thread. #(deliver expense (try [:ok (ledger/add-expense! parked a g "Dinner" 900)]
                                            (catch Throwable e [:threw (ex-message e)]))))
        (.setDaemon true) (.start))
      (try
        (is (string? (deref arrived 5000 nil)) "witness: the expense read its members and is parked before writing")
        (doto (Thread. #(deliver other (try [:ok (ledger/create-group! db a "Rent")]
                                            (catch Throwable e [:threw (ex-message e)]))))
          (.setDaemon true) (.start))
        (deref other 1000 nil)
        (release!)
        (is (= :released (deref exit 5000 ::hang)) "the park ended because it was released")
        (let [e (deref expense 10000 [::hang])
              o (deref other 10000 [::hang])]
          (is (= :ok (first e)) (str "the parked expense was written: " (pr-str e)))
          (is (= :ok (first o)) (str "and the other writer's group too, once it was its turn: " (pr-str o))))
        (is (= [[1] [2]] [[(count-of path "expense")] [(count-of path "ledger_group")]])
            "one expense and two groups, read independently")
        (finally (release!))))))
