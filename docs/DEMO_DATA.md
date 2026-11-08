# Prepare the frontend team's demonstration school

The role screens do not need to be finished. This local operator helper uses the real gateway APIs to activate test accounts and create fictional records. It uses no direct SQL, does not run the destructive integration suite and does not call Paystack, SMTP or Expo providers.

## Before running

Use Node.js 24 and the expanded backend branch. Start the six containers and run the normal bootstrap command from SETUP.md first. This helper accepts only the saved school with code `DEMO` and name `EduCore Presentation School`. The matching private `bootstrap-state.json`, `principal-access.json` and original backend `.env` must remain on the operator's computer.

```powershell
node scripts/seed-demo.mjs --demo
```

This activates the pending first Principal with a generated password, saved privately. It also creates a separate Principal account for the administrator-interface developer. An already active account with a different password is not reset: the command stops for local review.

After success, open the credentials locally:

```powershell
notepad .dev/demo-credentials.json
```

| Entry | Handover |
| --- | --- |
| `lead` | School lead's Principal account; retain privately |
| `admin` | Administrator frontend developer's separate Principal account |
| `teacher` | English teacher, class teacher for JSS1A and SSS1A |
| `mathTeacher` | Second subject teacher; class teacher for JSS1B |
| `student` | Zainab Demo's student account |
| `parent` | Ada Demo's parent account, linked to Zainab and Mariam |

Each entry has a separately generated password and actual backend-issued login ID. Give a teammate only their own entry, plus the school ID and reachable API base URL. Recovery codes are private to the account owner. If a password-setup response was lost after the server accepted it, sign-in can still resume using the saved password; an unretrieved recovery code must be rotated through Settings. No universal demo password is installed.

Keep both private demo files and the bootstrap files with this database volume; they are ignored by Git. On Windows keep the project folder private to your Windows user. Do not upload these files, put credentials in frontend source, or share the lead account with the group.

## Records created

- One active demonstration session, three non-overlapping terms, JSS1A, JSS1B and SSS1A.
- English, Mathematics, Biology and Civic Education in the subject catalogue. Only English and Mathematics have actual teaching assignments and sample results.
- Two teachers with explicit subject/level eligibility and six actual class-subject assignments; separate class-teacher duties.
- Four fictional students and three guardians. Ada is linked to children in both JSS1 arms. The SSS1 pupil has the Science department.
- One published weekly timetable with six Monday lessons, preserving the school's break and activity rules. It is a deliberately small sample, not a complete Nigerian subject curriculum.
- One submitted JSS1A attendance register with one absence and one present pupil, plus an office-reviewed absence alert. Other unsubmitted days remain unrecorded.
- One published English assignment.
- Four NGN invoices: unpaid, partly paid and fully paid examples. Cash records explicitly say they are simulated; no funds are received.
- One JSS1A assessment cycle with policy approval, two subject-teacher submissions, class-teacher remarks/review and two published report cards. Java calculates the scores and attendance coverage.
- One published notice and one parent fees complaint.

Dates are illustrative, generated around the Lagos date of the first seed and retained in retry state. They are not an official federal/state school calendar. Only one term has sample results; this does not invent annual promotion/graduation evidence. Additional pupils/guardians are provisioned pending activation; the six handover accounts above are activated.

## Retry behaviour

Rerun the same command after resolving an error. Accepted operations are retained; an uncertain request repeats its original idempotency key, body and version. Saved completed operations do not overwrite later frontend edits. Keep `demo-seed-state.json`; deleting it loses the retry references. Existing demo session records without that state cause a stop instead of a duplicate seed.

Only one seed process may run at a time. Normal errors release `demo-seed.lock`. If Windows or Node terminates abruptly, first confirm the old seed command has stopped, then remove only the stale lock:

```powershell
Remove-Item .dev/demo-seed.lock
node scripts/seed-demo.mjs --demo
```

Do not delete state, regenerate database passwords, drop databases or remove the database volume to retry. A stale-version or changed-credential failure requires review. Pending-account activation proofs can be reissued through the existing local operator/admin boundaries; active passwords are not reset.

## Connect the team

The account records live in the PostgreSQL volume on the computer where this runs. Credentials from that database do not automatically exist in a separately hosted database.

For a developer running the web frontend on the same laptop:

```dotenv
EXPO_PUBLIC_DEMO_MODE=false
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
```

For remote team testing with no hosting budget, the proposed temporary connection is a Cloudflare Quick Tunnel to the gateway:

```powershell
cloudflared tunnel --url http://localhost:8080
```

Use the generated HTTPS origin plus `/api/v1` in the frontend `.env`, then restart Expo. The URL is generated by Cloudflare; no permanent URL has been reserved or deployed. The laptop, Docker services, internet connection and tunnel process must stay running. The hostname changes on a new Quick Tunnel. Only the gateway is published; PostgreSQL and the operator port remain private. Use fictional data.

The normal shared API client remains in use. For local browser development, the backend allows `http://localhost:8081` and `http://localhost:4173`. If a frontend runs on another origin, the lead must explicitly add that origin in `EDUCORE_CORS_ORIGINS` and recreate the gateway. A tunnel's HTTPS address is the API destination, not the developer's browser origin.

A permanent hosted environment is a separate step. The proposed Render topology is a public HTTPS gateway, four private Java services and PostgreSQL with each service's own database/login, all in one private region/workspace. Private services require paid compute. Render's free web instances cannot receive private-network traffic and its free Postgres expires after 30 days. Do not present five public free services or a temporary tunnel as the final permanent hosting arrangement. No paid resources are created by this helper.

When a permanent staging database is introduced, populate it deliberately and issue the accounts for that environment. Do not promise that local credentials transfer without the matching account data and protected service configuration.

References checked 8 November 2026:

- [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)
- [Render free-service limits](https://render.com/docs/free)
- [Render compute plans](https://render.com/docs/compute-plans)

## Verification

[Completed backend run 42470308154](https://github.com/ayodamolami4-bot/educore-backend/actions/runs/42470308154) verifies the helper at source `b4b805054f5515c46bcdc4a644ea92bf87623834`, including the full PostgreSQL/container check described below. It also passes the ten Node helper checks on Linux and Windows. This is disposable test evidence; the operator must still run the command on the intended local demo database.

`node --test scripts/demo-seed.test.mjs` checks unknown-result replay, unchanged original body/version, scope restrictions, loopback-only destinations, redirect refusal, private credential output, mutual exclusion and Lagos dates. These are helper tests, not a live-school test.

The Backend checks workflow also runs `scripts/demo-seed-check.mjs` against the disposable PostgreSQL containers. It seeds twice, checks that the second run performs no school-record mutations, signs in all six accounts and exercises family/teacher/admin reads, parent switching, published reports, invoice balances and role denials. The check requires `EDUCORE_DEMO_CHECK=true` and creates a separate test school. Do not run that CI-only check on the lead's database.
