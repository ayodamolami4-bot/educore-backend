ALTER TABLE invoices ADD COLUMN credit_minor bigint NOT NULL DEFAULT 0 CHECK(credit_minor>=0 AND credit_minor<=total_minor);
ALTER TABLE invoices ADD COLUMN refunded_minor bigint NOT NULL DEFAULT 0 CHECK(refunded_minor>=0 AND refunded_minor<=paid_minor);
ALTER TABLE invoices DROP CONSTRAINT invoices_check;
ALTER TABLE invoices ADD CONSTRAINT invoice_positive_gross_paid CHECK(paid_minor>=0);
CREATE TABLE fee_plans (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,term_id uuid NOT NULL,name varchar(120) NOT NULL,
 class_ids jsonb NOT NULL,lines jsonb NOT NULL,due_on date NOT NULL,partial_allowed boolean NOT NULL DEFAULT true,
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','ARCHIVED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL,published_by uuid,published_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(school_id,id)
);
CREATE TABLE fee_batches (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,plan_id uuid NOT NULL,roster jsonb NOT NULL,source_signature char(64) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PREVIEW' CHECK(state IN ('PREVIEW','CONFIRMED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),confirmed_at timestamptz,
 FOREIGN KEY(school_id,plan_id) REFERENCES fee_plans(school_id,id),UNIQUE(school_id,id)
);
CREATE TABLE fee_plan_issues (
 plan_id uuid NOT NULL REFERENCES fee_plans(id),student_id uuid NOT NULL,term_id uuid NOT NULL,invoice_id uuid NOT NULL REFERENCES invoices(id),
 PRIMARY KEY(plan_id,student_id,term_id),UNIQUE(invoice_id)
);
CREATE TABLE fee_adjustments (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,invoice_id uuid NOT NULL,kind varchar(16) NOT NULL CHECK(kind IN ('CREDIT','DISCOUNT','REFUND')),
 amount_minor bigint NOT NULL CHECK(amount_minor>0),payment_id uuid REFERENCES payments(id),reason varchar(500) NOT NULL,
 evidence_reference varchar(240) NOT NULL,state varchar(32) NOT NULL DEFAULT 'REQUESTED' CHECK(state IN ('REQUESTED','APPROVED','REJECTED','PROCESSING','UNKNOWN','CONFIRMED','FAILED')),
 requested_by uuid NOT NULL,approved_by uuid,approval_reason varchar(500),provider_refund_id varchar(100),provider_reference varchar(100),allocated_refund_minor bigint NOT NULL DEFAULT 0,
 lease_token uuid,lease_until timestamptz,version bigint NOT NULL DEFAULT 1,created_at timestamptz NOT NULL DEFAULT now(),confirmed_at timestamptz,
 FOREIGN KEY(school_id,invoice_id) REFERENCES invoices(school_id,id),UNIQUE(school_id,id)
);
CREATE UNIQUE INDEX confirmed_refund_reference ON fee_adjustments(school_id,provider_reference) WHERE kind='REFUND' AND provider_reference IS NOT NULL;
CREATE TABLE settlement_batches (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,source varchar(16) NOT NULL CHECK(source IN ('PAYSTACK','BANK_STATEMENT')),
 stable_reference varchar(100) NOT NULL,snapshot jsonb NOT NULL,state varchar(24) NOT NULL CHECK(state IN ('MATCHED','REQUIRES_REVIEW','REVIEWED')),
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(school_id,source,stable_reference),UNIQUE(school_id,id)
);
CREATE TABLE settlement_rows (
 id uuid PRIMARY KEY,batch_id uuid NOT NULL REFERENCES settlement_batches(id),reference varchar(100) NOT NULL,payment_id uuid REFERENCES payments(id),
 amount_minor bigint NOT NULL,fee_minor bigint NOT NULL DEFAULT 0,net_minor bigint NOT NULL,
 issue varchar(80),resolution varchar(32),resolution_reason varchar(500),resolved_by uuid,resolved_at timestamptz,version bigint NOT NULL DEFAULT 1,
 UNIQUE(batch_id,reference)
);
ALTER TABLE finance_exceptions ADD COLUMN resolution varchar(32);
ALTER TABLE finance_exceptions ADD COLUMN resolution_reason varchar(500);
ALTER TABLE finance_exceptions ADD COLUMN resolved_by uuid;
ALTER TABLE finance_exceptions ADD COLUMN resolved_at timestamptz;
ALTER TABLE finance_exceptions ADD COLUMN version bigint NOT NULL DEFAULT 1;
