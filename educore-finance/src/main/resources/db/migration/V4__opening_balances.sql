CREATE TABLE opening_balance_batches (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,term_id uuid NOT NULL,payload_cipher text NOT NULL,source_signature char(64) NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'PREVIEW' CHECK(state IN ('PREVIEW','CONFIRMED')),version bigint NOT NULL DEFAULT 1,
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),confirmed_by uuid,confirmed_at timestamptz,UNIQUE(school_id,id)
);
CREATE TABLE opening_balance_entries (
 school_id uuid NOT NULL,row_reference varchar(80) NOT NULL,source_hash char(64) NOT NULL,invoice_id uuid NOT NULL REFERENCES invoices(id),
 evidence_reference varchar(240) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(school_id,row_reference),UNIQUE(invoice_id)
);
