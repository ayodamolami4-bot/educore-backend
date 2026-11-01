CREATE TABLE school_payment_settings(school_id uuid PRIMARY KEY,paystack_subaccount varchar(80) NOT NULL,version bigint NOT NULL DEFAULT 1);
CREATE TABLE invoices (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,student_id uuid NOT NULL,term_id uuid NOT NULL,description varchar(200) NOT NULL,
 lines jsonb NOT NULL,total_minor bigint NOT NULL CHECK(total_minor>0),paid_minor bigint NOT NULL DEFAULT 0,
 currency char(3) NOT NULL DEFAULT 'NGN' CHECK(currency='NGN'),due_on date NOT NULL,partial_allowed boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),CHECK(paid_minor>=0 AND paid_minor<=total_minor),UNIQUE(school_id,id)
);
CREATE TABLE payment_intents (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,invoice_id uuid NOT NULL,actor_id uuid NOT NULL,
 reference varchar(80) NOT NULL UNIQUE,amount_minor bigint NOT NULL CHECK(amount_minor>0),email_cipher text NOT NULL,
 state varchar(32) NOT NULL DEFAULT 'PENDING',authorization_url text,attempts integer NOT NULL DEFAULT 0,
 last_verified_at timestamptz,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,invoice_id) REFERENCES invoices(school_id,id)
);
CREATE TABLE payments (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,invoice_id uuid NOT NULL,source varchar(12) NOT NULL CHECK(source IN ('PAYSTACK','CASH','BANK')),
 external_reference varchar(100) NOT NULL,amount_minor bigint NOT NULL CHECK(amount_minor>0),allocated_minor bigint NOT NULL CHECK(allocated_minor>=0),
 verification_reference varchar(240) NOT NULL,recorded_by uuid,confirmed_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(school_id,invoice_id) REFERENCES invoices(school_id,id),CHECK(allocated_minor<=amount_minor),UNIQUE(school_id,source,external_reference)
);
CREATE UNIQUE INDEX provider_reference_once ON payments(external_reference) WHERE source='PAYSTACK';
CREATE TABLE finance_exceptions(id uuid PRIMARY KEY,school_id uuid NOT NULL,reference varchar(100) NOT NULL,code varchar(80) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(reference,code));
CREATE TABLE provider_events(event_hash char(64) PRIMARY KEY,reference varchar(80),event_kind varchar(80) NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
