# Secrets Manager containers for the application keys (decision 6 area 3).
# Containers ONLY: no secret version and no value is ever defined in Terraform,
# so no key appears in source, state or plan. The human enters each value
# after the apply. Tasks reference the containers by ARN (step 2b).
# Encryption: the AWS managed key aws/secretsmanager (no kms_key_id).
#
# The environment-variable NAMES under which each task receives a value are
# decided (decision 6 area 3) and match config/course.json:
#   SCHOLARCHAIN_VOYAGE_API_KEY            embedding.apiKeyEnvVar
#   SCHOLARCHAIN_ANTHROPIC_API_KEY         llm.apiKeyEnvVar
#   SCHOLARCHAIN_SERVICE_API_KEY           service.apiKeyEnvVar
#   SCHOLARCHAIN_SERVICE_READONLY_API_KEY  service.readOnlyApiKeyEnvVar

locals {
  app_keys = {
    voyage = {
      env_var     = "SCHOLARCHAIN_VOYAGE_API_KEY"
      description = "Voyage AI embedding API key (value entered by hand after apply)."
    }
    anthropic = {
      env_var     = "SCHOLARCHAIN_ANTHROPIC_API_KEY"
      description = "Anthropic Messages API key (value entered by hand after apply)."
    }
    service = {
      env_var     = "SCHOLARCHAIN_SERVICE_API_KEY"
      description = "ScholarChain service API key, full access (value entered by hand after apply)."
    }
    service-readonly = {
      env_var     = "SCHOLARCHAIN_SERVICE_READONLY_API_KEY"
      description = "ScholarChain service API key, read-only (value entered by hand after apply)."
    }
  }
}

resource "aws_secretsmanager_secret" "app_key" {
  for_each = local.app_keys

  name_prefix             = "${var.app_key_name_prefix}${each.key}-api-key-"
  description             = each.value.description
  recovery_window_in_days = var.app_key_recovery_window_days

  tags = { Name = "${var.name_prefix}-${each.key}-api-key", "env-var" = each.value.env_var }
}
