# db-base — specification

> **Coordinates.** Directory `db-base` · repository `arkaitz-dev/db-base` · artifact
> `dev.arkaitz/db-base` on the day there is one.
>
> **Status.** Specification. No code. Written 2026-09-09.
>
> **Provenance.** The third of three: `web-base` serves HTTP, `auth-base` turns
> someone into a subject, and this opens the connection both of them are careful not
> to open themselves. web-base §2 says it *never opens a database*; auth-base §7 says
> storage is a port with an in-memory default. This is what the host plugs into those
> holes when it has a real database, and **§12 argues honestly about whether it
> deserves to exist at all.**

## 1 · What it is

**A pooled connection with a lifecycle, migrations that run before anything serves,
and the decisions behind both.**

It is a **library you call**, and mostly it is a **component you start and stop**, the
same shape web-base's server has: a map goes in, a handle comes out, and `stop` closes
what `start` opened.

## 2 · What it is not

- **Not a query language.** No DSL, no threading macro over SQL, no opinion on
  HoneySQL. `next.jdbc` already takes a datasource and a vector.
- **Not an ORM or an entity mapper.** It never learns what a row means.
- **Not a schema.** The host's tables are the host's, including their names.
- **Not a database.** The engine is the host's choice and its driver is the host's
  dependency.
- **Not a repository pattern.** A function that takes a datasource is not improved by
  being wrapped in a protocol first.

## 3 · The rule that governs everything here

**Impose what you are, not what you use.** web-base §10 settled this and it binds here
harder, because a data layer is where transitive dependencies breed. A consumer of this
library must receive a connection pool and a migration runner, and **not** a JSON
codec, a logging backend, a date library or an opinion about their query builder.

Two consequences that decide the whole design:

**The engine is not chosen here.** Any JDBC database. The host brings the driver, and
the host writes SQL for the engine it brought. Nothing in this library may be
PostgreSQL-shaped, which is a discipline rather than a preference: the moment one
function assumes `jsonb` or `LISTEN`, the library belongs to one engine.

**And nearly all of that agnosticism is free.** The pool takes a JDBC URL, the migration
run hands the host's SQL to a library, and readiness is `Connection.isValid` — none of
the three can be engine-shaped, because JDBC and the JDK are doing the work. **The only
place this library writes SQL of its own is §8**: one table and five statements. So the
discipline costs something in exactly one place, and it is worth saying because it
decides what choosing an engine would change here — almost nothing. The engine matters
in the host's SQL and in the adapter auth-base writes over the datasource this library
handed it, and neither of those is this library's to decide.

**It may implement a port defined by a stable third party. It must never depend on a
sibling module of ours.** Ring's session store protocol lives in `ring-core`, is three
functions long, has not changed in a decade, and is already on every relevant
classpath — implementing it costs nothing and imposes nothing. auth-base's store port
is *ours*, versioned with us, and an implementation living here would lock two of our
own modules to each other's releases in both directions. That adapter belongs in
auth-base or in the host, and §8 shows how small it is.

### How this is proven

"Nothing may be PostgreSQL-shaped" cannot be checked by reading, and a discipline
nobody can falsify is decoration. web-base already had this problem and answered it the
same way: the rule it could not enforce by review, it enforced by a scan, and wrote in
that scan's own docstring that it is the only signal.

**The suite runs against two engines from different dialect families**, embedded, with
no infrastructure, on the test classpath and never in `:deps`. One engine proves
nothing: a suite that only ever runs on H2 passes with `MERGE` in it, and one that only
runs on PostgreSQL passes with `ON CONFLICT`. **Which two is not recorded here** — it is
a tool choice, and tools go in `CLAUDE.md` once they have been run and observed. What is
recorded is the criterion, and one measured warning: the pair should be a strict engine
next to a permissive one, because a strict engine rejects everything non-standard while
a permissive one catches the opposite mistake — SQLite accepts an over-long `VARCHAR`
in silence.

**The guard is checked before it is trusted.** Each forbidden dialect is fired directly
at each test connection and must be seen to fail there. Without that, a test harness
that has drifted — an engine quietly in a compatibility mode — makes the whole thing
decorative, permanently and with no symptom. This is not hypothetical: H2 in PostgreSQL
mode accepts `ON CONFLICT DO NOTHING` and rejects the upsert form, which is neither what
one would guess nor what it is usually said to do.

**A source scan is worth having and is not enough.** It reads string literals only, and
case-sensitively, or it fires on this document's own prose and on `(merge …)`; and it
cannot see SQL built by concatenation, exactly as web-base's scan admits about its own
narrowness. Every scan asserts first that it reached the real sources, because a walk
over nothing is green for the wrong reason.

**The single most valuable test in the library** is a concurrent delete and write,
asserting the deleted session does not come back. It is what stops someone later
"fixing" the zero-rows-updated case of §8 with an upsert, and auth-base already states
the standard: a test that does not run the two concurrently has not tested it.

## 4 · The membership test

**Would a bicycle rental and a clinic's appointment book need this, unchanged?** Both
open a pool at boot, run migrations before serving, and close it on the way out.
Neither needs a query builder chosen for them.

## 5 · The boundary

**It owns**

- the datasource: built from configuration, pooled, started and stopped;
- migrations: found where the host says, applied in order, before anything serves;
- a readiness answer: whether the database is actually reachable;
- **its own** tables, when it ships an implementation of a published port, and only
  those.

**It receives**

- configuration, as a map, already resolved. It never reads a file, never reads an
  environment variable, and never looks for either. web-base learned that lesson
  explicitly: a library that knows a filename can look for it, and then the directory
  a process started from decides which database it opens. What the pool it hands back
  and the host's driver read on their own is another matter, and §6 says whose that is.
- a place to find migrations.

**It never learns**

- what a table means, what a row is, or what the host calls anything;
- which engine it is talking to, beyond what JDBC exposes.

## 6 · The datasource as a component

The public namespace is `dev.arkaitz.db-base`, and `start` and `stop` are ordinary
functions returning a plain map, the shape web-base's server already has.

```clojure
(def db (db/start {:jdbc-url   "jdbc:…"
                   :user       …
                   :password   …
                   :pool       {:max 10 :timeout-ms 5000}
                   :migrations {:dir "db/migration"}}))   ; or :migrations :none
;; => {:datasource … :migrations-applied n}
(db/ready? db 2)                                          ; => true / false
(db/stop db)
```

`:datasource` is a `javax.sql.DataSource` and nothing more, so the host's choice of
`next.jdbc` stays the host's.

`start` fails loudly and names the key when configuration is missing or malformed,
which is web-base's rule and earns its keep the same way: a pool that silently defaults
to something is a production incident with no symptom in development.

**A password is never defaulted and never generated.** It arrives or the call fails.
The same reasoning as web-base's session key: a value invented at startup works
beautifully until it does not, and differs per instance. **Arriving means the key is
present and its value is a string, the empty string included**, and the same holds for
`:user`. SQLite and an embedded Derby take no password and an embedded H2 is
conventionally given `""`: a `""` the host wrote is a value it chose, whereas requiring a
non-empty one would force the host to invent exactly the value this paragraph forbids.
`nil` and a missing key fail.

**`:pool` takes `:max` and `:timeout-ms`, both required, neither defaulted.** The timeout
bounds every wait for a connection, and the liveness check HikariCP makes through the
driver before lending one that sat idle only as far as the driver honours the same
number, as `ready?` below says. It is not left to the pool's own default, because then
the guarantee below would depend on which pool sat underneath and on what its authors
thought a sensible wait was. **It is at least 250 milliseconds**: that is HikariCP's default
floor, below which it throws and at zero it means no deadline at all, and it is refused
here as a named key rather than surfacing as the pool's own exception. A JVM started with
`com.zaxxer.hikari.timeoutMs.floor` raised makes the pool refuse more than that; the
refusal still arrives as `ex-info` under the same key, with the pool's exception kept.
Neither key is unbounded above: `:max` is at most 2147483647, because HikariCP's setter
takes an `int`, and `:timeout-ms` at most 2147483646, because HikariCP casts its timeouts
to `int` and reads `Integer.MAX_VALUE` itself as no deadline at all.

**`:migrations` is required, and refusing them is a value it takes, not a key left
out.** Optional-with-a-default is the trap: a host that types `:migration` would get a
pool with no migrations and no complaint, which is the incident described two paragraphs
above. `:migrations-applied` is then **absent from the handle, not zero** — zero says
they ran and there was nothing to do, absent says they did not run.

**One function, not two.** `start` opens the pool and runs the migrations in the same
call. A separate `migrate!` turns §7 into "they run if the host remembers", and a pool
that must not migrate is the thirty lines §12 talks about, written in the host.

`stop` closes the pool. A component that cannot be stopped cannot be restarted, and a
REPL that cannot restart is a REPL nobody uses.

**`ready?` takes its timeout and has no default.** It answers whether the database is
reachable, over `java.sql.Connection.isValid`, and past the two refusals below it throws
nothing but an `Error`: a borrow that fails, an `isValid` that throws and a connection
that cannot be given back all answer false. Not `SELECT 1`:
that statement is rejected by HSQLDB and by Derby, and `SELECT 1 FROM DUAL` is rejected
by HSQLDB, Derby and SQLite, so **there is no portable readiness query** and the
one-line version in the host is a dialect table by the back door. The timeout is in
seconds, as `isValid` takes it, and is validated as a **positive** integer before the
call: `isValid(0)` means no timeout at all, which is a hang, and a negative one throws on
HSQLDB, on Derby and on pgjdbc. This is deliberately not web-base's `:port` check, which
accepts zero because port 0 means an ephemeral port; here zero means no deadline. The
largest is 2147483647, because `isValid` takes an `int`. A timeout it refuses arrives as `ex-info`
under `:config-key [:timeout]`: the timeout is an argument rather than configuration, but
a host that already dispatches on `:config-key` should not learn a second key for it. So
does a handle whose `:datasource` is not a `javax.sql.DataSource`, under `:config-key
[:datasource]` and without its value, which may be the configuration map, password and
all: a readiness check wired to the wrong thing should say so rather than report the
database down. (Decided with the user on 2026-09-13.)

**The timeout bounds `ready?` only as far as the driver honours it.** `isValid` runs on
the caller's thread, and so does the liveness check HikariCP makes through the driver
before it lends a connection that sat idle, so what bounds both is the driver and the
host's URL. pgjdbc applies the timeout to its socket, unless the URL's `socketTimeout` is
already shorter; H2 ignores it, and over TCP a server that stops answering holds the call
until a `NETWORK_TIMEOUT` in the URL expires — still blocked after 12 seconds without one,
back in 2 with `NETWORK_TIMEOUT=2000`. Bounding the caller from here would take a thread
of this library's per call, and during such an outage up to `:max` of them stuck, holding
every connection of the pool until the driver lets go. (Decided with the user on 2026-09-13, after measuring both.)

**A boot fails within a bounded time.** Acquiring a connection is a blocking call,
and a boot that waits forever is a hang, not a boot — the same reason web-base forces
`:join? false`. `start` borrows the moment the pool exists, and `[:pool :timeout-ms]`
bounds that wait, measured against a socket that accepts and never answers; the failure
then leaves `start` after HikariCP's wait to close the pool, which `start`'s docstring
quantifies. A later borrow and `ready?` are bounded only as far as the paragraphs above
say.

**Failure is `ex-info`.** The message names the offending thing, the data carries
`:config-key` as a vector path, and a driver's own exception is kept as `ex-cause`
rather than swallowed. That is web-base's measured vocabulary, and a host that already
dispatches on `:config-key` should not learn a second one. **No exception type of our
own.** One rule web-base did not need: **the password and the JDBC URL are never
echoed, in the message or in the data** — presence, never value, as auth-base already
reports `:token-present?`. What a driver puts in its own exception is the driver's.

### Where that map comes from

§5 says the configuration arrives resolved. This says where it is resolved from,
because a rule about what a library may not read says nothing about where an operator
should put a password.

**The connection details live in an EDN file that is not in the repository.** It is the
traditional `.env` in another format, and it is where web-base already keeps its signing
key. `*.local.edn` is git-ignored in all three modules, which is what makes the
statement true rather than aspirational. The password sits in that file literally, and
that is the right place for it: what a password may never do is travel with the
repository, be invented at startup, or come out of a file this library went looking for.

**The host reads it; this library does not.** A host that already uses web-base has its
readers and the `#wb/env` tag; one that does not reads the file with `clojure.edn` in
three lines. Either way what reaches `start` is the map of §5, and this library never
learns a filename. auth-base draws the same line for its bootstrap list — it arrives as
data the host passes in — and drawing it is exactly what lets both modules compose with
web-base without either depending on it.

**What the pool and the driver read on their own belongs to the host's JVM.** The promise
above is this library's: it reads no file, no environment and no property to find its
configuration. The component it hands back is not so quiet, and saying otherwise would be
a promise no JDBC stack can keep. HikariCP honours `hikaricp.configurationFile` when the
JVM sets it — a properties file that can set anything this library does not set on the
pool, `connectionInitSql` and `dataSourceClassName` included — and a dozen other JVM
properties; a driver reads its own files by name, pgjdbc its `driverconfig.properties`,
`.pgpass` and `pg_service.conf`. This library neither reads nor refuses them, for the
reason §3 leaves the driver to the host: they configure the JVM and the driver the host
brought. A host that sets one gets the pool it asked its JVM for, which is not
necessarily the one the map describes: a `dataSourceClassName` from the file replaces the
map's URL with no more than a HikariCP warning, which only a host that brought a logging
backend hears. The map's user and password are not replaced that way on the JDBC-URL path
this library configures: it always sets them, HikariCP asks for every connection with
them, and its driver data source puts them over any `dataSource.user` or
`dataSource.password` the file names. A `credentialsProviderClassName` from the file does
replace them, with no warning at all: HikariCP then asks the provider instead of the map.
The host's JVM is also where a failure may arrive other than as `ex-info`, after the map
has been validated: a `hikaricp.configurationFile` that cannot be found, read or applied
makes HikariCP throw its own exception before this library has set anything on the pool,
and one that loads but describes a pool HikariCP refuses fails when the pool is
constructed; neither is wrapped. A file that builds a pool whose every connection then
fails does arrive as `ex-info`, as the connection failure it causes. (Decided with the
user on 2026-09-12, after a review panel weighed refusing the property at `start`.)

## 7 · Migrations

**They run at boot, before the first request, and the process does not serve if they
fail.** A database whose shape is one deploy behind the code is a corruption that
reports itself as a bug somewhere else entirely.

What this library owns is the *running*, not the *writing*: it finds a directory the
host names, applies what has not been applied, in order, once, recording what it did.
The SQL inside is the host's and speaks the host's engine.

**Finding zero migrations in a directory the host named is an error, not success.**
An empty or misspelt directory is the schema one deploy behind, arriving silently and
reporting itself later as a bug somewhere else. A run that found nothing must say so
loudly, the same way a test run that ran no tests is a red. **Zero *pending* is not that
case**: a second boot finds every migration already recorded, applies none, and succeeds
with `:migrations-applied 0`, which is what §6 says zero means. The count that must not be
zero is the count found, and checking it on every boot is what catches a directory
misspelt after the first one.

**What a failure at migration N leaves behind.** Without this the section cannot be
tested at all: any assertion would be inventing the bound it claims to check. The floor,
and only the floor, because it is the part that holds on every engine — several do not
run DDL inside a transaction, so batch atomicity is a gift from the engine and never the
contract: **everything before N is applied and recorded, N is not recorded, `start`
throws naming N, and the next boot attempts N again.** A migration that half-applied on
an engine without transactional DDL is a repair the host performs, not a state this
library pretends to unwind.

**Which migration library sits underneath is not settled here.** It is a real decision
with two or three defensible answers, and this workspace's rule is that tools are
recorded once they have been run and observed, never chosen from convention. Two
requirements constrain the choice without making it, and both come from rules already
written down. It must support **two independent runs, with different control tables and
different sources** — §8 explains why there are two. And its lock must offer a
**bounded wait**: two instances booting at once is the ordinary case, and a lock without
a deadline turns it into a hang.

## 8 · The session store it ships

This is the reason the library is worth existing today, and it settles a question that
came up while auth-base was specified.

Ring's session store port is three functions — `read-session`, `write-session`,
`delete-session` — keyed by session id. web-base uses that port and ships nothing but
the signed-cookie implementation; it accepts any `{:store s}` the host hands it, and
that is the socket this fits.

**What a stored session buys, stated exactly, because the obvious answer is wrong.**
Ending every session of a subject does *not* need one: auth-base §10 revokes by
generation on the subject and works with every store, the cookie included. Two things
survive that, and both are properties of the cookie rather than opinions:

- **A copied cookie never expires.** The sealed payload carries no timestamp, so
  `Max-Age` is the only expiry there is, and `Max-Age` is enforced by an honest client.
  A stored session expires on the server or not at all.
- **`delete-session` under the cookie store cannot revoke anything.** It seals and
  returns a *fresh empty* cookie; the old sealed value stays cryptographically valid
  forever. Ending one session — one stolen device, the others left alive — needs a row.

Generation revocation is also not free: its cost is one store read per request. The
choice is not infrastructure against none; it is which table.

The implementation is small enough to state completely, and every line below is a way
it fails silently if written from intuition.

**The two statements, and the trap.** Ring never asks a store to upsert. The key
reaching `write-session` is non-nil only when `read-session` returned that row in the
same request, because the middleware sets the request's key to nil on a miss. So a nil
key is an `INSERT` under a key this store mints, a non-nil key is an `UPDATE`, and both
are ANSI SQL that needs no `ON CONFLICT`, no `MERGE` and no dialect. **An `UPDATE` that
touches zero rows is the correct outcome and must do nothing**: the row is gone because
someone logged out, revoked it, or it expired between the read and the write.
Re-inserting it there undoes a revocation — which is the whole point of §8 — and adds a
primary-key race to a path that could not previously fail. **`UPDATE`-then-`INSERT`-if-zero
is the trap**, the same shape as `take-challenge!` written as read-then-delete: it is
engine-neutral, it looks defensive, and it resurrects the dead. Ring's own `MemoryStore`
does exactly that (`swap! assoc`), so the first person to check the reference
implementation will find the wrong answer.

**That is not a hypothesis, and it is the strongest evidence this document has.** The
one JDBC session store the ecosystem has, `luminus-framework/jdbc-ring-session`, is
written exactly that way: `update!`, and on zero rows affected, `insert!`. Its
maintainer gives the reason in that project's issue 13 — *the library mirrors the
behavior of the memory store in Ring core.* Mirroring an in-memory `assoc` is safe in
memory and is not safe in a row another request can delete, so a session logged out or
revoked while one of its own requests is in flight comes back, with its old contents.
It has some 57,000 downloads and the code has read that way since 2019. **The case for
§8 is therefore not that this is hard to write** — it is thirty lines — **but that the
ecosystem's existing thirty lines followed the reference implementation into the wrong
answer.** That is what §12 means when it says the decisions are the value.

**Four more that fail silently.**

- **`read-session` returns `nil` for a row that is not there, never `{}`.** `{}` is
  truthy, so the middleware keeps the key and then asks for an `UPDATE` of a row that
  does not exist — the store manufactures the very case it was avoiding. An expired row
  is a miss, so the expiry predicate belongs in the read.
- **`delete-session` accepts a nil key** and returns without touching the database.
  Ring passes nil on the rotation path, which web-base already documents.
- **The key is a `java.util.UUID`**, which is what Ring's own store does and what the
  protocol's docstring asks for. Nothing of ours in a var root.
- **Sessions are EDN, `pr-str` and `clojure.edn/read-string`, with `:readers` passed
  through.** It is what Ring's cookie store does and it imports no codec §3 forbids.
  Ring also asserts the round trip *on write*; a store that only calls `pr-str` will
  store a session that cannot be read back, and will do it quietly.

**The table, and why these types.** One table: `VARCHAR(36)` key, `CLOB` data, `BIGINT`
expiry as epoch milliseconds — auth-base's own convention. Measured on H2, H2 in
PostgreSQL mode, HSQLDB, Derby and SQLite: this runs on all five. `TEXT` does not; it is
rejected by HSQLDB and by Derby. `VARCHAR(n)` for the data is worse than it looks — an
over-long session throws on H2, HSQLDB and Derby and is silently truncated by SQLite, so
the failure mode itself would depend on the engine. `CREATE TABLE IF NOT EXISTS` is not
an escape either: Derby rejects it, it would run outside §7's boot gate, and a table
with no recorded version can never be changed.

**The five statements are ANSI by measured choice, not by luck, and a second engine
would be a sibling namespace rather than a new layer.** Ring's port is already the
abstraction: `dev.arkaitz.db-base.session.postgres` beside a `…session.mysql` on the day
one is needed, each implementing the same three functions of a third party's protocol
under §3. Adding an engine is adding a namespace. **A dialect table here would be the
mistake**, and a tempting one, because it would make correctness a function of an
enumerated list — and the first engine absent from that list breaks with no symptom
until it is in production. It would also be more code than the five statements it
abstracts, and being the only implementation, it would be shaped around the one engine
that existed when it was written.

**Two control tables, not one, and the library's run goes first.** The library's table
names are written here and never generated. A single shared table breaks three ways,
each silently: a library upgrade adds a migration that sorts below ones already applied
and is skipped forever; a tool that checksums applied migrations bricks every host's
boot the day the library edits its own file; and the host's `reset` drops the library's
history with its own.

It ships **here**, with its own migration, in its own namespace, because it implements
a third party's port under rule §3 and because its table is this library's own rather
than the host's.

**What it costs, as a number, so §12 can be applied to one.** This section is what puts
`ring/ring-core` in `:deps`, and that is **1.02 MB in nine jars** — measured, 1,066,078
bytes. `ring-core` itself is 34,567 of them: the rest is what it drags, and most of the
rest is `commons-io` at 585 KB, `commons-codec` at 354 KB and `commons-fileupload2-core`
at 70 KB. **File-upload machinery on the classpath of §4's bicycle rental, which never
serves HTTP.** It is worth writing down next to the Integrant decision of §10, whose
whole closure is 23,504 bytes — this section costs forty-five times that, and it was the
one nobody thought to weigh. A host that already runs web-base or auth-base pays it
once, since both impose ring-core too. A host that only wanted a pool pays it for a
session store it may never construct, and §12's exit condition is where that gets
settled.

**auth-base's store is a different case and does not ship here.** Its protocol is ours,
and the adapter is a page of code the host writes over a datasource this library
already gave it:

```clojure
(defrecord JdbcStore [ds]
  auth/Store
  (take-challenge! [_ token] …)   ; one statement, returning and deleting atomically
  …)
```

That page belongs where the protocol is, or in the application. It does not justify
binding two of our libraries to each other's version numbers.

## 9 · What it must never own

Written down before it is tempting, because each of these has a plausible first step:

- **A query builder.** "Just a small helper for `where`" is how one arrives.
- **Entity mapping.** The moment a row becomes a record with a namespace, the library
  knows the domain.
- **A connection-per-request middleware.** That is the host's transaction boundary to
  decide, not this library's, and deciding it here makes every consumer's request
  handling this library's business.
- **Read replicas, sharding, tenancy.** Real needs, all of them the host's.
- **A `db` global.** Ambient state is the rule web-base was built to avoid; a datasource
  in a var root is the same mistake with a connection attached.
- **A background thread.** A sweeper on a timer is the previous entry wearing a
  different hat: a lifecycle the host did not ask for, and a shutdown path that will be
  forgotten. Scheduling is the host's.

## 10 · Scope

**In**: the pool and its lifecycle, migrations at boot, a readiness check, and
implementations of published third-party ports whose tables are its own.

**Out**: everything in §9, the engine, the schema, the SQL, and the driver.

**Integrant is used, not imposed.** `start` and `stop` are ordinary functions, and an
optional namespace ships the `init-key` methods for a host that wires with Integrant;
a host that does not is not blocked. This is web-base's rule rather than a preference of
this library's, and it is stated there as the general one: it is what well-behaved
Clojure libraries do. The namespace is the only place that may reference Integrant, and
a scan enforces it, because an illegal require compiles, loads and passes every other
test.

**One key, never two.** The pool and its migrations are one component. Two keys let a
host wire the pool and omit the migrations, which deletes §7's guarantee with no symptom
until the first request meets a missing table.

## 11 · Settled, and open

**Settled**

| | |
|---|---|
| Engine-agnostic: any JDBC, the driver is the host's | §3 |
| Configuration arrives as a map; it never reads a file or an environment | §5 |
| The connection details live in an EDN outside the repository, read by the host | §6 |
| It may implement a third party's port, never depend on a sibling of ours | §3, §8 |
| Migrations run at boot or the process does not serve; zero applied is an error | §7 |
| A stored session store ships here, with its own table and its own control table | §8 |
| The store never upserts, and a zero-row update is correct | §8 |
| §3 is proven against two engines, not by review | §3 |
| Integrant is used, not imposed: an optional namespace, one key | §10 |
| A readiness check belongs here, and its timeout has no default | §6 |

**Open**

- **The pool** and **the migration library** underneath. Both are real choices and
  neither is made from convention. §7 states the two requirements the migration library
  must meet, which constrain the choice without making it.
- **Which two engines** the suite runs against. §3 states what the pair must satisfy;
  the names go in `CLAUDE.md` once they have been run.
- **Reclaiming expired rows**: on write, or by an operator calling a function this
  library exposes. Not on a timer — §9. This is only about space: an expired session is
  already invisible, because §8 puts the expiry in the read, and that part is settled.

**Measured, but not settled — the residue.** Both are the kind of trap this document
writes down before there is code: whether a real network driver honours the `isValid`
timeout when a socket accepts and never answers — H2 over TCP was measured and does not
(§6), and pgjdbc's handling was read in its source but not run against such a socket;
and whether `UUID/randomUUID` is safe under a native image, since the JDK holds that
`SecureRandom` where a scan of our own var roots cannot see it.

## 12 · The honest argument against this library

It is the thinnest of the three and it may not deserve to exist.

Wiring a pool is thirty lines. Running migrations is twenty more. A library whose whole
content is fifty lines of wiring is a decision record with code attached, and a decision
record can be a paragraph in the host instead.

The case in favour is that the *decisions* are the value and they are the part that
gets re-litigated: which pool, which migration tool, what the configuration looks like,
what happens when a migration fails at boot, whether the password may ever be
defaulted. Those are settled once here rather than argued in every project, and the
session store of §8 is real code that would otherwise be copied.

**The exit condition, so this is falsifiable.** If, after a second consumer, this
library is still nothing but the wiring of §6 and §7, then it should be folded back
into the applications and deleted. web-base §7's rule points the same way from the
other side: when in doubt, leave it in the consumer, because moving code *into* a
library later is cheap and getting it back out is not.

**As of 2026-09-09 it has no consumer at all.** Not one, never mind two. That is the
sentence to read before adding anything to §10's *In* list: what is written above is a
design for consumers that do not exist yet, and web-base §7 says what happens to a base
built before there are two — it fits the first one, and becomes a part of that
application maintained separately. **Nothing enters the scope without a consumer that
asked for it.**

**And a second thing would falsify the case in favour, earlier than the exit
condition.** The argument above is that the decisions are the value. If, once the pool
and the migration library of §11 have actually been run and recorded, they turn out to
be the choices every host would have made anyway, then the decision record is a
paragraph and not a library — without waiting for a second consumer to prove it.

What is *not* subject to that, and is the honest reason this document is worth its
length today, is not the store's existence but its contract: that a zero-row update is
correct, that an upsert resurrects a revoked session, and that Ring's own reference
implementation does the wrong thing. That is the part which would otherwise be copied —
copied wrong.
