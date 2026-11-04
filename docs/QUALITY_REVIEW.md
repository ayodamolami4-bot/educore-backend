# EduCore implementation and review record

Reviewed 4 November 2026. This records working checks and remaining review. It is not an independent security audit or a promise that defects cannot remain.

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

- Enable and review dependency-vulnerability and static security scans. The current CI builds, formats, validates contracts and executes tests; no CodeQL/Dependabot/SCA scan result is claimed.
- Expand an independent authorization matrix across sensitive routes, including changed roles/guardian links, worker execution, downloads/exports and connection reuse. The current shared-school databases use application checks/constraints; PostgreSQL row-level security is not implemented.
- Test migrations on a representative existing dataset as well as fresh databases. Applied Flyway files must remain unchanged.
- Validate actual Paystack test-account flows, external mailbox delivery and native Expo devices. Do not use real money or actual pupil records for development checks.
- Complete HTTPS hosting, private service networking, durable secret management, rate/abuse controls, monitoring and a tested backup/restore process.
- Review file malware scanning, operational limits and load behaviour; existing file type/hash/size checks are not a malware scanner.
- Have another engineer review critical access, payment, grading and promotion behaviour before release. Automated and AI reviews support that review; they do not supply independent sign-off.
- Finish the controls in the 17 PARTIAL pages, resolve the explicitly deferred recovery policy if scope changes, and connect/test the React Native role screens.

No new feature policy, paid hosting, provider delivery or production-data action is authorized by this review record.
