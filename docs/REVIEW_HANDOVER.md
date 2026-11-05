# EduCore backend review handover

**Ready for backend code review.** The verified feature checkpoint passed 42 Java tests, 10 helper/gate checks, 80 PostgreSQL scenario groups, all five Docker builds and both startup checks. Use `backend/complete-workflows` and pull request #1. This document separates implemented backend work from account setup, frontend implementation and release validation.

The setup-file checks also pass on Windows. CodeQL gates pass with one narrowly triaged export-audit exception and three medium operator/test data-flow notices; the findings remain visible. Review the rationale and frozen-source checks in [QUALITY_REVIEW.md](QUALITY_REVIEW.md). This is implementation triage, not independent security approval.

## What is being handed over

The agreed school workflows have backend implementations: accounts and school permissions; student/guardian/staff administration; sessions, terms, curriculum, rooms and dated timetables/examinations; attendance and reviewed corrections; assessment review/publication and immutable reports; materials, app/physical assignments and verified documents; fees, test checkout and reconciliation; notices, private conversations, complaints, imports/exports and activity history.

The final controls add provisioning diagnosis/retry and reviewed repair of unapplied account matching, invitation history/revocation, school setup editing, delivery troubleshooting, bounded report previews/downloads and explicit approval of the adopted grading policy. The preserved administrator specification and school decisions remain authoritative.

Read [implementation status](IMPLEMENTATION_STATUS.md) for the exact verified commit/run, [screen connections](SCREEN_CONNECTIONS.md) for each role page, and [API.md](API.md) for permissions, versions and failure states. The endpoint catalogue describes backend coverage; it does not mean the React Native role screens have been built.

## Your first review on your computer

Install Docker Desktop with Compose and Node.js 24. A local JDK is optional for Docker builds. Follow [SETUP.md](SETUP.md), including its Windows instructions.

```sh
git fetch origin
git checkout backend/complete-workflows
node scripts/init-dev.mjs
docker compose --parallel 1 build
docker compose up -d --wait --wait-timeout 180
node scripts/bootstrap.mjs DEMO "EduCore Presentation School" "Demo Principal" "08012345678"
```

The example names/phone are fictional. The last command saves private access details in `.dev/principal-access.json`. Activate that Principal account in the connected app, choose a private password and save the recovery code. Repeat the same bootstrap command after a timeout; its saved retry state prevents a duplicate school. Demo credentials do not sign in to a real backend account.

Do not run the destructive fresh-database CI helpers or integration suite against this review school. They are for disposable test databases. Do not delete Docker database volumes as an update step.

## Manual inputs and who owns them

| Work | Your input | Implementation/support |
| --- | --- | --- |
| First Principal | Run local setup and privately keep activation/password/recovery details. | Backend bootstrap and account flows are implemented; frontend team connects activation/sign-in. |
| Paystack | Create/access your account, stay in Test mode, obtain your test secret and approved test settlement subaccount. Enter the secret privately in backend configuration. | Hosted checkout, verification, signed webhooks, stable references, reconciliation and test-only enforcement are implemented. I can help configure and run the actual provider checks with your account access. |
| Email | Supply an authorized SMTP sender/account and its private settings if enabling email. | Optional email delivery and truthful waiting/failure/retry states are implemented. Actual mailbox delivery needs your chosen account. |
| Phone | Open the frontend on a supported device and exercise sign-in, links and notification permission/delivery. | Frontend team owns its secure token storage, navigation and native configuration; I can troubleshoot the API/provider results. |
| Frontend screens | Your frontend developers build/connect the role screens and use real school-issued accounts. | Backend contracts, permitted roles, errors and screen mapping are documented. Official grades and financial calculations stay on Java. |
| Review/merge | Review the code and functional walkthrough, then decide on merging PR #1. | The review branch preserves `main` until that decision. No production deployment or real-money activation is part of this handover. |

These are account/device/review dependencies. They are not a request for you to finish the remaining backend feature code.

## Paystack setup after code review

1. Use your Paystack **test** account. Obtain the `sk_test_...` secret and the school's approved test `ACCT_...` subaccount. Confirm that the subaccount belongs to the intended school; merely passing a format/configuration check does not verify bank ownership.
2. Set `PAYSTACK_SECRET_KEY` privately in the backend `.env`, then recreate Finance with `docker compose up -d finance`. Never put the secret in React Native, Expo public variables, a commit, screenshots or this handover.
3. Sign in as Principal, select the school context and create the initial setting with `POST /schools/{school}/invoices/payment-settings` and `{"subaccount":"ACCT_YOUR_TEST_SUBACCOUNT"}`. Send a new UUID `Idempotency-Key`. Later edits use the current version/reason; Accounts can read settings/history.
4. Use fictional pupil/invoice data. Initialize checkout from the permitted parent's invoice, open the returned hosted checkout URL and check the intent through its backend verification endpoint. A browser return does not confirm payment.
5. When an HTTPS gateway is available, configure the provider webhook as `https://YOUR_GATEWAY/api/v1/webhooks/paystack` and verify signed delivery. Only the gateway is public; business/internal ports stay private.
6. Exercise a success, a failed/cancelled attempt, a duplicate event and a delayed verification. Check one confirmed allocation/receipt and the correct remaining invoice balance. Provider transaction confirmation and settlement reconciliation are separate checks.

The backend intentionally rejects live Paystack secret keys. Moving to real money requires a separate reviewed release change; a test account is enough for the next integration milestone. Approved refunds are executed through the bank/provider and then confirmed with evidence; the API does not automatically send money.

## Frontend connection contract

The React Native app calls one gateway address. It does not call databases or business services directly. Its Model sends requests, Presenter coordinates state/actions and View renders responses.

```dotenv
EXPO_PUBLIC_DEMO_MODE=false
EXPO_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
```

That address is for local web development. Android emulator uses `http://10.0.2.2:8080/api/v1`. For a physical phone, follow the frontend's accepted HTTPS development/hosting configuration; the phone's `localhost` is not your computer. Restart Expo after changing public configuration.

The current [frontend environment validator](https://github.com/ayodamolami4-bot/educore-frontend/blob/main/src/config/environment.ts) and [shared API client](https://github.com/ayodamolami4-bot/educore-frontend/blob/main/src/api/client.ts) were checked against these settings; the client also has a protected binary `download` method for report files.

Connect the shared access flows first: activation → sign-in → allowed school/role/child contexts → selected context → role screens. Use opaque bearer sessions, the real returned IDs and current permissions. Send stable idempotency keys for commands and `If-Match: "vN"` where documented. On stale data, reload and request user review; do not silently replace the new version.

Payment keys, database passwords, encryption keys and internal service keys remain backend-only. CORS and gateway origins are configured in the setup guide. No fixed demonstration account should be substituted for a real Principal or linked parent.

## Practical review walkthrough

1. As Principal, create a session/term, class arms, subjects, rooms, teacher eligibility and real assignments. Confirm stale edits and conflicts are rejected.
2. Enrol a fictional pupil/guardian, inspect provisioning and privately issue activation instructions. Replace/revoke an unused invitation and confirm the old activation flow fails. An active global account must retain its own recovery proofs.
3. As teacher, submit attendance and request a correction. Office approval should change accepted attendance; a pending request should not. Families should see only the selected child and published records.
4. Enter subject marks, submit each sheet, complete class-lead review and let Principal publish. Check a PDF and a correction revision. The adopted 20/20/60 policy, no overall rank, missing evidence and historical snapshots must remain consistent.
5. Publish a material, record physical work, release feedback and verify a historical document. Draft changes and private evidence should stay scoped.
6. As Accounts, issue an invoice, confirm a fictional manual payment and inspect balances/reconciliation. School Office should receive only the permitted fee-status summary. Connect actual Paystack Test mode after account setup.
7. Publish a notice, open a scoped conversation/case and inspect delivery metadata. A queued send/provider receipt is different from an in-app read. Check PDF/CSV reports and cross-school/child/role denials.

## Boundaries retained for release

- SH09 is the agreed help/contact boundary. Verified-email and saved-code recovery exist; staff-assisted recovery after all private proofs are lost remains a separate policy decision. No school can reset an active global identity.
- The adopted grading weights/bands are fixed and approved per cycle. Arbitrary grading/report builders and automated refund disbursement are outside this agreed implementation.
- Actual Paystack account, mailbox/native Expo delivery and frontend role-screen walkthroughs require their real account/device environment. Automated provider checks use local doubles and no real funds.
- HTTPS hosting/private networking, durable secrets, monitoring, backup/restore drills, representative deployed-data migration checks, load limits and independent security/access review remain release validation. Existing tests do not certify production readiness.
- CodeQL/dependency update configuration is included separately; record its actual scan findings and reviews rather than treating configuration as proof of a clean security audit.

The next milestone after your review is connecting the frontend and running the actual provider/device checks together.
