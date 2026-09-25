-- The magic link's single-use challenge. The token is the primary key, which is
-- what makes the take atomic: whoever's DELETE reports a row is the one caller
-- who redeemed it, and the engine decides that, not a read.
--
-- VARCHAR(43) is auth-base's `token/length` exactly — 32 bytes in base64 without
-- padding. The token is the secret, so it is never logged and never put in an
-- exception's data anywhere in this host.
--
-- No expiry predicate belongs in any statement against this table: auth-base's
-- port says an expired challenge is consumed by the attempt that found it
-- expired, so expiry is the ceremony's judgement and a `WHERE expires_at > ?`
-- here would quietly end single use.
CREATE TABLE login_challenge (token VARCHAR(43) NOT NULL PRIMARY KEY,
                              identifier VARCHAR(320) NOT NULL,
                              expires_at BIGINT NOT NULL)
