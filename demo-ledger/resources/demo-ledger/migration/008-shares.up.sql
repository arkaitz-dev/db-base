-- What each member owes of one expense. Written in the same transaction as the
-- expense, and the shares of an expense always add up to its amount.
CREATE TABLE expense_share (expense_id VARCHAR(36) NOT NULL REFERENCES expense (id) ON DELETE CASCADE,
                            subject VARCHAR(36) NOT NULL REFERENCES account (subject),
                            share_cents BIGINT NOT NULL CHECK (share_cents >= 0),
                            PRIMARY KEY (expense_id, subject))
