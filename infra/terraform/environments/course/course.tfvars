# Non-personal values of the course environment. Committed; holds no secret,
# account ID, bucket name or personal value. Passed by scripts/plan.sh with
# -var-file=course.tfvars (Terraform does not load it by itself).
# Steps 2a and 2b add the values of their areas here.

aws_region = "us-east-1"

# Area 3: remove secret containers at once on cleanup, so a re-apply after
# cleanup never collides with a name still in its recovery window.
app_key_recovery_window_days = 0

# Area 6: ephemeral course environment; items are reproducible from S3.
dynamodb_point_in_time_recovery_enabled = false
dynamodb_deletion_protection_enabled    = false

# Area 7: ephemeral graph store, reproducible from S3 by the bulk loader.
neptune_deletion_protection = false
neptune_skip_final_snapshot = true

# Area 10: the runtime is off on the first apply (the ECR repository is empty
# and the secrets have no values). Phase 14 sets true together with
# image_tag and graph_run_id in a second signed plan.
runtime_enabled = false

# Area 13: explicit cleanup mode stays off; the human sets it to true only for
# the documented removal order (README.md, "Cleanup mode").
cleanup_mode = false

# Date after which the course environment should be removed (expires tag).
# Human to confirm before signing the plan.
expires = "2026-12-31"
