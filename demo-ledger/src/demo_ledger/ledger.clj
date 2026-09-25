(ns demo-ledger.ledger
  "Groups, their members, what they spent and who owes whom — written with next.jdbc
  against the datasource db-base handed over.

  **Membership is a predicate of every statement, never a check in a handler.** A
  group's rows are reached only through a `membership` row for the asking subject, in
  the same statement, so the next caller of these functions — a second route, an API,
  a batch job — cannot read or write a group it is not in by passing the right id.
  A function answers nil or 0 alike whether the group does not exist or is somebody
  else's: telling those apart would confirm, to anyone who guesses an id, that it is
  real.

  **An expense and its shares are written in one transaction**, because the balances
  are only right while every expense's shares add up to its amount, and a crash
  between the two inserts would leave money that nobody owes."
  (:require [dev.arkaitz.db-base.collision :as collision]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private as-maps {:builder-fn rs/as-unqualified-lower-maps})

(defn- ds [db] (:datasource db))

(defn- changed [result] (or (some-> result vals first) 0))

(defn- now [] (System/currentTimeMillis))

(def ^:private member-clause
  "Appended to a WHERE that has the group's id bound as `g`: true only when the
  asking subject belongs to it."
  "EXISTS (SELECT 1 FROM membership m WHERE m.group_id = g.id AND m.subject = ?)")

;; --- groups ------------------------------------------------------------------

(defn create-group!
  "A new group named `group-name`, with its creator as its first member. Returns its id."
  [db subject group-name]
  (let [id (str (random-uuid))
        t  (now)]
    (jdbc/with-transaction [tx (ds db)]
      (jdbc/execute-one! tx ["INSERT INTO ledger_group (id, name, created_by, created_at) VALUES (?, ?, ?, ?)"
                             id group-name subject t])
      (jdbc/execute-one! tx ["INSERT INTO membership (group_id, subject, joined_at) VALUES (?, ?, ?)"
                             id subject t]))
    id))

(defn groups-of
  "Every group `subject` belongs to, with how many members it has, by name."
  [db subject]
  (jdbc/execute! (ds db)
                 ["SELECT g.id, g.name, COUNT(m.subject) AS members
                   FROM ledger_group g JOIN membership m ON m.group_id = g.id
                   WHERE EXISTS (SELECT 1 FROM membership me
                                 WHERE me.group_id = g.id AND me.subject = ?)
                   GROUP BY g.id, g.name
                   ORDER BY g.name, g.id" subject]
                 as-maps))

(defn group-for
  "The group `id` as `subject` may see it, or nil when it is not theirs to see."
  [db subject id]
  (jdbc/execute-one! (ds db)
                     [(str "SELECT g.id, g.name FROM ledger_group g WHERE g.id = ? AND " member-clause)
                      id subject]
                     as-maps))

(defn members
  "The members of a group `subject` belongs to, by address; empty when they do not."
  [db subject group-id]
  (jdbc/execute! (ds db)
                 [(str "SELECT a.subject, a.identifier
                        FROM membership mm JOIN account a ON a.subject = mm.subject
                        JOIN ledger_group g ON g.id = mm.group_id
                        WHERE g.id = ? AND " member-clause "
                        ORDER BY a.identifier")
                  group-id subject]
                 as-maps))

;; --- invitations ---------------------------------------------------------------

(defn invite!
  "Invites `identifier` into a group `subject` belongs to. Answers :invited, or
  :already-invited, or nil when the group is not `subject`'s to invite into.

  Inviting twice is ordinary — two members inviting the same friend — so the primary
  key arbitrates and a refused insert is answered by looking for the invitation."
  [db subject group-id identifier]
  (when (group-for db subject group-id)
    (collision/arbitrate!
     (fn []
       (jdbc/execute-one! (ds db)
                          ["INSERT INTO invitation (group_id, identifier, invited_by, created_at) VALUES (?, ?, ?, ?)"
                           group-id identifier subject (now)])
       :invited)
     #(when (jdbc/execute-one! (ds db)
                               ["SELECT 1 FROM invitation WHERE group_id = ? AND identifier = ?"
                                group-id identifier])
        :already-invited))))

(defn invitations-for
  "What `identifier` has been invited to, with the group's name."
  [db identifier]
  (jdbc/execute! (ds db)
                 ["SELECT i.group_id, g.name FROM invitation i
                   JOIN ledger_group g ON g.id = i.group_id
                   WHERE i.identifier = ? ORDER BY g.name, g.id" identifier]
                 as-maps))

(defn accept!
  "Turns the invitation of `identifier` into `group-id` into a membership of
  `subject`. Answers true when there was an invitation to accept.

  The delete decides, in one transaction with the insert: two tabs accepting at once
  both reach the delete, one of them removes the row, and only that one inserts.

  **The insert is conditional rather than arbitrated**, because it runs inside a
  transaction: somebody already in the group is a primary-key refusal, and on
  PostgreSQL a refused statement aborts the whole transaction — the delete included —
  so `arbitrate!`'s look afterwards would have nothing to look through. `INSERT …
  SELECT … WHERE NOT EXISTS` asks the question in the statement instead."
  [db subject identifier group-id]
  (jdbc/with-transaction [tx (ds db)]
    (let [taken (changed (jdbc/execute-one! tx ["DELETE FROM invitation WHERE group_id = ? AND identifier = ?"
                                                group-id identifier]))]
      (when (= 1 taken)
        (jdbc/execute-one! tx ["INSERT INTO membership (group_id, subject, joined_at)
                                SELECT ?, ?, ? WHERE NOT EXISTS
                                  (SELECT 1 FROM membership WHERE group_id = ? AND subject = ?)"
                               group-id subject (now) group-id subject])
        true))))

;; --- expenses ------------------------------------------------------------------

(defn split
  "`amount-cents` divided among `n` members as evenly as whole cents allow: the
  remainder goes one cent at a time to the first members, so the parts always add up
  to the amount and never differ by more than a cent."
  [amount-cents n]
  (let [base  (quot amount-cents n)
        extra (rem amount-cents n)]
    (vec (for [i (range n)] (if (< i extra) (inc base) base)))))

(defn add-expense!
  "Records that `subject` paid `amount-cents` for the group, split evenly among the
  members it has at this moment. Answers the expense's id, or nil when the group is
  not `subject`'s.

  The members are read inside the transaction that writes the shares, which the
  host's `transaction_mode=IMMEDIATE` makes the only writer until it commits."
  [db subject group-id description amount-cents]
  (jdbc/with-transaction [tx (ds db)]
    (let [people (mapv :subject (jdbc/execute! tx
                                               [(str "SELECT mm.subject FROM membership mm
                                                      JOIN ledger_group g ON g.id = mm.group_id
                                                      WHERE g.id = ? AND " member-clause "
                                                      ORDER BY mm.subject")
                                                group-id subject]
                                               as-maps))]
      (when (seq people)
        (let [id (str (random-uuid))]
          (jdbc/execute-one! tx ["INSERT INTO expense (id, group_id, payer, description, amount_cents, created_at)
                                  VALUES (?, ?, ?, ?, ?, ?)"
                                 id group-id subject description amount-cents (now)])
          (doseq [[person share] (map vector people (split amount-cents (count people)))]
            (jdbc/execute-one! tx ["INSERT INTO expense_share (expense_id, subject, share_cents) VALUES (?, ?, ?)"
                                   id person share]))
          id)))))

(defn expenses
  "A group's expenses, newest first, each with who paid and what `subject` owes of it."
  [db subject group-id]
  (jdbc/execute! (ds db)
                 [(str "SELECT e.id, e.description, e.amount_cents, e.payer, a.identifier AS payer_identifier,
                               COALESCE(s.share_cents, 0) AS my_share
                        FROM expense e
                        JOIN ledger_group g ON g.id = e.group_id
                        JOIN account a ON a.subject = e.payer
                        LEFT JOIN expense_share s ON s.expense_id = e.id AND s.subject = ?
                        WHERE g.id = ? AND " member-clause "
                        ORDER BY e.created_at DESC, e.id DESC")
                  subject group-id subject]
                 as-maps))

(defn delete-expense!
  "Removes an expense `subject` paid, with its shares. Answers the rows removed: 1, or
  0 for an expense that is somebody else's or is not there."
  [db subject expense-id]
  (changed (jdbc/execute-one! (ds db) ["DELETE FROM expense WHERE id = ? AND payer = ?"
                                       expense-id subject])))

(defn balances
  "Every member of a group `subject` belongs to, with what they paid minus what they
  owe, in cents — positive is owed to them. The balances of a group add up to zero."
  [db subject group-id]
  (jdbc/execute! (ds db)
                 [(str "SELECT a.identifier, mm.subject,
                               COALESCE((SELECT SUM(e.amount_cents) FROM expense e
                                         WHERE e.group_id = g.id AND e.payer = mm.subject), 0)
                             - COALESCE((SELECT SUM(s.share_cents) FROM expense_share s
                                         JOIN expense e ON e.id = s.expense_id
                                         WHERE e.group_id = g.id AND s.subject = mm.subject), 0) AS balance
                        FROM membership mm
                        JOIN ledger_group g ON g.id = mm.group_id
                        JOIN account a ON a.subject = mm.subject
                        WHERE g.id = ? AND " member-clause "
                        ORDER BY a.identifier")
                  group-id subject]
                 as-maps))
