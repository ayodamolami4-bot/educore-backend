CREATE TABLE school_invitations (
 id uuid PRIMARY KEY,school_id uuid NOT NULL,subject_type varchar(16) NOT NULL,subject_id uuid NOT NULL,
 account_id uuid NOT NULL REFERENCES accounts(id),proof_id uuid NOT NULL REFERENCES proofs(id),
 state varchar(16) NOT NULL DEFAULT 'ISSUED' CHECK(state IN ('ISSUED','REPLACED','REVOKED')),
 issued_by uuid NOT NULL REFERENCES accounts(id),created_at timestamptz NOT NULL DEFAULT now(),
 ended_at timestamptz,reason varchar(500),version bigint NOT NULL DEFAULT 1,
 UNIQUE(school_id,id)
);
ALTER TABLE proofs ADD COLUMN invitation_id uuid REFERENCES school_invitations(id);
CREATE INDEX school_invitation_subject ON school_invitations(school_id,subject_type,subject_id,id);
CREATE INDEX proof_invitation ON proofs(invitation_id) WHERE invitation_id IS NOT NULL;
