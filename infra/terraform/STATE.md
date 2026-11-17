# Terraform state for ScholarChain

Owner: `infra-engineer`. Decision: Phase 12 work order, decision 5 ("local state plus a documented remote design").

## What the course environment uses: local state

- Root: `infra/terraform/environments/course/`. It declares no `backend` block, so Terraform uses the default `local` backend and writes `terraform.tfstate` in that folder.
- The state file lives only on the operator's laptop. It is gitignored (`.gitignore`, Terraform section) and is never committed. Agents are denied reading it (guard and permission rules); only the human operator reads it.
- `init` is run by the human: `terraform -chdir=infra/terraform/environments/course init -backend=false` for validation in Phase 12; `scripts/plan.sh` runs the full `init` against the local backend before planning.
- Plan files are written only under `infra/terraform/environments/course/plans/` (gitignored). They may contain account IDs and ARNs; only the human reads or shares them, masked.

### Why local state for the course environment

1. **No bootstrap apply.** A remote backend needs its own bucket before the first `init`. That bucket would be one more resource created outside the reviewed plan (or one more root and one more apply), for no grading value.
2. **One operator.** Only the human architect runs `plan` and `deploy`, one at a time. Locking between people is not needed.
3. **Short life.** The environment exists from the Phase 14 apply to the Phase 17 cleanup (the `expires` tag records the end date). It is not a persistent environment.
4. **Cost.** A state bucket is cheap, but the course runs under a fixed $25 ceiling; nothing is added that is not needed.

### Risk of local state, and how it is contained

| Risk | Effect | Mitigation |
|---|---|---|
| The laptop or the state file is lost or corrupted | Terraform no longer knows what it created; `plan` would propose to create everything again; nothing is removed automatically | Every resource carries the default tags `project=scholarchain`, `environment=course`, `managed-by=terraform`, `owner`, `expires` (provider `default_tags`). Leftovers are found by tag (Resource Groups Tagging API or Tag Editor, filtered on `project=scholarchain` and `environment=course`) and removed in the order documented for `scripts/cleanup.sh` (decision 6 area 13). Untaggable children (for example route-table associations, security-group rules) go with their tagged parent. |
| Two `apply` runs at once | Corrupted state | One operator; `scripts/deploy.sh` is the only apply path and runs a saved plan; Terraform's local backend takes a local lock file during operations. |
| State contains identifiers (account ID, ARNs, endpoints) | Disclosure if shared | Never committed, never pasted into reports; agents cannot read it. No secret value is in state, because Terraform creates only Secrets Manager containers (no `aws_secretsmanager_secret_version`). |
| A stale copy is used after a laptop restore | Drift, or duplicate creates | Optional manual step for the human: after each apply, copy `terraform.tfstate` to an encrypted personal backup outside the repository. |

## Remote design for a persistent environment (documented, not built)

Use this design if ScholarChain ever gets a persistent environment (more than one operator, or a life longer than the course).

### Layout

- A separate **bootstrap root**, for example `infra/terraform/bootstrap/`, with local state, applied once by the human. It creates only the state bucket (and nothing else). Its own state is small and can be kept locally or re-imported if lost.
- Each environment root then declares:

```hcl
terraform {
  backend "s3" {
    bucket       = "<state bucket, passed with -backend-config, never committed>"
    key          = "scholarchain/<environment>/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
```

- The bucket name is not written in source; it is passed at `init` time with `-backend-config=bucket=...` from the operator's shell or a gitignored backend file.

### State bucket settings

- **Versioning on**, so an overwritten or damaged state can be restored from a previous version. A lifecycle rule expires noncurrent versions after a bounded number of days.
- **Encryption at rest:** SSE-S3 by default; SSE-KMS with a customer-managed key if the environment needs key-level access control or audit (bills monthly; see `COSTS.md`).
- **Encryption in transit:** a bucket policy that denies any request with `aws:SecureTransport = false`.
- **Block Public Access** on all four settings; object ownership `BucketOwnerEnforced` (no ACLs).
- **Locking:** native S3 locking with `use_lockfile = true` (Terraform 1.10 and later; the lock is a `.tflock` object next to the state). No DynamoDB lock table; the DynamoDB-based locking argument is deprecated in current Terraform.
- `force_destroy = false` and `prevent_destroy` on the bucket in the bootstrap root.

### Who may read and write state

- **Deployment role** (the role `scripts/deploy.sh` and `scripts/plan.sh` run under, assumed by the human): `s3:ListBucket` on the state bucket limited to the environment's key prefix; `s3:GetObject`, `s3:PutObject` on the state key; `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` on the `.tflock` lock object only.
- **Human reviewers:** read-only (`s3:GetObject` on the state key) when needed for an incident.
- **Runtime roles** (ECS tasks, EMR, Step Functions, loaders): no access.
- **Agents:** no access. The read-only MCP profile (`ViewOnlyAccess`) lists buckets but does not read object contents; the guard also denies reading state files locally.

### Migrating from local to remote

1. Apply the bootstrap root (human).
2. Add the `backend "s3"` block to the environment root.
3. Run `terraform init -migrate-state` with the backend configuration (human), confirm, and verify that `plan` shows no changes.
4. Remove the local state file from the laptop only after the remote copy is verified.
