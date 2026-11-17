# Root of the ScholarChain course environment.
# The only provider is hashicorp/aws, pinned exactly here and nowhere else.
# Child modules declare the provider source only (no version, no provider block).
# Backend: the default local backend (see infra/terraform/STATE.md).
terraform {
  required_version = "= 1.17.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.59.0"
    }
  }
}
