ALTER TABLE school_payment_settings ADD COLUMN checkout_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE school_payment_settings ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE school_payment_settings ADD COLUMN updated_by uuid;
CREATE TABLE school_settlement_accounts (
 paystack_subaccount varchar(80) PRIMARY KEY,school_id uuid NOT NULL,first_recorded_at timestamptz NOT NULL DEFAULT now(),UNIQUE(school_id,paystack_subaccount)
);
INSERT INTO school_settlement_accounts(paystack_subaccount,school_id) SELECT paystack_subaccount,school_id FROM school_payment_settings;
ALTER TABLE school_payment_settings ADD FOREIGN KEY(school_id,paystack_subaccount) REFERENCES school_settlement_accounts(school_id,paystack_subaccount);
CREATE TABLE payment_setting_revisions (
 school_id uuid NOT NULL,version bigint NOT NULL,paystack_subaccount varchar(80) NOT NULL,checkout_enabled boolean NOT NULL,
 actor_id uuid,reason varchar(500) NOT NULL,source varchar(24) NOT NULL CHECK(source IN ('COMMAND','MIGRATION_BASELINE')),
 recorded_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(school_id,version),FOREIGN KEY(school_id,paystack_subaccount) REFERENCES school_settlement_accounts(school_id,paystack_subaccount)
);
INSERT INTO payment_setting_revisions(school_id,version,paystack_subaccount,checkout_enabled,reason,source)
 SELECT school_id,version,paystack_subaccount,checkout_enabled,'Configuration observed at migration; earlier actor and change time are not reconstructed.','MIGRATION_BASELINE' FROM school_payment_settings;
ALTER TABLE payment_intents ADD COLUMN settlement_subaccount varchar(80);
ALTER TABLE payment_intents ADD COLUMN settlement_settings_version bigint;
ALTER TABLE payment_intents ADD COLUMN payer_snapshot jsonb;
ALTER TABLE payment_intents ADD FOREIGN KEY(school_id,settlement_subaccount) REFERENCES school_settlement_accounts(school_id,paystack_subaccount);
ALTER TABLE payment_intents ADD FOREIGN KEY(school_id,settlement_settings_version) REFERENCES payment_setting_revisions(school_id,version);
CREATE INDEX checkout_workspace_by_school_date ON payment_intents(school_id,created_at DESC,id DESC);
CREATE INDEX invoice_fee_status_by_pupil ON invoices(school_id,student_id,term_id,id);
