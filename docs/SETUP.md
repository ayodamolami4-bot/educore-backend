# Run EduCore on your computer

## Demo sign-in or a real admin account

For the separate frontend's default demo mode, press **Fill demo sign-in details** and **Sign in**, then choose the **Principal** context. The fictional login is `EC-DEMO-001` and its password is `EduCore learning demo 2026!`. This uses the frontend's in-memory demo transport and does not create a backend account or call its business APIs. At the 4 November 2026 handoff, the role modules on frontend main are placeholders until their developers connect their screens; the shared sign-in, context, notifications and settings screens are implemented.

For a real local development admin, follow steps 1–4 below. The bootstrap command creates the school's first **Principal**, writes a private one-time activation slip, and lets you choose your own password. There is no public form that allows someone to grant themselves school-admin access, and no reusable default backend password. A hosted EduCore API and a complete connected role interface have not been demonstrated.

## 1. Tools

Install Git, Node.js 24 and Docker with the Compose plugin. Start Docker before continuing. The commands below work from the backend folder in a terminal or PowerShell. Building Java inside Docker does not need a separate JDK installation. To work on Java outside Docker, install a Java 17+ **JDK**.

Clone the backend into its own working folder:

```sh
git clone --branch backend/complete-workflows https://github.com/ayodamolami4-bot/educore-backend.git
cd educore-backend
```

Keep your React Native frontend in its separate folder. This command selects the expanded implementation branch. The default main branch still contains the earlier foundation until the remaining work is reviewed. Give contributors their own GitHub access when they need to push changes.

## 2. Generate your local configuration

```sh
node scripts/init-dev.mjs
```

This creates a private `.env` with a different password for each database, keys for communication between programs, and keys for encrypting stored private commands. It also creates `.dev/local-runtime.json` for isolated Java integration tests. Neither file belongs in Git. Do not copy `.env.example` to `.env`; the example only documents optional provider settings.

Keep this configuration with your local database volume. Running the command again preserves an existing `.env`. Changing a database password in `.env` after first startup does not automatically change the password stored inside PostgreSQL. Do not delete or regenerate the configuration to solve a connection error.

## 3. Build and start

```sh
docker compose --parallel 1 build
docker compose up -d --wait --wait-timeout 180
docker compose ps
```

The first build downloads Java dependencies and container images, so it takes longer. Serial builds reduce the memory needed. There are six containers: PostgreSQL and five Java programs. PostgreSQL holds four separate application databases. Flyway creates and versions each service's tables at startup.

The public development API is `http://localhost:8080/api/v1`. Health is `http://localhost:8080/actuator/health`. Gateway health checks the gateway itself; `docker compose ps` also shows the individual service and database health.

The operator-only school setup port is bound to your computer at `127.0.0.1:8092`; it is not a frontend API. Other business services are reachable only inside the Compose network. PostgreSQL's development port is also bound to `127.0.0.1`.

If port 5432 is already in use, add `POSTGRES_PORT=55432` to `.env` before startup. If port 8080 is occupied, add `GATEWAY_PORT=8085` and use that port in the frontend. Do not expose the PostgreSQL or operator port publicly.

## 4. Create the first school and principal

```sh
node scripts/bootstrap.mjs DEMO "EduCore Presentation School" "Demo Principal" "08012345678"
```

These are fictional presentation details; replace them for your own development school. The command talks to Academics using the operator key, creates the school, waits for Identity to create its principal account, then saves `.dev/principal-access.json`. Open that private file locally to see `schoolId`, `loginId`, the one-time `activationCode` and expiry. Do not send this file to GitHub or put it in screenshots.

In the frontend, open **Activate account**, enter that login ID and activation code, choose a private password of 15–128 characters, and save the new recovery code privately. Then sign in. The initial school activation code expires after 48 hours. A reviewed password flow expires after 10 minutes. The app needs the resulting school/role context before accessing school data.

The bootstrap command saves retry state. If a request times out, repeat the same command; it reuses the original school and command references. It cannot reset an active principal's password. It is a development/operator tool, not a public registration screen.

## 5. Connect React Native

In your **separate frontend** `.env`, set:

```dotenv
EXPO_PUBLIC_DEMO_MODE=false
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
```

Restart Expo after editing configuration. For the Android emulator use `http://10.0.2.2:8080/api/v1`. The current frontend allows HTTP only for local development hosts. A physical phone using a LAN address needs an HTTPS development endpoint accepted by the frontend; phone hosting and universal/app links are not configured by this backend startup.

Every screen calls the same gateway base. The frontend must never receive database passwords, Paystack secret keys or service-to-service keys. The frontend's Models send API requests, Presenters manage screen state, and Views display forms and results.

For browser development, CORS defaults to `http://localhost:8081` and `http://localhost:4173`. If Expo uses another web port, update `EDUCORE_CORS_ORIGINS` in backend `.env`, then recreate the gateway with `docker compose up -d gateway`. List full origins separated by commas.

## 6. Fill a presentation school

Use the implemented APIs in [API.md](API.md), in this order:

1. Sign in and select the principal school context.
2. Create the academic session, its terms, class arms and subjects.
3. Register a teacher with eligible subjects/levels and actual class subject assignments.
4. Assign the class attendance teacher.
5. Read metadata and enrol a child with a verified guardian relationship.
6. Request separate activation slips for the pending student and guardian accounts. Provisioning may still be queued just after enrolment; retry a `404` after a short delay.
7. Build the weekly timetable, review every assignment target, then publish it.
8. Activate the teacher and parent accounts to test their permitted attendance, child, timetable, invoice and complaint views.

Creating a class/teacher/student record does not mean its role screen has already been implemented by the frontend team. Principal can register School Office/Accounts staff through `POST /staff`, issue their `STAFF` activation slips, then sign in under each staff preset. The principal must retain another activated principal before removing their own school access. Use [SCREEN_CONNECTIONS.md](SCREEN_CONNECTIONS.md) for covered pages and remaining controls.

## 7. Payments and email

Local operation does not need a payment account or an email provider. Manual cash and independently verified bank entries work without Paystack. They require an explanation and a unique reference; the bank verification reference records evidence checked by staff, not an automatic bank connection.

Hosted checkout is **test mode only**. Configure a Paystack `sk_test_` secret in backend `.env` and configure the school's approved test subaccount through the principal payment-settings endpoint. Recreate Finance after configuration changes. The app opens the returned hosted checkout URL and polls the payment intent; a browser redirect is not a paid receipt. A reachable HTTPS webhook URL is needed for external webhook testing. Local automated tests use a fake provider and do not contact Paystack.

Email stays disabled until a provider is configured. Add the optional SMTP variables listed in `.env.example`, set `EDUCORE_EMAIL_ENABLED=true`, and recreate Communication. Remote SMTP requires TLS. The sender mailbox/domain must be authorised by your provider. Without configuration, mail remains `WAITING_CONFIGURATION`; the app must not claim it was delivered. The configured Expo push worker is implemented and tested with a local provider double. Actual native-device delivery, Expo credentials and app links still need separate validation; an Expo provider receipt does not prove the person read the message.

## 8. Stop, update and diagnose

```sh
docker compose down
```

This stops containers and preserves the named database volume. After pulling a code update, rebuild and start again. Applied Flyway migration files must stay unchanged; add a new migration for later schema changes.

```sh
docker compose logs --tail 80 academics
docker compose logs --tail 80 identity
docker compose ps
```

Share only reviewed, redacted logs. Do not share `.env`, private access files or activation/recovery codes. If a business service is unhealthy, check its database configuration and migration errors before restarting repeatedly.

## 9. Java tests

```sh
./mvnw spotless:check verify
```

PowerShell: `./mvnw.cmd spotless:check verify`. To format changed Java: `./mvnw spotless:apply`.

The GitHub workflow creates fresh PostgreSQL test databases and runs `scripts/integration.mjs` with local Paystack/SMTP test doubles. It then builds all Docker images, starts Compose, bootstraps a fictional school and checks sign-in and unconfigured mail. Those tests create fictional records and change credentials/settings. Run them only with a dedicated test configuration, never against your development school or an actual school's database. `scripts/container-check.mjs` is a disposable CI-only check, not a development-school setup command. Do not run `scripts/init-databases.mjs` against an existing database; it is a fresh-database CI helper.

No cloud API is deployed by these steps. Moving to hosting requires private networking, HTTPS, durable secret management, backups/restores, provider configuration, access controls and the release validation listed in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).
