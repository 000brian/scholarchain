# Outputs of the vectors module (decision 6 area 8).

output "vector_bucket_name" {
  description = "Name of the vector bucket (storage.vectorBucket is set from this by the task definitions; never in config/**)."
  value       = aws_s3vectors_vector_bucket.this.vector_bucket_name
}

output "vector_bucket_arn" {
  description = "ARN of the vector bucket."
  value       = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "index_arns" {
  description = "Index ARNs by index name (researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1)."
  value       = { for k, i in aws_s3vectors_index.this : k => i.index_arn }
}

output "index_names_by_config_key" {
  description = "Index names by their configuration key (vectors.*Index)."
  value       = { for k, i in local.indexes : i.configKey => k }
}

output "limits" {
  description = "Application limits to respect in config (README): PutVectors batch size and query topK."
  value = {
    put_vectors_max_batch = 500
    query_max_top_k       = 30
    dimension             = var.index_spec.dimension
  }
}
