# Working together

Ayo is the lead reviewer. Work in a short-lived branch and open a pull request into `main`; do not push another person's unfinished work or regenerate their files. This backend is a separate working folder from the React Native frontend.

| Work | Usual owner folder |
| --- | --- |
| Authentication and account settings | `educore-identity/` |
| School/teacher/student/timetable/attendance and results | `educore-academics/` |
| Invoices and payment provider | `educore-finance/` |
| Notifications and complaints | `educore-communication/` |
| Shared helpers, gateway, root build, Compose and cross-service contracts | Lead coordinates changes to `platform/`, `api-gateway/`, root and shared documentation |

Roles belong to permissions, not separate backend copies. For example, the teacher and admin attendance screens both call Academics with different allowed actions. Keep database credentials inside their owning program. Never reach into another service's tables to save an API call.

Before implementation, agree the endpoint, request/response fields, allowed roles, validation and loading/empty/error states with the frontend owner. Update [docs/API.md](docs/API.md) and the implementation-status table in the same pull request. A new endpoint must check current school and teaching/child scope, including on an idempotent retry.

Add a new Flyway migration after the highest existing version in that service; do not edit a migration already applied. Use local SQL transactions for one service's changes and an outbox event for reliable work in another service. Financial references and stable IDs must survive retries. Require an expected version where one editor could overwrite another.

Run `./mvnw spotless:apply` after Java changes, then `./mvnw spotless:check verify`. Cross-service changes also need the real PostgreSQL integration run through GitHub Actions or isolated test databases. Test permission failures, duplicate commands and relevant boundary cases, not only the successful path.

Never commit `.env`, `.dev`, real student records, provider keys, access slips or recovery codes. Do not add real recipients or enable live payments in automated tests. Mark a feature complete only when its whole permitted workflow works; a DTO, a database table or a unit-tested formula alone is not a connected page.
