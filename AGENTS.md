# EduCore backend boundaries

Java Spring Boot microservices with PostgreSQL, behind a single /api/v1 gateway. MVP means Model–View–Presenter in the separate React Native frontend, not a backend architecture.

Each business service owns its SQL database and migrations. Never join another service's tables or share mutable JPA entities. Platform contains transport/security/transaction helpers, not school business entities. Authenticate every protected request and validate current school/role/child permissions server-side. Never trust client-supplied identity headers. Do not log passwords, activation/recovery codes, access/refresh tokens, provider secrets or message bodies.

Read docs/IMPLEMENTATION_STATUS.md before claiming a capability is implemented. The final administrator decisions and current shared frontend wire types override the older proposed blueprint. Keep commands idempotent and versioned, histories append-only and all official calculations on Java. School changes must not reset a global parent's identity.

Run ./mvnw verify, integration tests against actual PostgreSQL and scripts/integration.mjs for cross-service changes. Never report skipped integration tests as passing. No live payment, deployment, invitations or production-data mutation is implied by local tests.
