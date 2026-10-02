ALTER TABLE pro_coupons
    ADD COLUMN code_suffix varchar(8) CHECK (code_suffix ~ '^[0-9A-F]{8}$'),
    ADD COLUMN issued_by_admin_id bigint REFERENCES admin_accounts(id) ON DELETE SET NULL,
    ADD COLUMN disabled_at timestamptz,
    ADD CONSTRAINT ck_pro_coupons_disabled_unused CHECK (disabled_at IS NULL OR consumed_at IS NULL);
CREATE INDEX idx_pro_coupons_created_at_id ON pro_coupons(created_at DESC, id DESC);
