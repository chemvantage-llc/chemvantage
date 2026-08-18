# Cloud Run Development Migration Guide

This guide defines the first implementation target for migrating ChemVantage development traffic from App Engine to Cloud Run.

## Target

- Current development URL: https://dev.chemvantage.org
- Cloud Run destination URL (after DNS cutover): https://dev.chemvantage.org
- Project: dev-vantage-hrd
- Region: us-central1
- Suggested service name: chemvantage-dev

## Prerequisites

1. Install and authenticate gcloud.
2. Ensure billing is enabled on the project.
3. Enable required APIs:
   - run.googleapis.com
   - cloudbuild.googleapis.com
   - artifactregistry.googleapis.com
  - cloudtasks.googleapis.com
4. Create Artifact Registry repository (once):

```bash
gcloud artifacts repositories create chemvantage \
  --repository-format=docker \
  --location=us-central1 \
  --description="ChemVantage container images"
```

5. Ensure Cloud Tasks queue exists in the same region (once):

```bash
gcloud tasks queues describe default --location=us-central1 --project=dev-vantage-hrd \
  || gcloud tasks queues create default --location=us-central1 --project=dev-vantage-hrd
```

## Cloud Tasks Authentication (Cloud Run)

ChemVantage now enqueues Cloud Tasks as HTTP requests (not App Engine requests) and can attach an OIDC token using the
`CLOUD_TASKS_OIDC_SERVICE_ACCOUNT` runtime environment variable.

Example (dev):

```bash
export TASKS_OIDC_SERVICE_ACCOUNT=890312835091-compute@developer.gserviceaccount.com
./scripts/deploy-dev.sh
```

Required IAM for authenticated task delivery:

1. Runtime service account can enqueue tasks:

```bash
gcloud projects add-iam-policy-binding dev-vantage-hrd \
  --member="serviceAccount:890312835091-compute@developer.gserviceaccount.com" \
  --role="roles/cloudtasks.enqueuer"
```

2. Token service account can invoke Cloud Run service:

```bash
gcloud run services add-iam-policy-binding chemvantage-dev \
  --project=dev-vantage-hrd \
  --region=us-central1 \
  --member="serviceAccount:890312835091-compute@developer.gserviceaccount.com" \
  --role="roles/run.invoker"
```

3. Cloud Tasks service agent can mint tokens for the token service account:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  890312835091-compute@developer.gserviceaccount.com \
  --member="serviceAccount:service-890312835091@gcp-sa-cloudtasks.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator"
```

## Build and Deploy (Dev)

`./scripts/deploy-dev.sh` now runs static asset sync first, then submits Cloud Build.

Defaults:

- Static source directory: `src/main/webapp`
- Static bucket: `chemvantage-static-dev`
- Dotfile exclusion regex: `(^|/)\\..*`

Run from repository root:

```bash
./scripts/deploy-dev.sh
```

Optional explicit image tag:

```bash
./scripts/deploy-dev.sh v2026-08-15
```

Optional controls:

```bash
# Skip static sync for this deploy
SKIP_STATIC_SYNC=true ./scripts/deploy-dev.sh

# Override static bucket/source for one run
STATIC_BUCKET=chemvantage-static-dev SOURCE_DIR=src/main/webapp ./scripts/deploy-dev.sh
```

Equivalent raw command:

```bash
gcloud builds submit --config cloudbuild.yaml \
  --substitutions _PROJECT_ID=dev-vantage-hrd,_REGION=us-central1,_REPOSITORY=chemvantage,_SERVICE=chemvantage-dev,_IMAGE_TAG=$(git rev-parse --short HEAD)
```

## Build and Deploy (Production)

`./scripts/deploy-prod.sh` now runs static asset sync first, then submits Cloud Build.

Required for production sync:

- `STATIC_BUCKET` must be set (example: `chemvantage-static-prod`)

Use the same Cloud Build pipeline with production substitutions:

```bash
STATIC_BUCKET=chemvantage-static-prod ./scripts/deploy-prod.sh
```

Optional explicit image tag:

```bash
STATIC_BUCKET=chemvantage-static-prod ./scripts/deploy-prod.sh v2026-08-15
```

Optional controls:

```bash
# Skip static sync for this deploy
SKIP_STATIC_SYNC=true ./scripts/deploy-prod.sh
```

Equivalent raw command:

```bash
gcloud builds submit --config cloudbuild.yaml \
  --substitutions _PROJECT_ID=chem-vantage-hrd,_REGION=us-central1,_REPOSITORY=chemvantage,_SERVICE=chemvantage-prod,_IMAGE_TAG=$(git rev-parse --short HEAD)
```

Recommendation: configure separate Cloud Build triggers and IAM scopes for dev and production so production deploys require explicit approval.

After deploy, capture the generated URL:

```bash
gcloud run services describe chemvantage-dev \
  --region us-central1 \
  --format='value(status.url)'
```

Use that URL for validation before DNS cutover.

## Recommended Validation Before DNS

1. Home page and static assets load.
2. LTI launch and deep link routes respond correctly.
3. Datastore read/write flows work in development.
4. Admin-restricted endpoints still reject unauthorized requests.
5. Cloud Run logs contain no startup/runtime errors.

## Load Balancer Path (Active Plan)

This environment now uses a global external Application Load Balancer path for custom domains. Direct Cloud Run domain mapping for dev.chemvantage.org was removed.

As of 2026-08-17, routing was inverted from the original "default to Cloud Run" model to a "default to the static bucket" model, so that unmatched/junk paths (bot scans, typos, etc.) resolve cheaply against the bucket instead of invoking a Cloud Run instance. This requires every servlet path to be explicitly enumerated in the URL map.

Provisioned resources:

- Serverless NEG: cv-dev-run-neg (targets Cloud Run service chemvantage-dev in us-central1)
- Backend service (servlets): cv-dev-run-bes
- Backend service (admin, IAP-enabled): cv-dev-admin-bes (also targets cv-dev-run-neg)
- Backend bucket (default): cv-dev-static-frontend (Cloud Storage bucket chemvantage-static-dev)
- Bucket website config: mainPageSuffix=index.html, notFoundPage=error/404.html
- URL map: cv-dev-urlmap
  - Default backend (both top-level and path matcher): cv-dev-static-frontend (static bucket)
  - Host rule: dev.chemvantage.org -> path matcher dev-host-matcher
  - Admin path rule (IAP, routed to cv-dev-admin-bes):
    - /Admin, /DataStoreCleaner, /Edit, /EraseEntity, /contacts, /messages, /ReportScore, /ValidateQuestions (each with a `/*` wildcard variant)
  - Redirect rule: /chemistry-reasoning -> urlRedirect (302 FOUND) to /chemistry-reasoning/ (infra-level, no app code)
  - All other servlet paths explicitly routed to cv-dev-run-bes:
    - /checkout, /Contribute, /example-questions, /examples, /Feedback, /feedback, /Help, /help, /Homework, /images/*, /itembank, /items, /jwks, /lti/deeplinks, /lti/registration, /lti/launch, /rewards/*, /item, /PlacementExam, /Poll, /PracticeExam, /Quiz, /Sage, /SmartText, /auth/token, /unsubscribe, /VideoQuiz (each with a `/*` wildcard variant except /images/* and /rewards/*, which are wildcard-only to match their servlet mappings)
  - Everything not listed above (static HTML/CSS/JS/docs/images assets, /chemistry-reasoning/* SPA assets, unknown paths) falls through to the bucket default.
- Managed certificate: cv-dev-devonly-cert (domain: dev.chemvantage.org)
- HTTPS target proxy: cv-dev-https-proxy
- Global forwarding rule: cv-dev-https-fr
- Global IPv4 address: cv-dev-lb-ip

Note: `/images/*` (ImageRedirect servlet, issues 301s to images.chemvantage.org) and `/rewards/*` (ManageReferrals servlet) were previously mis-routed to the static bucket under the old default-to-Cloud-Run model and were effectively unreachable; this is fixed by the explicit rules above.

**Maintenance requirement:** any new `@WebServlet` path added to the codebase must also be added to the `cv-dev-run-bes` (or `cv-dev-admin-bes` for admin/IAP-protected routes) path rule in `cv-dev-urlmap`, or it will silently 404 from the bucket instead of reaching the app.

### chemistry-reasoning: static-only routing (2026-08-18)

The `/chemistry-reasoning` SPA no longer has a backing servlet or Cloud Run classpath bundle. `src/chemistry-reasoning-standalone/` is synced directly into the static bucket under a `chemistry-reasoning/` prefix by `scripts/sync-static-dev.sh` / `scripts/sync-static-prod.sh` (in addition to the `src/main/webapp` sync). The bucket's `mainPageSuffix=index.html` website config serves `chemistry-reasoning/index.html` for the trailing-slash path automatically; the bare `/chemistry-reasoning` path is handled by a URL map `urlRedirect` rule (302 to `/chemistry-reasoning/`). This removed the `ChemistryReasoning.java` servlet and its `pom.xml` resource-copy block entirely — the feature now has zero Cloud Run/app dependency.

Current LB IP:

- 34.54.51.124

## GoDaddy DNS Cutover Checklist (when approved)

1. Open GoDaddy DNS management for chemvantage.org.
2. For host dev, create or replace with:
   - Type: A
   - Name: dev
   - Value: 34.54.51.124
   - TTL: 600 seconds (or lowest allowed)
3. Save changes.
4. Verify propagation:

```bash
dig +short A dev.chemvantage.org
```

Expected result: 34.54.51.124

5. Check managed certificate status:

```bash
gcloud compute ssl-certificates describe cv-dev-devonly-cert \
  --global \
  --project=dev-vantage-hrd \
  --format='yaml(name,managed.status,managed.domainStatus)'
```

Expected: managed.status=ACTIVE and domainStatus for dev.chemvantage.org is ACTIVE.

6. Validate hostname and representative servlet/static paths:

```bash
curl -I https://dev.chemvantage.org/
curl -I https://dev.chemvantage.org/css/style.css
curl -I https://dev.chemvantage.org/docs/question-json-ingest.md
curl -I https://dev.chemvantage.org/Quiz
curl -I https://dev.chemvantage.org/images/foo.png
```

Expected:

- `/` and static asset paths are served by the bucket backend (200, or custom 404 page for missing objects).
- Explicitly enumerated servlet routes (e.g. `/Quiz`) are served by Cloud Run.
- `/images/*` returns a 301 redirect to images.chemvantage.org (ImageRedirect servlet on Cloud Run).

7. Optional verification from URL map:

```bash
gcloud compute url-maps describe cv-dev-urlmap --global --project=dev-vantage-hrd
```

Confirm dev.chemvantage.org host rule maps enumerated servlet path rules to the backend services and defaults everything else to the backend bucket.

## Rollback

If issues are found after DNS cutover:

1. Revert GoDaddy A record for dev to its previous value.
2. Keep Cloud Run deployed for debugging.
3. Document failing routes and logs before retrying cutover.
