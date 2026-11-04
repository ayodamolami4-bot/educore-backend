# How the backend fits together

Reviewed 4 November 2026. The [final administrator specification](product/FINAL_ADMIN_SPEC_v1.md) records the agreed target. [NIGERIAN_RULES.md](NIGERIAN_RULES.md) records product defaults, and [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) separates tested implementation from remaining work. [QUALITY_REVIEW.md](QUALITY_REVIEW.md) records checks and limits for AI-assisted development.

The React Native app calls one URL. The gateway forwards a request to one of four independently running Java programs. Each business program owns its database. Keeping the source in one repository makes shared reviews and builds easier; it does not combine the programs into one process.

| Folder/program | Owns | Database |
| --- | --- | --- |
| `api-gateway` | Fixed HTTP routes, permitted forwarded headers, CORS and request limits | None |
| `educore-identity` | Global accounts, private proofs, sessions, school memberships and allowed contexts | `educore_identity` |
| `educore-academics` | School/session/class records, enrolment and guardian links, teaching assignments, timetable/exams, attendance, assessments/reports, promotion, learning/files and school rules | `educore_academics` |
| `educore-finance` | Invoices/plans, payment references/intents, allocations, approved adjustments/refunds and statement reconciliation | `educore_finance` |
| `educore-communication` | Notifications/notices, child-linked threads, complaint cases, private mail and configured push delivery | `educore_communication` |
| `platform` | Shared Java transport, security, encryption, error, transaction and event helpers | No separate database; common helper tables are migrated into each owning database |

One PostgreSQL server can hold four databases locally. Each program has its own login and can connect only to its own database. Schools share tenant-scoped tables within each owning service database; this is not one database per school. There are no SQL joins across services. Current school isolation uses authenticated server context, explicit SQL predicates and school-aware constraints; PostgreSQL row-level security is not currently implemented. Internal HTTP supplies a needed fact such as whether a parent still has a valid child relationship. A missing dependency produces a temporary failure rather than accepting a guessed permission.

## Ordinary requests

The app sends a bearer access token. Identity owns an opaque token record and verifies the current session/account/context. The other services call Identity's internal introspection endpoint. The service then checks the selected school, allowed role and relevant child/payer or teaching scope before querying or changing school data. Choosing a context never grants a new role.

Opaque tokens were chosen to make immediate session revocation and current school access straightforward. The present build does not use JWTs. A gateway request cannot supply a trusted account ID, role, school or internal service key through a spoofed identity header.

Public access goes through the gateway. Business `/internal/*` endpoints accept only a configured sender and that specific sender-to-receiver key. For example, Finance's key to ask Academics about a student cannot provision an account in Identity. The operator bootstrap key stays outside the app. Deployment must also isolate internal endpoints with private networking and HTTPS where requests leave a trusted local network.

## Enrolment and delayed coordination

Academics saves the student, dated class placement, guardian link and an outbox event **in one local transaction**. It returns `ACCOUNTS_PENDING`. A worker sends the event to Identity. Identity deduplicates the event with its inbox, then creates or explicitly links permitted accounts. A lost response is retried without creating another account. The pending account can then receive a school-issued activation slip.

An outbox is a saved delivery list: committing the school change also commits the item that must be delivered. It is not a Kafka broker. Inbox records stop a repeated delivery from applying the same work again. A short delivery lease permits safe outbox workers; retries back off and stop after the configured attempt limit. Operational monitoring and manual failed-event repair screens remain to build.

This build uses local database transactions and outbox/inbox coordination. It does not claim a complete multi-step Saga orchestrator. Kafka and Kubernetes are not required for the current runnable project; adding them before measuring a need would add separate systems for the beginner team to operate.

## Preventing duplicate and conflicting changes

| Mechanism | Example in this build |
| --- | --- |
| Idempotency key | Double-tapping enrol reuses one command/result; changing its body with the same key is rejected |
| Permanent unique reference | Re-recording the same bank/provider payment reference cannot credit an invoice again, even with a fresh command key |
| Optimistic version (`If-Match`) | An old attendance editor cannot overwrite a register corrected by somebody else |
| Transaction/row/advisory locks | Concurrent invoice allocations and changes to one timetable plan are serialised |
| SQL constraints | Foreign keys and school-aware relationships prevent orphan records; exclusions block teacher/room overlap |
| Audit records | A reasoned attendance correction appends who changed it and why |
| Stable UUIDs | A student, invoice, event or provider intent keeps the same reference when retried |

## Payment boundaries

A checkout is an intention to pay. Finance creates a stable reference before calling Paystack. A timeout becomes an unknown/pending outcome that can be verified later. A valid webhook signature wakes verification; the provider's server-side response must match reference, amount, NGN currency and test domain before local allocation. A duplicate success does not allocate twice. Overpayments and mismatches are recorded for review.

Verification of one transaction and bank/provider **settlement reconciliation** are different jobs. This build implements both transaction verification and reviewed bank/provider statement reconciliation, including gross/fee/net differences and reasoned exceptions. Approved refunds remain pending until the external bank/provider outcome is confirmed. The API does not automatically send refund money. Provider integration has been exercised with local test doubles, not real funds.

## Metadata and school rules

Metadata describes the supported school form fields; it is not executable JavaScript or a replacement for database constraints. The initial student metadata includes `homeAddress` and `previousSchool`; versioned form updates support up to 20 additional text fields with required/length settings. The frontend reads its version and submits that version with enrolment. An old form is rejected so it can be refreshed. Core identity, class, grading and payment rules remain typed Java/SQL.

Spring JDBC makes the SQL and school filter explicit. Flyway applies reviewed numbered migrations. DTO records define input, service classes own business decisions, and controllers handle HTTP. The frontend's Model–View–Presenter separation stays in the frontend; the backend does not use its Presenters.

## Delivery limits

The outbox/inbox and ledger give exactly-once **local effects** for deduplicated commands/events; external delivery is not magically exactly once. SMTP can accept a message just before a worker loses its response. Private mail, optional email and configured Expo push workers use leases, attempt limits, preference/access checks and expiry. Expo ticket/provider receipt is distinct from an in-app read acknowledgement. Actual external mailbox and native-device delivery have not been demonstrated. Global request throttling, full abuse protection, production backup/restore and independent security review remain required before hosting real school data.

## Pattern and technology boundaries

| Decision or pattern | Current use and reason |
| --- | --- |
| React Native + TypeScript + Expo, frontend MVP | Model calls/maps APIs, Presenter coordinates screen state, View renders. Java remains the backend language. |
| Java 17 + Spring Boot, REST DTO/controller/service layers | Typed inputs and explicit business decisions behind one gateway; Spring JDBC exposes the owning service's SQL. |
| PostgreSQL + Flyway | Relational constraints, transactions and ordered migrations. Applied migrations are immutable; add the next migration. |
| Service-owned databases | Identity, Academics, Finance and Communication run independently and own separate databases/logins. |
| Current role/school/child authorization | A selected role never grants new access; losing a guardian link or teacher duty removes access on later requests. |
| Opaque sessions and protected recovery | Rotating access/refresh tokens, replay revocation, PBKDF2 password hashes, short-lived single-use proofs and recent authentication for sensitive changes. |
| Transactional outbox + deduplicating inbox | An accepted school change durably queues account/notification work; retried events produce one accepted local effect. |
| Idempotency + permanent references | Retries do not create another enrolment or payment; a fresh command key still cannot reuse a permanent bank/provider reference. |
| Transactions + row/advisory locks + optimistic versions | Concurrent updates serialize where needed; stale screens receive a conflict rather than silently overwriting later work. |
| SQL constraints and exclusion rules | School-aware relationships, unique references and schedule exclusions add database checks beneath Java. |
| Draft/review/approval/publication and immutable snapshots | Families receive accepted versions; later drafts/corrections preserve old reports, documents, marks and receipts. |
| Webhook verification and reconciliation | Signed Paystack events trigger authoritative verification; settlement matching separately checks what the provider/bank settled. |
| Bounded timeouts, leased workers and retry backoff | Failed dependencies remain pending/failed truthfully; workers avoid simultaneous ownership and retry later within limits. |
| Versioned metadata | Supported form/settings changes carry versions; metadata cannot execute code or change official historical calculations. |
| Private files, audit history and limits | Permission-checked encrypted files, content/hash/size checks, reasoned audit records and bounded requests/exports. Malware scanning is not integrated. |
| Docker Compose and GitHub Actions | Repeatable local services and checked builds/integration/container startup. This does not deploy a hosted system. |

Kafka, Kubernetes, an AWS deployment, a complete Saga orchestrator and a circuit-breaker implementation are not part of the present build. They were discussed as options; adding any requires a concrete operational need and an explicit decision. Current coordination is the local-transaction/outbox/inbox design above.
