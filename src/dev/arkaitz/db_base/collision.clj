(ns dev.arkaitz.db-base.collision
  "One function, for one event: a write the engine refused when what the write was for
  may already be there. It holds no SQL, takes no datasource and reads no row itself;
  the host's two functions do all of that through whatever the host already calls.

  That is what keeps it on this side of SPEC §9, where a helper that took a statement
  would be the first step of a query builder, and it is why this namespace has one public
  var and `structure_test` pins it. A second one is an edit of SPEC §10 before it is code.
  Added 2026-09-25, after the shape had been written by hand in `demo-tasks/` and in this
  library's own migration lock."
  (:import [java.sql SQLException]))

(defn- rolled-back?
  "Whether `e`, or an `SQLException` among its causes, says the engine rolled the
  transaction back: a state of class 40 other than 40003. The walk stops after 32
  links, because a cause chain can be made cyclic and a walk that follows one never
  returns."
  [^Throwable e]
  (loop [t e n 0]
    (cond
      (or (nil? t) (<= 32 n)) false
      (let [state (when (instance? SQLException t) (.getSQLState ^SQLException t))]
        (and (string? state) (.startsWith ^String state "40") (not= "40003" state))) true
      :else (recur (.getCause t) (inc n)))))

(defn arbitrate!
  "Calls `write!` and returns what it returns. If it throws a `java.sql.SQLException`,
  calls `re-read` once: a truthy answer is returned in the write's place, and nil or false
  rethrows the write's own exception, unwrapped. Nothing but `SQLException` is caught, and
  a transaction the engine rolled back is rethrown without a look (below).

  **The collision is recognised by looking, never by the engine's code for it.** A
  duplicate key is 23505 on PostgreSQL, 23000 with vendor code 1062 on MySQL and MariaDB,
  and no state at all on SQLite (measured). So this does not know why the write failed
  and does not pretend to: it answers whether what the write was for is there now. That
  is also what makes a write the server committed, and the client never heard about, come
  out right.

  **An aborted transaction is not a collision, and is rethrown without a look** (since
  0.9.0). When the write's exception, or an `SQLException` in its cause chain, carries a
  state of class 40 — transaction rollback: 40001 a serialization failure, 40P01 a
  deadlock — other than 40003, the write's own exception leaves and `re-read` is never
  called. The engine chose this transaction as the one to give way while another writer
  was still in flight, so a look at that moment reads a world the other writer has not
  finished changing, and answers it with confidence (measured on PostgreSQL in the
  academy: a deadlock victim's look found a row the other transaction was deleting). The
  caller retries, or reports the failure; whether to is the host's. 40003, statement
  completion unknown, keeps the look: the write may have committed, which is the case
  the look exists for. The code is read for this alone — it says the question has no
  answer yet, never what the answer is. An engine that reports a rollback outside class
  40 — a lock timeout on H2 or MySQL, SQLite's busy, Oracle's deadlock — still gets the
  look; the class is the only signal this can read without knowing the engine.

  **`re-read` must ask for exactly the row the write would have written**: every column
  that makes it the caller's, not only its key. A re-read by key alone accepts a row
  somebody else wrote under that key and returns it as though it were the caller's. That
  predicate is the one way this function can lie, and it is the host's.

  **`re-read` reads.** It runs after a write has already failed, and nothing here makes a
  second write safe at that point. A fallback that writes is a different contract —
  update, and create when nothing was there — which SPEC §8 is the argument against; where
  it is right for a host's own table, it stays in that host.

  If `re-read` throws, the write's exception is the one that leaves, with the re-read's
  added as suppressed, unless the re-read threw an `Error` or was interrupted: that one
  leaves instead, carrying the write's, as `start` lets both through (SPEC §6).

  **Inside a transaction the host opened**, a refused write can leave nothing to re-read
  with. PostgreSQL rejects every statement after an error until the transaction rolls
  back, so the re-read throws and the write's exception leaves — the right answer for a
  transaction already lost. On an engine that keeps the transaction alive, a re-read under
  a snapshot taken before the other writer committed finds nothing, and the write's
  exception leaves the same way. A host that wants to carry on inside its transaction
  marks a savepoint of its own inside `write!`: the transaction boundary is the host's
  (SPEC §9), so none is taken here. **The usual answer inside a transaction is not this
  function at all** but a write that asks its own question — an insert whose `SELECT`
  carries a `WHERE NOT EXISTS` for the row it would add — because nothing about it can be
  refused; the README's recipes show one. **Around the whole transaction is a right
  shape too**: `write!` opens, writes and commits, so a refusal anywhere in it rolls
  back before `re-read` runs, outside it, against what is committed (measured in the
  academy: a second enrolment refused by the role's key after its seat was taken came
  back as the role already held, with the seat given back).

  A write that failed because no connection could be borrowed makes the re-read borrow
  too, so that failure costs the pool's timeout twice."
  [write! re-read]
  (try (write!)
       (catch SQLException e
         (when (rolled-back? e) (throw e))
         (or (try (re-read)
                  (catch Throwable t
                    (when (or (instance? Error t) (instance? InterruptedException t))
                      (.addSuppressed t e)
                      (throw t))
                    (.addSuppressed e t)
                    nil))
             (throw e)))))
