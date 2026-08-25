CREATE TABLE admin_impersonation_sessions
(
    id             UUID PRIMARY KEY,
    admin_id       BIGINT                   NOT NULL,
    target_type    VARCHAR(40)              NOT NULL,
    target_id      BIGINT                   NOT NULL,
    target_label   VARCHAR(500)             NOT NULL,
    reason         VARCHAR(500)             NOT NULL,
    source_address VARCHAR(128),
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_admin_impersonation_admin
        FOREIGN KEY (admin_id) REFERENCES admin_accounts (id),
    CONSTRAINT ck_admin_impersonation_target
        CHECK (target_type IN ('USER', 'STUDIO', 'GALLERY'))
);

CREATE INDEX idx_admin_impersonation_active
    ON admin_impersonation_sessions (admin_id, expires_at)
    WHERE ended_at IS NULL;
