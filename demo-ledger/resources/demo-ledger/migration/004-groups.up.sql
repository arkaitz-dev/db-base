-- A group of people who share expenses. The creator is recorded and is a member like
-- any other: nothing in this host gives the creator powers the others lack.
CREATE TABLE ledger_group (id VARCHAR(36) NOT NULL PRIMARY KEY,
                           name VARCHAR(80) NOT NULL,
                           created_by VARCHAR(36) NOT NULL REFERENCES account (subject),
                           created_at BIGINT NOT NULL)
