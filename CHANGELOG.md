# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking**, says what a host must change, and the README says "since" beside
the behaviour. Every release is on Clojars as `dev.arkaitz/db-base` and tagged `vX.Y.Z`.
A change to what this library ships in `src` is a release; one to its README, its
hosts or its tests is not.

## 0.2.2 — 2026-09-28

- `dev.arkaitz.db-base.native` resolves a directory inside a GraalVM native image by
  its last segment: GraalVM CE 25.0.4 doubled 0.2.1's module prefix, and a native
  binary could not list its migrations.

## 0.2.1 — 2026-09-28

- `dev.arkaitz.db-base.native`: a migration prefix can be listed inside a GraalVM native
  image.
- The session store's refusal of an over-long session names `[:sessions :dialect]`, the
  setting that would lift the bound, as its `:config-key`.

## 0.2.0 — 2026-09-28

- A session store built over a database without its table is refused when built,
  naming `[:sessions]`, instead of failing on every request.
- `testing/sessions` and `testing/session` read §8's table for a host's tests.

## 0.1.0 — 2026-09-26

- First release: `start`, `stop`, `ready?`, `pool-stats`; migrations under a lock of
  their own before anything serves; §8's session store; the Integrant key;
  `collision/arbitrate!`; the `testing` namespace.
