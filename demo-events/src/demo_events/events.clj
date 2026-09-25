(ns demo-events.events
  "Events with a capacity, and a waiting list behind it — written with next.jdbc
  against the datasource db-base handed over.

  **The capacity is decided by one statement, never by a read.** Counting who is
  going and then inserting another is the classic over-booking: two people read the
  same count and both take the last place. Here a place is taken by
  `UPDATE event SET going = going + 1 WHERE id = ? AND going < capacity`, which every
  engine applies to the row one writer at a time; the rows it changed — one or none —
  are the answer. `rsvp` then records what that answer was.

  **Leaving promotes the first person waiting, in the same transaction**, so a freed
  place is never visible as free while somebody is queued for it."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:private as-maps {:builder-fn rs/as-unqualified-lower-maps})

(defn- ds [db] (:datasource db))

(defn- changed [result] (or (some-> result vals first) 0))

(defn- now [] (System/currentTimeMillis))

(defn create-event!
  "A new event owned by `subject`. Answers its id."
  [db subject title capacity starts-at]
  (let [id (str (random-uuid))]
    (jdbc/execute-one! (ds db) ["INSERT INTO event (id, owner, title, capacity, going, starts_at)
                                 VALUES (?, ?, ?, ?, 0, ?)"
                                id subject title capacity starts-at])
    id))

(defn upcoming
  "Every event, soonest first, with its places and what `subject` answered to it."
  [db subject]
  (jdbc/execute! (ds db)
                 ["SELECT e.id, e.title, e.capacity, e.going, e.starts_at, e.owner,
                          (SELECT COUNT(*) FROM rsvp w WHERE w.event_id = e.id AND w.status = 'waiting') AS waiting,
                          r.status AS mine
                   FROM event e
                   LEFT JOIN rsvp r ON r.event_id = e.id AND r.subject = ?
                   ORDER BY e.starts_at, e.id" subject]
                 as-maps))

(defn event-for
  "One event, or nil."
  [db id]
  (jdbc/execute-one! (ds db) ["SELECT id, title, capacity, going, starts_at, owner FROM event WHERE id = ?" id]
                     as-maps))

(defn attendees
  "Who is going and who is waiting, each in the order they answered."
  [db event-id]
  (jdbc/execute! (ds db)
                 ["SELECT a.identifier, r.status FROM rsvp r JOIN account a ON a.subject = r.subject
                   WHERE r.event_id = ?
                   ORDER BY CASE r.status WHEN 'going' THEN 0 ELSE 1 END, r.joined_at, r.subject"
                  event-id]
                 as-maps))

(defn join!
  "Answers :going when `subject` took a place, :waiting when the event was full, :already
  when they had answered before, or nil when there is no such event.

  The answer is recorded first as waiting, conditionally, so a second tab of the same
  person finds it and changes nothing; then the conditional `UPDATE` on the event
  decides whether a place was taken, and only then is the answer promoted."
  [db subject event-id]
  (jdbc/with-transaction [tx (ds db)]
    (when (jdbc/execute-one! tx ["SELECT 1 FROM event WHERE id = ?" event-id])
      (let [recorded (changed (jdbc/execute-one! tx ["INSERT INTO rsvp (event_id, subject, status, joined_at)
                                                      SELECT ?, ?, 'waiting', ? WHERE NOT EXISTS
                                                        (SELECT 1 FROM rsvp WHERE event_id = ? AND subject = ?)"
                                                     event-id subject (now) event-id subject]))]
        (cond
          (zero? recorded) :already
          (= 1 (changed (jdbc/execute-one! tx ["UPDATE event SET going = going + 1
                                                WHERE id = ? AND going < capacity" event-id])))
          (do (jdbc/execute-one! tx ["UPDATE rsvp SET status = 'going' WHERE event_id = ? AND subject = ?"
                                     event-id subject])
              :going)
          :else :waiting)))))

(defn leave!
  "Withdraws `subject`'s answer. When they were going, the first person waiting takes
  the place in the same transaction, or the place is given back. Answers true when
  there was an answer to withdraw.

  The promotion names the person and asks, in the same statement, that they are
  still waiting: two people leaving at once on an engine that runs them side by side
  would otherwise both promote the same person, and one place would vanish."
  [db subject event-id]
  (jdbc/with-transaction [tx (ds db)]
    (let [status (:status (jdbc/execute-one! tx ["SELECT status FROM rsvp WHERE event_id = ? AND subject = ?"
                                                  event-id subject]
                                              as-maps))
          gone   (changed (jdbc/execute-one! tx ["DELETE FROM rsvp WHERE event_id = ? AND subject = ?"
                                                 event-id subject]))]
      (when (= 1 gone)
        (when (= "going" status)
          (let [promoted (changed (jdbc/execute-one!
                                   tx ["UPDATE rsvp SET status = 'going'
                                        WHERE event_id = ? AND status = 'waiting' AND subject =
                                          (SELECT subject FROM rsvp WHERE event_id = ? AND status = 'waiting'
                                           ORDER BY joined_at, subject LIMIT 1)"
                                       event-id event-id]))]
            (when (zero? promoted)
              (jdbc/execute-one! tx ["UPDATE event SET going = going - 1 WHERE id = ?" event-id]))))
        true))))

(defn delete-event!
  "Removes an event `subject` owns, with every answer to it. Answers the rows removed."
  [db subject event-id]
  (changed (jdbc/execute-one! (ds db) ["DELETE FROM event WHERE id = ? AND owner = ?" event-id subject])))
