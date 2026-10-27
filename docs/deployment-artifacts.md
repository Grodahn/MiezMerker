# Internal field-alpha deployment artifacts

Foundation CI reuses its tested Spring Boot executable JAR and **app/dist**;
**dist-signed is never published for this environment**. Both PWA builds and
their browser tests remain. Vite emits build.json with full GITHUB_SHA and the
compiled policy. The candidate manifest binds JAR, PWA, Flyway SQL checksums,
FIELD_ALPHA_PHONE_CLOCK, full SHA, Foundation run ID and attempt. The artifact
contains application outputs only; host configuration and credentials are absent.

A candidate is not approved for deployment. `deployment.yml` runs trusted
default-branch code after main push CI completion. `github.py` requires successful
completed runs **and every job** from ci.yml, esp32.yml (all variants),
offline-auth.yml, local-issuer.yml and windows-idf-tools.yml for the exact SHA.
The small issuer/wrapper checks now run for every main push to make this contract
unambiguous; PR path filters remain. No Maven/Node build is repeated. New mandatory
CI workflows must be added to the approval policy and trigger together.

Only then is deployment-approved-<SHA> published, referring to the original
candidate's immutable artifact ID and GitHub SHA-256 digest. Pull clients also
require the approval run to have completed successfully. Earlier failures,
skipped jobs, PR/fork runs, pending runs and changed rerun attempts fail closed.
Re-running only some failed jobs may leave no candidate for the current attempt;
rerun the complete Foundation workflow to recreate that attempt's tested bundle.

Actions Artifacts are retained for 30 days. This avoids a registry/runtime,
per-commit Releases, and duplicate payload uploads. Downloads require a dedicated
fine-grained token restricted to this repository with Actions read (implicit
Metadata read). No host credential is present in GitHub Actions. After expiration,
existing host releases remain usable, but a fresh host needs a new successful
main CI build/approval. Token expiry/revocation fails closed and is visible in the
host service journal. Artifact limits/billing remain the repository owner's
responsibility. A GitHub App installation token is an alternative for operators
who need automated credential renewal.

## Pull client contract

`scripts/deployment/pull.py` runs unprivileged with project/environment JSON
configuration and a systemd `LoadCredential` token. Native timer/user/polkit,
Caddy, backup integration, hostnames, networks and mounts are private operator
configuration and intentionally absent here. It downloads no source and invokes
no Maven, npm, TypeScript, containers or Nix builds. Ordinary app updates only
stop/start the configured application service.

Each environment has a separate root containing releases/<full-SHA>, current,
state.json, an interruption journal and verified staging metadata. A shared
root-owned lock serializes deployments with consistent DB/media backups. Static
files and the JAR switch together after full digest, per-file checksum,
completeness, variant and commit verification. A durable transaction precedes
stop/switch/start. Success requires both application health and the PWA build
receipt through the configured HTTPS origin. Only then is state.json updated.
Unchanged/quarantined versions cause no downloads or restarts. Offline GitHub
leaves the running application intact. Verified staging survives migration holds
without repeated downloads; interrupted partial downloads are discarded.

The client keeps current, previous and one extra verified release (maximum
512 MiB expanded each), plus one bounded download. It refuses a download below
2 GiB free. Garbage collection only touches recognized SHA directories beneath
that environment's releases; no database, photo or credential paths are touched.
Malformed/failed versions are quarantined (32 recent failures retained).

## Flyway and rollback

Unchanged migration checksums allow ordinary automatic application updates.
Every changed migration set, including initial deployment, requires a root-owned
local approval tied to candidate SHA, previous database schema fingerprint, new
fingerprint, explicit rollback compatibility, a successful backup within 24 hours,
an expiry, and a live backup/USB guard. Modified or removed applied migrations are
always rejected. Incompatible/destructive migrations require a separate operator
procedure; **do not grant a compatibility approval to bypass that procedure**.

On failed start/health or interrupted activation, the previous admitted compatible
application is restored and checked, and the bad SHA is quarantined. On initial
failure the application remains stopped. Database high-water migration checksums
are retained conservatively even after application rollback: Flyway and data are
never rolled back by changing the JAR. If recovery itself fails, the transaction
remains for operator intervention. Existing PostgreSQL, photos and issuer keys
are always persistent and external to releases. Schema equality alone is not
proof of semantic data compatibility; review app/data contract changes as well.

For an operator rollback, invoke the configured client with `--rollback` as its
dedicated deploy user. It acquires the same operation lock, verifies the recorded
compatible previous release, atomically selects it, checks health, updates state,
quarantines the replaced SHA and holds polling. `--resume` explicitly resumes
polling; quarantined SHAs stay excluded. Never simply swap the symlink while
leaving state.json inconsistent. Database high-water marks remain unchanged.
Backups are for separate recovery, never automatically restored over a live DB.

## Same-origin serving and acceptance

Caddy must proxy /api, /api/**, /admin, /admin/**, /error and the backend's root
admin JS/CSS resources without stripping prefixes. Only current/public is a
static root. Explicit PWA routes get the SPA shell; missing assets and unknown
paths return 404. Hashed assets can be immutable; shell, sw.js, build.json and
stable assets must revalidate. Keep Secure/HttpOnly/SameSite session cookies,
CSRF and trusted reverse-proxy handling. The PWA retains prompt service-worker
registration, with no skipWaiting, clientsClaim or forced page reload added.
Do not install a local CA on clients as part of this pipeline. LAN/VPN access,
DNS and publicly trusted DNS-01 certificates are separate private host controls;
publication of artifacts never grants ingress or activates production.

Focused executable coverage: `python -m unittest discover -s scripts/tests -p
test_deployment.py -v` (all host cases execute on Linux in CI). It covers identity,
variant, archive traversal/link rejection, every CI gate, PR/fork rejection,
attempt changes, checksum failure, initial/update/no-change, download interruption,
outage, health rollback, durable recovery, quarantine and migration holds.
Actual main publication and real JAR startup require the PR to be merged by the
operator first. Reboot, authenticated Caddy/admin sessions, BLE continuity and
populated-database/photo recovery must be reported UNVERIFIED until performed.
