# Friction: what building two more hosts cost, library by library

Written 2026-09-25 while building `demo-ledger/` and `demo-events/`, two more hosts of
web-base, auth-base and db-base, at the user's request: *collect the friction points
that would help complete or improve any of the three libraries*. Each entry was written
at the moment it was met, with what was measured, and says which library it belongs to.
An entry is evidence, not a decision: none of them has been acted on here.

## In order of what it costs

| | Library | Finding | Cost of leaving it |
|---|---|---|---|
| F7 | db-base | SQLite transactions that read before writing fail under concurrency; the README recipe omits `transaction_mode=IMMEDIATE` | an intermittent 500 in any host with such a transaction; **measured** |
| F1 | auth-base (db-base rule 3 in the way) | the JDBC `Store` and account plumbing is copied into every host, 236 lines | a fix to single-use redemption has to be made in every host |
| F2 | db-base | `arbitrate!` is unusable inside a PostgreSQL transaction, and SQLite hides it | passes every test here, fails in production |
| F6 | db-base | `parking` interleaves statements, never transactions, on SQLite | a host designs a test that proves nothing, or a timeout |
| F4 | auth-base | the identifier normalisation is private | a host's own stored addresses silently stop matching |
| F9 | auth-base | a rate-limited sign-in shows a blank page | a person locked out with no sentence |
| F3 | web-base | a form that fails validation can only reach an error page | every host hand-rolls the round trip |
| F5 | web-base | every host rewrites the same test browser | ~60 lines per host, and the cookie-jar lesson relearned |
| F8 | web-base / auth-base | `main`/`system` identical across hosts; port and link origin kept in step by hand | a moved port prints links to nowhere |

And one open question closed: db-base §11's "does `testing` earn a second consumer"
— it earned two (last section).

## Every host of all three libraries

**F1 · The identity plumbing is copied, unchanged, into every host** (auth-base, with
db-base's rule 3 in the way). `demo-ledger` needed `accounts.clj`, `auth_store.clj`
and three migrations — account, generation, challenge — and got them from `demo-tasks`
with `sed` renaming one namespace: **236 lines, not one of them about ledgers**.
`demo-events` is the same 236 again. Three hosts, three copies of the same JDBC
implementation of auth-base's `Store`, the same `register!` with the same
`arbitrate!`, the same `DELETE … RETURNING` whose single use is the security of a
magic link. db-base's rule 3 forbids it to live here (auth-base's port is ours, not a
stable third party's), and auth-base ships only an in-memory store. What the evidence
supports is a place for it that is neither — a small `auth-base-jdbc` artifact that
depends on auth-base and next.jdbc, or a documented template — because the part being
copied is exactly the part with a security property to keep, and a fix to it today
has to be made three times.

## db-base

**F2 · `arbitrate!` cannot be used inside a transaction on the production engine**, and
the first transaction a host writes meets that at once. `demo-ledger`'s `accept!`
deletes an invitation and inserts a membership in one transaction; the insert is
refused when the person is already a member. `arbitrate!`'s docstring says so honestly
— PostgreSQL rejects every statement after an error until rollback — but that leaves
the host to rediscover the portable alternative, `INSERT … SELECT … WHERE NOT EXISTS`
(or a savepoint of its own). The suite runs on SQLite, where the refused insert would
*not* abort the transaction, so the mistake would pass every test here and fail only
in production. A README recipe beside the ones added today ("a conditional write inside
a transaction") would have saved the detour; a savepoint-taking variant would be a
transaction helper, which §9 forbids and should keep forbidding.

**F6 · `testing/parking` cannot interleave two writers on SQLite once the parked
caller has written in its transaction.** Designing `demo-events`' capacity race: the
natural place to suspend a caller is between the writes of its transaction, and
there SQLite's single writer lock is already held — WAL lets readers past it, not
writers — so the other caller waits out its `busy_timeout` and the "interleaving" is
a timeout. Measured (F7's probe): in the default deferred mode a park after the
transaction's first *read* already pins a snapshot, and the caller then fails when it
writes; with `transaction_mode=IMMEDIATE` xerial takes the write lock when the
transaction begins — before the first prepared statement, which is where `parking`
intercepts — so no park inside a transaction interleaves anything at all. `parking`'s
docstring says a caller inside a transaction holds a lock and that SQLite "wants WAL";
the accurate sentence is stronger: **on SQLite, `parking` interleaves statements, never
transactions**, and the proof of an atomic statement on the production engine
(PostgreSQL's row locks under READ COMMITTED) is out of reach of both test engines
whatever the harness does. A host should be told that before it designs the test.

**F7 · On SQLite, a transaction that reads before it writes fails under concurrency,
and the recipe db-base published today does not prevent it.** Measured with
`demo-events`' own `join!` (read the event, then insert, then update), one caller
parked after its read while another joined to completion, WAL and
`busy_timeout=2000`:

| URL | the other caller | the parked caller, released |
|---|---|---|
| default (deferred) | `:going` | **`SQLITE_BUSY_SNAPSHOT`** — "another database connection has already written" |
| `&transaction_mode=IMMEDIATE` | waits, and here times out (the park holds the lock) | `:going` |

In a running host with no park, the deferred case is an intermittent 500 for whoever
loses the race, and `busy_timeout` cannot help: a stale snapshot is not a wait. Both
new hosts have that shape — `demo-ledger`'s `add-expense!` reads the members before it
writes the shares — and neither `demo-tasks` nor `demo` did, which is why nobody met
it. The README's "SQLite wants WAL and a busy timeout" recipe (added 2026-09-25) is
therefore incomplete: a host that uses transactions wants
`transaction_mode=IMMEDIATE` too, and the reason is exactly this table.

## auth-base

**F4 · A host that stores an address has to guess how auth-base spells it.** The
ceremony normalises every identifier — trim and lower-case by default, or the host's
own `:normalise` — before `subject-for`, `issue!` and `:on-unknown` see it, and the
function is private to the ceremony. `demo-ledger` stores addresses of its own
(invitations, to people who may not have signed in yet) and must compare them with
the account's, so it re-implements the default in its route. The day the host passes
a different `:normalise`, invitations silently stop matching the people they name.
A public `(auth/normalise ceremony identifier)` would make the host's copy the
library's.

**F9 · A sign-in the rate limit refuses shows the person a blank page.** The `429`
auth-base's `:issue` handler answers has an empty body (it now carries an honest
`Retry-After`, 2026-09-25), and nothing renders the host's login view for it. In
Chrome, measured on `demo-tasks`, the person gets the browser's own "this page isn't
working — HTTP ERROR 429", with no form and no sentence. The `:view` the host already
hands auth-base takes a state map (`:sent?`, `:spent?`); a `:limited?` state rendered
with the `429` status would give the person the page they were on and a reason.

## web-base

**F3 · A form that fails validation has nowhere to go but an error page.** web-base
passes a reitit `:coercion` through and renders a coercion failure as a 400 datum —
the right answer for an API, the wrong one for a person who typed "12,50" into an
amount field and expects the form back, with what they typed and a sentence under the
field. `demo-ledger` parses the amount by hand in the route and redirects back with a
flag in the query string, which loses the other fields and puts the validation in two
places. What a host of forms needs from the base is small: a way for a handler to
answer "re-render this route's page with these errors and these values" — the
round trip every server-rendered form library ends up owning.

**F5 · Every host writes the same test browser.** `demo-ledger/test/.../support.clj`
is `demo-tasks`'s, `sed`-renamed: 179 lines, of which the cookie jar, `GET`, `POST`
with the CSRF token of the page in front of it, and `sign-in!` are about sixty, and
none of them is about the host. web-base's `testing` namespace has the parts —
`cookies`, `with-cookies`, `csrf-token` — and not the assembly, and the assembly is
where the first host lost an afternoon: chaining from the last response alone drops
the session cookie at the first page that sets none, so every test past the login
looked gated. An accumulating `(browser handler)` with `GET`/`POST` in web-base's
testing namespace would carry that lesson for every host instead of each one
relearning it. (The magic-link half, `sign-in!`, reads auth-base's challenge table
and belongs with F1's artifact rather than in web-base.)

## Every host, again: the wiring

**F8 · `main.clj` and `system.clj` are the same file in every host.** Across
`demo-ledger` and `demo-events` they are identical modulo the namespace — 59 lines —
and `demo-tasks`' differ only in comments and one extra route argument. Two things in
them are more than boilerplate:
- **The sign-in link's origin is kept in step with the server's port by hand.**
  `main` assoc-es the port into web-base's server *and* into the host's auth config's
  `:base-url`, because auth-base builds links for a server it knows nothing about and
  web-base knows the port and nothing about links. Forget the second assoc and every
  link points at a door nobody is standing at (demo-tasks' docstring says so; every
  host repeats the code). web-base's Integrant key could publish the origin it serves,
  or auth-base's key could take a ref to it.
- **The console `deliver!` is the only delivery any host has.** Three hosts, three
  copies of the same five `println`s. Not a library defect — delivery is the host's —
  but a sign that auth-base's README example is what everybody copies.

## Evidence that answers an open question

**db-base §11's one open row — "whether `testing` earns a second consumer" — has its
answer.** Opened this morning because `parking` had been written by one host. Both new
hosts' suites read through `testing/rows` (via their `support/rows`), and both reach
for `parking` where their contract needed an interleaving: `demo-ledger` to pin
`transaction_mode=IMMEDIATE` under a writer committing mid-transaction, `demo-events`
for the over-booking control and the same pin. Nothing in either needed a function the
namespace does not have; what they needed was F6's sentence about what `parking` can
and cannot interleave on SQLite.

**One thing that is nobody's library: every host writes the same `changed`.**
next.jdbc answers a write with `{:next.jdbc/update-count n}`, and three hosts now carry
the same one-line `(or (some-> result vals first) 0)` to read it. db-base does not wrap
next.jdbc and should not start (§9); noted so nobody proposes it as a helper here.
