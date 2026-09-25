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

(defn arbitrate!
  "Calls `write!` and returns what it returns. If it throws a `java.sql.SQLException`,
  calls `re-read` once: a truthy answer is returned in the write's place, and nil or false
  rethrows the write's own exception, unwrapped. Nothing but `SQLException` is caught.

  **The collision is recognised by looking, never by the engine's code for it.** A
  duplicate key is 23505 on PostgreSQL, 23000 with vendor code 1062 on MySQL and MariaDB,
  and no state at all on SQLite (measured). So this does not know why the write failed
  and does not pretend to: it answers whether what the write was for is there now. That
  is also what makes a write the server committed, and the client never heard about, come
  out right.

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
  refused; the README's recipes show one.

  A write that failed because no connection could be borrowed makes the re-read borrow
  too, so that failure costs the pool's timeout twice."
  [write! re-read]
  (try (write!)
       (catch SQLException e
         (or (try (re-read)
                  (catch Throwable t
                    (when (or (instance? Error t) (instance? InterruptedException t))
                      (.addSuppressed t e)
                      (throw t))
                    (.addSuppressed e t)
                    nil))
             (throw e)))))
