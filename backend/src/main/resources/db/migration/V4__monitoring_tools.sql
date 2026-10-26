-- Registered monitoring tools and their token hashes (D31, D32, US-02).
-- Names are unqualified; Flyway runs with search_path = incident (D26).

CREATE TABLE monitoring_tool (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       varchar(100) NOT NULL,
    token_hash varchar(64)  NOT NULL,
    created_at timestamptz  NOT NULL,
    revoked_at timestamptz
);

-- Tool names are unique regardless of case: "Prometheus" and "prometheus" are the same tool.
CREATE UNIQUE INDEX monitoring_tool_name_key ON monitoring_tool (lower(name));

-- Token lookup is by SHA-256 hash.
CREATE UNIQUE INDEX monitoring_tool_token_hash_idx ON monitoring_tool (token_hash);
