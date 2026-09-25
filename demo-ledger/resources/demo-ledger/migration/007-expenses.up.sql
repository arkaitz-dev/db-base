-- Money is whole cents in a BIGINT, never a NUMERIC: a NUMERIC comes back as a
-- BigDecimal, and `=` in Clojure does not equate 1000M with 1000.
CREATE TABLE expense (id VARCHAR(36) NOT NULL PRIMARY KEY,
                      group_id VARCHAR(36) NOT NULL REFERENCES ledger_group (id) ON DELETE CASCADE,
                      payer VARCHAR(36) NOT NULL REFERENCES account (subject),
                      description VARCHAR(120) NOT NULL,
                      amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
                      created_at BIGINT NOT NULL)
