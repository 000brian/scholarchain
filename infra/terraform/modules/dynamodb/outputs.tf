# Outputs of the dynamodb module (decision 6 area 6). Keys are the logical
# names of TableSpecs.scala.

output "table_names" {
  description = "Physical table names by logical name (RunLedger, EntityLinkCache, QuerySession, EvidenceBundle, ArtifactRegistry, ResearcherMetricCache)."
  value       = { for k, t in aws_dynamodb_table.this : k => t.name }
}

output "table_arns" {
  description = "Table ARNs by logical name."
  value       = { for k, t in aws_dynamodb_table.this : k => t.arn }
}
