ALTER TABLE private_mail ADD COLUMN expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes';
UPDATE private_mail SET expires_at=created_at+interval '15 minutes';
CREATE INDEX private_mail_due ON private_mail(next_attempt_at) WHERE state IN ('QUEUED','WAITING_CONFIGURATION','SENDING');
CREATE INDEX push_delivery_due ON push_deliveries(next_attempt_at) WHERE state IN ('QUEUED','WAITING_CONFIGURATION','SENDING','AWAITING_RECEIPT');
