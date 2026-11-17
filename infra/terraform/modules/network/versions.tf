# Provider source only. The version is pinned once in the root
# (environments/course/versions.tf); no provider block in a child module.
terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
