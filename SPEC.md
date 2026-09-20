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

**The host's engine is PostgreSQL, and saying so changes nothing above.** (Decided with
the user on 2026-09-20, from measurements taken that day.) All three candidates were run
against this library unmodified, with the same fixtures and the same four boots:
PostgreSQL 18.6, MariaDB 13.0.2 and MySQL 26.7.0 each applied the migrations, refused a
history the source had lost, took the lock and gave it back. So the decision was not made
on whether they work — they all do — but on two things. `DELETE … RETURNING` makes
auth-base's single-use take atomic in one statement; MariaDB has it and MySQL 26.7 still
does not, which would turn that ceremony into a conditional `UPDATE` and a read. And
pgjdbc honours `isValid`'s timeout against a socket that stays open and answers nothing,
where H2 over TCP does not (§11) — an operational property no dialect table shows.

**What that decision does not buy.** It is the host's engine, not this library's. Nothing
here may assume it, and the guards matter *more* now rather than less, because the
tempting engine is the one everybody has in front of them: the suite still runs on H2 and
SQLite, **neither of which is the production engine, on purpose** — a suite that ran on
PostgreSQL would accept every PostgreSQL-shaped mistake in silence — and the scan below
still refuses to find `ON CONFLICT`, `RETURNING` or `jsonb` spelled anywhere in `src`,
all of which PostgreSQL takes happily. §8's types were already chosen by measurement
across five engines and do not change. The day this library is PostgreSQL-shaped, it has
stopped being what §12 argues it barely deserves to be.

**And nearly all of that agnosticism is free.** The pool takes a JDBC URL, the migration
run hands the host's SQL to a library, and readiness is `Connection.isValid` — none of
the three can be engine-shaped, because JDBC and the JDK are doing the work. **Only two
places write SQL of this library's own**: §7's migration lock — one table, the ANSI
statements that create, take and give it back, and the read of the control table that
decides whether to take it — and §8, one table and five statements. So the discipline
costs something in exactly two places, and it is worth saying because it decides what
choosing an engine would change here — almost nothing. The engine matters
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
runs on PostgreSQL passes with `ON CONFLICT`. **Which two is not this section's business** — it is
a tool choice, recorded in `CLAUDE.md` and, once run, in §11. What is
recorded is the criterion, and one measured warning: the pair should be a strict engine
next to a permissive one, because a strict engine rejects everything non-standard while
a permissive one catches the opposite mistake — SQLite accepts an over-long `VARCHAR`
in silence.

**The guard is checked before it is trusted.** Each forbidden form is fired at a fresh
connection of each engine, on the URL the suite boots with, and the whole matrix — which
engine refuses which — is pinned, not only the rule that at least one of the two must
refuse each form. Without that, a test harness that has drifted — an engine quietly in a
compatibility mode — makes the whole thing decorative, permanently and with no symptom.
This is not hypothetical: H2 in PostgreSQL mode accepts `ON CONFLICT DO NOTHING` and
rejects the upsert form, which is neither what one would guess nor what it is usually
said to do. Two limits come with it. A mode set on the pool's own path, by one of the JVM
properties §6 records as the host's, is not on this path and is not seen. And what the
pair cannot see at all is a form both engines accept: a `TEXT` column, and the existence
clause on `CREATE TABLE` that §7 avoids — HSQLDB and Derby are what refuse those, so §8's
column types stand on the measurement across five engines rather than on this suite. The
source scan names the existence clause, because §7 has code that must not spell it;
`TEXT` waits for §8 to have any.

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
                   :migrations {:dir "db/migration" :lock-wait-ms 60000}})) ; or :migrations :none
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
they ran and there was nothing to do, absent says they did not run. A map takes `:dir`,
the classpath prefix the migrations live under, and `:lock-wait-ms`, how long a boot
waits for another instance to finish migrating; both are required and neither is
defaulted: the prefix for §5's reason, the wait for §7's.

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
`:config-key` as a vector path, and `:migration-id` when one migration of §7 is to blame,
and a driver's own exception is kept as `ex-cause` rather than swallowed. Everything §7
refuses carries `[:migrations :dir]` with the prefix as `:value` — the subsystem, since
the lock table and the control table are this library's names and no key of the host's is
to blame — except a wait that ran out, which carries `[:migrations :lock-wait-ms]`, the
wait as `:value`, `:dir`, and the `:holder` and `:acquired-at` the row gave. A lock that
cannot be given back carries the `:holder` whose row it is, so the repair the message
names can be checked before it is run. An `Error`, and
the `InterruptedException` ragtime raises between two migrations, are not failures of the
configuration or of the database and pass through unwrapped — after the pool, if one
opened, has been closed, which HikariCP will not do on an interrupted thread unless the
flag is cleared around it (measured). **If closing is what threw the `Error`, that
`Error` is what leaves**, carrying the failure it interrupted as its suppressed; between
two `Error`s the first keeps the way out, as a `try`-with-resources would have it. A
close that throws anything else is suppressed into the failure and changes nothing, which
is what keeps a boot's refusal an `ex-info`.

**The driver is checked as the pool will find it, not as this library does.**
`DriverManager` answers by the calling class's loader, and the loader Clojure compiles
this library into is not the one HikariCP's class came from. A driver added to a running
JVM — `add-lib` at a REPL — is therefore visible here and not to the pool, and the pool's
own refusal for that carries the JDBC URL (measured 2026-09-20). So `start` refuses it
first, naming the driver class and no URL, before anything opens. The question is asked by
walking the loaders the pool's own delegates to, because §5 forbids src to load a class by
name and §3's scan enforces it — which is how this check came to be written twice.

An interrupt that lands while the pool is lending
the boot its connection is HikariCP's to report: it arrives as that pool's `SQLException`,
wrapped as the borrow's own failure, with the flag still set. That is web-base's measured vocabulary, and a host that already
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

What this library owns is the *running*, not the *writing*: it finds the migrations under
a classpath prefix the host names, applies what has not been applied, in order, once,
recording what it did. The SQL inside is the host's and speaks the host's engine.

**The source is a classpath prefix, not a directory on disk.** A path on disk resolves
against the directory a process started from, which is exactly what §5 forbids from
deciding anything, and an uberjar has no disk to look at. A host whose migrations live in
a directory puts that directory on its classpath. What a prefix means is ragtime's:

- only the files directly under it count, not those in its subdirectories;
- every classpath root holding the prefix contributes, so two jars that both carry it
  merge;
- it is looked up through the context class loader of the thread that calls `start`;
- a jar built without directory entries answers nothing for it, which the next paragraph
  turns into a boot that fails.

Migrations are ragtime's files: SQL in an `up` file with an optional `down` beside it, or
EDN, identified by name and applied in the string order of those names, so numbers are
zero-padded. A `down` never runs here. An EDN migration may name a function instead of
SQL; it runs with the datasource, and it is the host's code.

**What stops the boot before any pool exists**, because each of them is a migration that
would never run and never say so:

- **a file ragtime would not load as the migration it looks like** — an extension it does
  not know, `001-a.up.SQL` among them, or an SQL name that is neither `<id>.up.sql` nor
  `<id>.down.sql`, numbered or not. Ragtime passes the first over and loads the second
  under an empty id; the refusal names the file. This library lists the prefix itself,
  with ragtime's own reader, for exactly this;
- **two migrations under one id**, an SQL file and an EDN file named alike;
- **a migration with no id**, which is what several migrations in one EDN file with none
  named come to;
- **a migration with nothing to run up**: a `down` file with no `up`, an `up` whose
  statements are all blank — H2 runs an empty statement without a word — or an EDN
  migration naming a function that does not exist in a namespace that does, which
  `requiring-resolve` answers with nothing;
- **a source that cannot be read, or a prefix that cannot even be listed**, naming the
  prefix rather than the file, with the exception the loading threw as the cause — the
  EDN reader's, or Clojure's own for a namespace that does not exist: ragtime parses them
  all before this library sees any of them.

In each case ragtime would otherwise run something other than what the files say — nothing
at all, one of two migrations sharing an id, or the second of two unnamed ones twice —
recording what it did under the wrong id, or pass the file over in silence. (Decided with
the user on 2026-09-14/19.) Two SQL files of one name
under two classpath roots are that same case: ragtime groups by each file's whole address,
so they arrive as two migrations under one id. The host's
run records what it applied in `ragtime_migrations`, ragtime's own default, so a host
that already used ragtime keeps its history. (Decided with the user on 2026-09-13.)

**Finding zero migrations under a prefix the host named is an error, not success.**
An empty or misspelt prefix is the schema one deploy behind, arriving silently and
reporting itself later as a bug somewhere else. A run that found nothing must say so
loudly, the same way a test run that ran no tests is a red. **Zero *pending* is not that
case**: a second boot finds every migration already recorded, applies none, and succeeds
with `:migrations-applied 0`, which is what §6 says zero means. The count that must not be
zero is the count found, and checking it on every boot is what catches a prefix
misspelt after the first one.

**What a failure at migration N leaves behind.** Without this the section cannot be
tested at all: any assertion would be inventing the bound it claims to check. The floor,
and only the floor, because it is the part that holds on every engine — several do not
run DDL inside a transaction, so batch atomicity is a gift from the engine and never the
contract: **everything before N is applied and recorded, N is not recorded, `start`
throws naming N, and the next boot attempts N again.** A migration that half-applied on
an engine without transactional DDL is a repair the host performs, not a state this
library pretends to unwind.

**An applied migration the source no longer has, or a new one that sorts before the last
one applied, stops the boot**, naming it: the first is a history this code does not
know, the second a change the database would receive out of order. The check compares
sets and the source's order, never the order the control table returns, because ragtime
orders applied ids by a timestamp in milliseconds: measured, H2 recorded two or three
migrations within one millisecond in 43 runs of 50, and when such ties come back in
another order, ragtime's own check reports a conflict that is not there, on both test
engines. The timestamp is also written in the JVM's own time zone and carries none, so a
clock change or two instances in different zones can order applied migrations backwards
with no tie at all. (Decided with the user on 2026-09-14.)

**The migration library underneath is ragtime**, chosen by measurement rather than
convention, with the measured reasons in `CLAUDE.md`. Two requirements constrained the
choice, and both come from rules already written down. It must support **two independent runs, with
different control tables and different sources** — §8 explains why there are two. And
the run must sit under a lock with a **bounded wait**: two instances booting at once is
the ordinary case, and a lock without a deadline turns it into a hang.

**The lock is this library's, because the library underneath has none**: measured, two
concurrent runs applied the same data migration twice, six times out of six. It is one
row in `db_base_migration_lock` — `id VARCHAR(64)` primary key naming the control table,
`holder VARCHAR(36)`, `acquired_at BIGINT` in epoch milliseconds — taken with an `INSERT`
and given back with a `DELETE` of that holder's row, in ANSI SQL, with no transaction held
open. A boot takes it only when the control table shows something pending — read with a
`SELECT` of this library's, because ragtime's own read creates the table, which must
happen under the lock — and the run reads the control table again under the lock before
it applies anything. A control table that is not there, or that this `SELECT` cannot read
at all, is not an answer: such a boot takes the lock and lets the run decide. A history
that disagrees with the source is refused from that same read, **before** the lock, so a
boot with nothing to apply reports the disagreement rather than a lock it never needed.
A lock that cannot be given back afterwards fails the boot like anything else, with the
migrations already applied and recorded. If the row survived whatever stopped the
`DELETE` it stays, and because a boot with nothing pending never asks for the lock, the
restarts in between neither see it nor report it: the first boot that does have a
migration to run is the one that names it. **The wait is
`[:migrations :lock-wait-ms]`**, required and not defaulted, because waiting for another
instance to finish migrating is not waiting for a connection: a boot that cannot take the
lock within it fails naming the holder id it recorded — an identifier for that row, not
for a process an operator can find — and when it took the lock. On an engine that gives
one writer the whole file, the wait can stretch by the driver's own busy timeout and end
in taking the lock rather than failing — measured on SQLite, a one-second wait against a
holder inside a write transaction ended 1.75 to 1.77 seconds late, ten times out of ten,
with the lock taken and nothing left to apply — and never in a second apply.

**What a crash leaves.** The row has no expiry, on purpose. An expiring lease hands a
migration still running to a second instance, which applies it again: measured ten times
out of ten on both test engines, with a one-second lease and a three-second migration. So
a process killed while it holds the lock leaves the row, and every later boot fails loudly
within its wait, naming that holder, until an operator who knows the process is dead
deletes it: `DELETE FROM db_base_migration_lock WHERE id = 'ragtime_migrations'` for the
host's run, with §8's control table named instead for the library's. It is
the repair this section already gives a migration that half-applied, which such a crash
may also have left. A boot with nothing pending never takes the lock, so a crash during
an ordinary restart leaves nothing behind. The row lock held by an open transaction,
which heals itself, was measured too and cannot work here: on SQLite it blocks the
migrations it protects — every write on another connection failed at the driver's
three-second busy timeout — and on H2 the first DDL statement commits it away.
(Decided with the user on 2026-09-14, from these measurements.)

**The lock table is created without `IF NOT EXISTS`**, which Derby rejects: a probe, the
`CREATE`, and a second probe when the `CREATE` fails, which absorbed two concurrent
creators 200 times out of 200, and two cold boots 50 times out of 50, on both test
engines. Its columns can never change, because it
exists before any migration could record a version of it: a different shape would be a
different name.

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
| Migrations run at boot or the process does not serve; zero found is an error | §7 |
| The pool is HikariCP; migrations run on ragtime, under a lock of this library's own | §6, §7 |
| A stored session store ships here, with its own table and its own control table | §8 |
| The store never upserts, and a zero-row update is correct | §8 |
| §3 is proven against two engines, not by review: H2 strict beside SQLite permissive, with which engine refuses which form pinned | §3 |
| The host's engine is PostgreSQL, chosen from three that were all run; the library assumes none of them, and the test pair is deliberately not it | §3 |
| Integrant is used, not imposed: an optional namespace, one key | §10 |
| A readiness check belongs here, and its timeout has no default | §6 |

**Open**

- **Reclaiming expired rows**: on write, or by an operator calling a function this
  library exposes. Not on a timer — §9. This is only about space: an expired session is
  already invisible, because §8 puts the expiry in the read, and that part is settled.

**The residue is measured, and what is left of it is a rule rather than a question.**

Whether `UUID/randomUUID` is safe under a native image: **it is, where this library uses
it, and the rule that keeps it so is that a minted value never becomes a var root.**
Measured 2026-09-20 on GraalVM CE 25.3.4.1. In an image built the ordinary way every UUID
differs from run to run, including one minted while a class initialises. Build that same
class with `--initialize-at-build-time` — which is how a Clojure image is built — and the
value minted during initialisation is **baked into the binary**: identical in every run of
every instance, with no error and no warning from the builder. Values minted at run time
stay fresh even there, so the generator is not the hazard; the load-time `def` is. This
library mints exactly one UUID, the lock holder of §7, inside a function that runs per
boot, **and a scan beside §9's keeps it that way**: no var root of src may reach a UUID, a
generator, or a string carrying a UUID's spelling. §9's own scan cannot answer this — it
hunts state, not constants — so the two run side by side over one walk. What neither sees
is a baked value shaped like any other: a clock reading, an identity hash, a pid, which
the scan's own docstring says out loud. Asking the image builder to initialise this
namespace at run time instead is not a fix: it builds, and the binary then dies on
startup, because Clojure's runtime initialisation goes looking for `clojure/core/server.clj`
on a classpath a native image does not have (measured the same day). The JDK's own
generator sits in `java.util.UUID$Holder`, a `static final SecureRandom` that `java.base`
does not open, so nothing of ours could reach it in any case.

The driver question is closed too. Whether a real network driver honours the `isValid`
timeout against a socket that stays open and answers nothing: **pgjdbc 42.7.13 does**.
Against PostgreSQL 18.6 through a proxy told to go quiet — both sockets open, every byte
dropped — `ready?` with a two-second timeout answered false in 2005 ms and then in
1003 ms, a fresh boot failed with this library's own message in 2005 ms for a
`[:pool :timeout-ms]` of 1000, and a socket that accepts and never speaks at all failed
the same way in 2008 ms (measured 2026-09-20). H2 over TCP, the same shape, was still
blocked after twelve seconds (§6), so that trap belongs to that engine and not to the
JDBC world: it is why the suite's H2 tests stop the server rather than silence it.

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
