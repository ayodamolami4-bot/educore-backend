# EduCore implementation and review record

Reviewed 5 November 2026. This records working checks and remaining review. It is not an independent security audit or a promise that defects cannot remain.

## Keep the decisions available

| Record | Purpose |
| --- | --- |
| [Final administrator specification](product/FINAL_ADMIN_SPEC_v1.md) | Preserved agreed 34-template administrator target, including permissions and links to other roles. The original specification is unchanged. |
| [Nigerian school rules](NIGERIAN_RULES.md) | Secondary-school defaults and supported workflows; these do not claim to be uniform national rules. |
| [Architecture and patterns](ARCHITECTURE.md) | Actual technologies, service boundaries, coordination, concurrency and deferred options. |
| [Implementation status](IMPLEMENTATION_STATUS.md) | Verified code, evidence and unfinished work. A target specification does not mean every feature is built. |
| [Screen connections](SCREEN_CONNECTIONS.md) | All 94 agreed screens, real routes and exact missing controls. |
| [API guide](API.md) | Request/version/permission workflows and response-mapping limits. |

Later agreed decisions take precedence over the older proposed blueprint. Keep the product rules, Java decisions, API handoff and screen map in agreement. A change to published grading, payment, promotion or permission behaviour needs an explicit version/history rather than an undocumented settings change.

## Findings from official guidance

GitHub's AI-code review guidance identifies fabricated APIs, ignored constraints, suspicious dependencies and deleted/skipped tests as pitfalls. OWASP's AI secure-coding guidance also addresses unverified dependency versions, edits outside scope, secret exposure and fabricated test evidence. We apply those findings to the specific controls below; this is a review method, not a claim that a tool makes the application secure.

- [GitHub: review AI-generated code](https://docs.github.com/en/copilot/tutorials/review-ai-generated-code)
- [OWASP: secure coding with AI](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Coding_with_AI_Cheat_Sheet.html)
- [OWASP: tenant isolation](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html)

## Checks present in this project

| Failure to guard against | Current control and evidence boundary |
| --- | --- |
| Invented or stale endpoint documentation | `scripts/check-api-catalogue.py` compares Spring public routes, OpenAPI, CSV and every page connection. It resolves schema references; it does not prove complete response typing or all runtime permissions. |
| New tests written but not executed | The same checker discovers every `*-tests.mjs` module and requires its exported scenario function to be imported and awaited by the runner. Actual runtime PASS lines and the completed CI run remain the evidence of execution. |
| Only the successful path tested | PostgreSQL scenarios include cross-school/child/teacher denial, expired/consumed proofs, stale versions, duplicate commands, invalid input, incomplete marks/registers and provider/delivery failures. This is meaningful targeted coverage, not an exhaustive matrix of every role on every route. |
| A frontend-hidden button mistaken for authorization | Services authenticate current Identity context and check school, role, child/payer and teaching duty. File downloads repeat audience/visibility checks. Identity headers supplied by the app are not trusted. |
| Retried requests or simultaneous writes duplicate records | Idempotency, permanent references, local transactions, locks, SQL constraints and versions protect accepted effects. Outbox/inbox events deduplicate local processing; external delivery is not guaranteed exactly once. |
| Historical records silently rewritten | Published report/document/material snapshots, accepted submissions, invoices, payment references and reasoned audit/revision history are retained. |
| Official calculations drift into phone code | Java computes marks, bands, balances and promotion recommendations from accepted evidence; money is integer kobo. Metadata is constrained configuration, not executable code. |
| Demonstration results described as live validation | Paystack/SMTP/Expo use local doubles in tests. Hosted APIs, real mailbox/native devices, production migrations and restore drills remain separate evidence. |
| Secrets or unrelated edits reach a commit | Development credentials/access slips are ignored, public API/service keys stay separate, publishing compares actual file contents, and changes are saved on the expanded branch rather than silently replacing main. |
| Old notes contradict newer implementation | The architecture/rules notes were corrected at this checkpoint and linked to the preserved specification, implementation status and screen map. Future changes must update the affected records. |

The scenario-module convention is one exported `async function` per `*-tests.mjs` file, imported without alias and awaited by `scripts/integration.mjs`. The wiring check catches omissions in this convention; it does not detect every possible conditional skip or prove the assertions are correct.

## Work still required before real-school release

- Review ongoing dependency-vulnerability and static analysis findings. CodeQL configuration now scans Java and JavaScript/TypeScript, checks the actual SARIF output and rejects high/critical results. Dependabot update configuration is included. Configuration and synthetic gate tests do not establish an actual clean scan or a complete SCA review.
- Expand an independent authorization matrix across sensitive routes, including changed roles/guardian links, worker execution, downloads/exports and connection reuse. The current shared-school databases use application checks/constraints; PostgreSQL row-level security is not implemented.
- Test migrations on a representative existing dataset as well as fresh databases. Applied Flyway files must remain unchanged.
- Validate actual Paystack test-account flows, external mailbox delivery and native Expo devices. Do not use real money or actual pupil records for development checks.
- Complete HTTPS hosting, private service networking, durable secret management, rate/abuse controls, monitoring and a tested backup/restore process.
- Review file malware scanning, operational limits and load behaviour; existing file type/hash/size checks are not a malware scanner.
- Have another engineer review critical access, payment, grading and promotion behaviour before release. Automated and AI reviews support that review; they do not supply independent sign-off.
- Core controls now have 42 Java tests, 10 helper/gate checks, 80 PostgreSQL scenario groups and full container evidence. Connect/test the React Native role screens and retain SH09 help-only unless a different recovery policy is adopted.

No new feature policy, paid hosting, provider delivery or production-data action is authorized by this review record.

## Final core-control checkpoint

[Run 41888279723](https://github.com/ayodamolami4-bot/educore-backend/actions/runs/41888279723) verifies 35 unit tests, 80 PostgreSQL scenario groups, all five Docker builds and both startup checks at `5eb8d421c2407f3f3791ecff164f434973a2f89f`. The final map has 285 public operations, 93 MAPPED pages and the one retained help-only policy boundary. Provider/device/production evidence remains separate.

The final review corrected the earlier backlog's scope wording: the adopted grading policy is fixed but now explicitly Principal approved; refunds are externally executed and then confirmed; report catalogues supply the supported bounded synchronous previews/PDF/CSV rather than an arbitrary query/export-job builder. These decisions are recorded in the preserved administrator target and the current handover, not silently treated as new missing endpoints.

## Security hardening and finding review

[Backend run 42066557989](https://github.com/ayodamolami4-bot/educore-backend/actions/runs/42066557989) at `968183344e4b1665c515cf5e322031d0e0e0aaf0` verifies the current 42 Java tests, 4 Node setup-file checks on Linux and Windows, 6 Python gate checks, all 80 PostgreSQL scenario groups, all five container builds and both startup checks. [CodeQL run 42066557885](https://github.com/ayodamolami4-bot/educore-backend/actions/runs/42066557885) completes Java and JavaScript analysis with **one reviewed high diagnostic exception, three medium script notices and zero unresolved high/critical findings**. Findings remain uploaded, not dismissed.

The first actual scan at `fa3476ac2ca60241ff454c6531f14e42fe5ca1c2` reported **14 high/critical and 3 medium findings** across Java and JavaScript. The gate reads rule metadata from driver and extension components; missing scan files or rule metadata fail closed. Earlier scan configuration or successful jobs are not evidence of a clean source scan.

The hardening uses fixed component-built gateway origins with redirects disabled, bounded checked encryption allocations that preserve the existing GCM envelope, separate assignment reads/writes, explicit authenticated Spring Security chains and Spring's credential filters. Bootstrap publishes complete retry state exclusively, saves private access files atomically and restricts operator credentials to loopback origins. Regression checks cover denied cookie/session/Basic access, user/peer credential separation, encoded queries, ciphertext compatibility, eight concurrent setup processes and Windows file handling.

**EC-SEC-001 is a visible implementation-triaged diagnostic exception**, pending independent confirmation during review. The student-directory GET reads school-scoped data and appends an export access audit; it does not edit pupils, placements or guardian relationships. Removing that audit or changing the download contract would discard required behaviour. [HTTP semantics, section 9.2.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.1) explicitly permits request logging on safe methods. PostgreSQL regression assertions check two repeated downloads, unchanged domain-table snapshots, exactly one audit per download and parent/Accounts/other-school/cookie-only denials.

[The reviewed-finding record](codeql-reviewed-findings.json) binds this one rule, severity, controller location and seven source/test SHA-256 hashes. Source changes or duplicate matches require fresh review. All other high findings still fail. Every finding is printed and uploaded; the gate does not dismiss GitHub alerts or globally exclude a query. Six gate regression tests cover absent output, missing extension metadata, unknown/moved/duplicate findings and changed source fingerprints.

Three medium JavaScript data-flow notices remain visible: private bootstrap state to a fixed loopback request; private disposable-container state to its fixed local gateway; and a validated setup response to a fixed private access file through an exclusive temporary file and atomic rename. These are intentional operator/test flows, not arbitrary destinations. This triage does not authorize using the helpers with real pupil data, public operator ports or shared folders. No independent security sign-off or complete dependency-vulnerability/SCA review is claimed.

[GitHub's compiled-language CodeQL guidance](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages) documents the v4 action and manual Java extraction used here.
