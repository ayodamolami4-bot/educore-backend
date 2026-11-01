-- Mail can be disabled while the rest of Communication remains available.
-- Preserve existing rows and the already-applied V2 migration checksum.
ALTER TABLE private_mail ALTER COLUMN state TYPE varchar(32);
