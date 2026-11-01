# EduCore backend

Java 17+, Spring Boot 3.5.16 and PostgreSQL 17. This repository is the first backend implementation for the secondary-school EduCore app. The separate React Native app uses Model–View–Presenter; these are backend microservices.

| Program | Responsibility | Local port |
| --- | --- | --- |
| api-gateway | One public `/api/v1` entry; fixed service routing and explicit CORS | 8080 |
| educore-identity | Accounts, activation/recovery, rotating sessions, school/role/child access | 8091 |
| educore-academics | School records, metadata, enrolment, guardians, teachers, timetable and attendance | 8092 |
| educore-finance | Invoices, cash/verified-bank records, Paystack test checkout and verification | 8093 |
| educore-communication | Private notifications, school notices, complaints and configured SMTP mail | 8094 |

Each business service owns a separate SQL database and login. No service queries another service's tables. `platform` is a shared Java library for transport, security, encryption and transaction helpers, not another server.

## Start locally

Install Docker with Compose and Node.js 24. From this folder run:

```sh
node scripts/init-dev.mjs
docker compose --parallel 1 build
docker compose up -d --wait --wait-timeout 180
node scripts/bootstrap.mjs DEMO "EduCore Presentation School" "Demo Principal" "08012345678"
```

The last command saves the principal's private activation instructions to `.dev/principal-access.json`. Use them in the app's activation screen, choose your own password, and save your private recovery code. There is no shared default password. The app's API base is `http://localhost:8080/api/v1` for local web development.

Read [SETUP.md](docs/SETUP.md) for first sign-in, Windows, frontend configuration and troubleshooting.

## Build Java directly

Install Java 17 or a compatible later JDK. The Maven wrapper downloads Maven; a separate Maven installation is not required.

```sh
./mvnw verify
```

On Windows PowerShell use `./mvnw.cmd verify`. Use a **JDK**, which includes the Java compiler. A Java runtime alone cannot compile the code. Docker builds use their own JDK, so a separate local JDK is optional for the Docker startup above.

## Current scope

The current build implements the shared frontend contracts, a complete enrolment-to-account flow, weekly timetable review/publication, submitted attendance/corrections, invoice/payment foundations and private complaint cases. Internal assessment and promotion rules have unit tests; marks entry, report publication/PDFs, promotion execution, assignments/submissions, threads, imports/exports, financial adjustments/refunds and settlement reconciliation still require implementation. Do not treat a tested rule engine as a finished report or promotion workflow.

Payments are deliberately **Paystack test mode only**. A school subaccount must be configured before hosted checkout; a client redirect is never proof of payment. SMTP and native push delivery need deployment configuration and provider/device tests. Device registration and in-app notifications do not mean a push was delivered.

## Checks

`./mvnw spotless:check verify` compiles all programs and runs the unit tests. `scripts/integration.mjs` runs the five programs against four actual PostgreSQL databases, with local HTTP Paystack and SMTP test doubles. It requires a dedicated test database configuration; never point it at a school or another project's database. No live payment or real email delivery is part of the integration test.

The frontend source is at https://github.com/ayodamolami4-bot/educore-frontend. Its shared API contracts are preserved under the gateway `/api/v1` base. Official school permissions, validation and calculation always run on Java.

## Team handoff

- [Working features and remaining work](docs/IMPLEMENTATION_STATUS.md)
- [Implemented endpoints and request examples](docs/API.md)
- [Programs, databases and coordination](docs/ARCHITECTURE.md)
- [Agreed school rules](docs/NIGERIAN_RULES.md)
- [How to contribute](CONTRIBUTING.md)

This source is runnable locally. A public API, production hosting, live payments and app-store releases have not been deployed.
