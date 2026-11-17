# The single aws provider configuration. Child modules inherit it implicitly.
# Default tags put the required tags on every taggable resource, so leftover
# resources can be found by tag (project, environment) during cleanup.
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      project      = "scholarchain"
      course       = "cs441"
      environment  = "course"
      owner        = var.owner
      expires      = var.expires
      "managed-by" = "terraform"
    }
  }
}
