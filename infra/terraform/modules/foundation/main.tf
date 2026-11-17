# foundation: account, partition and region facts shared by the other modules
# (decision 6 area 1). Nothing here creates a resource; these are read-only
# data sources evaluated at plan time, so no account ID is ever written in source.

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  # Base for every resource name: "<project>-<environment>", for example
  # "scholarchain-course". Lowercase letters, digits and hyphens only.
  name_prefix = "${var.project}-${var.environment}"

  # Short, stable, non-reversible suffix derived from the account ID at plan
  # time. Used where a name must be unique but a bucket_prefix/name_prefix is
  # not available (S3 Vectors bucket). It never exposes the account ID.
  account_suffix = substr(sha256(data.aws_caller_identity.current.account_id), 0, 8)
}
