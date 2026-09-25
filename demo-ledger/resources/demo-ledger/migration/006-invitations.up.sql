-- An invitation names an address, not an account: the person may not have signed in
-- yet, and an account is only ever created by redeeming a link. Accepting it turns
-- the address into a membership of whoever that address belongs to by then.
CREATE TABLE invitation (group_id VARCHAR(36) NOT NULL REFERENCES ledger_group (id) ON DELETE CASCADE,
                         identifier VARCHAR(320) NOT NULL,
                         invited_by VARCHAR(36) NOT NULL REFERENCES account (subject),
                         created_at BIGINT NOT NULL,
                         PRIMARY KEY (group_id, identifier))
