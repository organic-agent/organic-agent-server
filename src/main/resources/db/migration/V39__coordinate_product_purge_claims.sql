-- Product S3 purge cannot hold database row locks for the duration of a network call. Persist a
-- short lease instead: admin mutations treat every remaining claim as a hard conflict, while a
-- product retry may take over an expired lease and repeat the idempotent S3 deletion.
CREATE TABLE product_purge_claims
(
    resource_type VARCHAR(20)              NOT NULL,
    resource_id   BIGINT                   NOT NULL,
    claim_token   UUID                     NOT NULL,
    claimed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_until   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (resource_type, resource_id),
    CONSTRAINT ck_product_purge_claim_resource_type
        CHECK (resource_type IN ('GALLERY', 'PHOTO'))
);

CREATE INDEX idx_product_purge_claims_lease_until
    ON product_purge_claims (lease_until);
