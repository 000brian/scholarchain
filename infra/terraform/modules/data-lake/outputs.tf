# Outputs of the data-lake module (decision 6 area 4). The bucket name is
# resolved at plan time; it is never written into a file or config/**.

output "bucket_name" {
  description = "Name of the data-lake bucket (generated from bucket_prefix)."
  value       = aws_s3_bucket.data.id
}

output "bucket_arn" {
  description = "ARN of the data-lake bucket."
  value       = aws_s3_bucket.data.arn
}

output "prefixes" {
  description = "Key prefixes of the data lake (no leading or trailing '/'): raw, curated, graph, rag, artifacts, logs, reports. config/course.json objectStore.*Prefix values and every IAM policy use these."
  value       = local.prefixes
}

output "emr_log_uri" {
  description = "S3 location of EMR cluster logs (LogUri on createCluster)."
  value       = "s3://${aws_s3_bucket.data.id}/${local.prefixes.logs}/emr/"
}
