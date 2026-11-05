# EduCore backend

Java 17+, Spring Boot 3.5.16 and PostgreSQL 17. This repository is the first backend implementation for the secondary-school EduCore app. The separate React Native app uses Model–View–Presenter; these are backend microservices.

| Program | Responsibility | Local port |
| --- | --- | --- |
| api-gateway | One public `/api/v1` entry; fixed service routing and explicit CORS | 8080 |
| educore-identity | Accounts, activation/recovery, rotating sessions, school/role/child access | 8091 |
| educore-academics | School records, metadata, enrolment, guardians, teachers, timetable, attendance, assessments, reports, promotion and assignments | 8092 |
| educore-finance | Invoices, cash/verified-bank records, Paystack test checkout, adjustments and settlement reconciliation | 8093 |
| educore-communication | Private notifications, school notices, complaints, private threads and configured SMTP/Expo delivery | 8094 |

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

The expanded backend is saved on [backend/complete-workflows](https://github.com/ayodamolami4-bot/educore-backend/tree/backend/complete-workflows). It includes school/staff/student/guardian administration, calendars/curriculum, timetable and examination drafts, attendance, marks/report publication, promotion/graduation, materials, app/physical assignments, verified school documents, fee plans and reconciliation, parent communication/complaints, delivery workers and reviewed imports/exports.

The latest verified code passed **42 Java tests, 10 helper/gate checks, 80 PostgreSQL integration groups, all five Docker builds and two container startup checks**. Setup-file checks also run on Windows. CodeQL gates pass with one explicitly triaged audit-only exception and three medium operator/test data-flow notices retained for review. Core contracts now map 93 pages with one retained help-only recovery-policy boundary. Frontend/provider/device and release work remain; this is not a production-readiness claim. Read [IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) before connecting a screen.

## Frontend handoff

Start with [REVIEW_HANDOVER.md](docs/REVIEW_HANDOVER.md), then [SCREEN_CONNECTIONS.md](docs/SCREEN_CONNECTIONS.md): all 94 agreed pages are mapped to real endpoints, with remaining controls labelled. Then read [API.md](docs/API.md). [FRONTEND_ENDPOINTS.md](docs/FRONTEND_ENDPOINTS.md) lists all **285 implemented gateway operations** and **126 request record types**. There is also an [OpenAPI request catalogue](docs/openapi.json) and a [filterable endpoint CSV](docs/ENDPOINT_CATALOGUE.csv).

The frontend Model owns API calls and response mapping, the Presenter owns screen state/actions, and the View owns rendering. School/role/child permissions and official calculations are enforced on Java.

The default branch still contains the earlier foundation until the remaining work is reviewed. To inspect the expanded backend after cloning:

```sh
git fetch origin
git checkout backend/complete-workflows
```

## Architecture and validation

Four business services own separate PostgreSQL databases. Enrollment/account provisioning and notifications use encrypted outbox events, receiver deduplication and retries. Financial commands use stable references, row locks, idempotency keys and explicit version checks. Delivery jobs use database leases. Kafka and Kubernetes are not prerequisites for this local development setup.

Run `./mvnw -B spotless:check verify` for formatting and unit checks and `python3 scripts/check-api-catalogue.py` to validate controller/catalogue/page connections. The [Backend checks workflow](.github/workflows/backend.yml) starts real PostgreSQL, runs all five programs and the integration suite, builds Docker images and verifies container startup. Provider HTTP/email/push integrations are tested with local doubles; no live funds or real external deliveries are involved.

[Completion checklist](docs/COMPLETION_CHECKLIST.md) · [Frontend repository](https://github.com/ayodamolami4-bot/educore-frontend)

[Preserved administrator decisions](docs/product/FINAL_ADMIN_SPEC_v1.md) · [School rules](docs/NIGERIAN_RULES.md) · [Architecture and patterns](docs/ARCHITECTURE.md) · [Quality checks and remaining review](docs/QUALITY_REVIEW.md)
