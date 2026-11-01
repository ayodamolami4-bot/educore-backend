#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
 --set=identity_password="$IDENTITY_DATABASE_PASSWORD" \
 --set=academics_password="$ACADEMICS_DATABASE_PASSWORD" \
 --set=finance_password="$FINANCE_DATABASE_PASSWORD" \
 --set=communication_password="$COMMUNICATION_DATABASE_PASSWORD" <<'SQL'
CREATE ROLE educore_identity LOGIN PASSWORD :'identity_password';
CREATE ROLE educore_academics LOGIN PASSWORD :'academics_password';
CREATE ROLE educore_finance LOGIN PASSWORD :'finance_password';
CREATE ROLE educore_communication LOGIN PASSWORD :'communication_password';
CREATE DATABASE educore_identity OWNER educore_identity;
CREATE DATABASE educore_academics OWNER educore_academics;
CREATE DATABASE educore_finance OWNER educore_finance;
CREATE DATABASE educore_communication OWNER educore_communication;
REVOKE CONNECT ON DATABASE educore_identity FROM PUBLIC;
REVOKE CONNECT ON DATABASE educore_academics FROM PUBLIC;
REVOKE CONNECT ON DATABASE educore_finance FROM PUBLIC;
REVOKE CONNECT ON DATABASE educore_communication FROM PUBLIC;
SQL
