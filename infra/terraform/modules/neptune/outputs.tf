# Outputs of the neptune module (decision 6 area 7).

output "cluster_endpoint" {
  description = "Writer endpoint host of the cluster (storage.neptuneEndpoint is set from this by the task definitions; never in config/**)."
  value       = aws_neptune_cluster.this.endpoint
}

output "reader_endpoint" {
  description = "Reader endpoint host of the cluster."
  value       = aws_neptune_cluster.this.reader_endpoint
}

output "port" {
  description = "Neptune port."
  value       = aws_neptune_cluster.this.port
}

output "cluster_id" {
  description = "Cluster identifier."
  value       = aws_neptune_cluster.this.id
}

output "cluster_arn" {
  description = "Cluster ARN (rds namespace)."
  value       = aws_neptune_cluster.this.arn
}

output "cluster_resource_id" {
  description = "Cluster resource ID (cluster-...), used in neptune-db IAM resource ARNs: arn:<partition>:neptune-db:<region>:<account>:<cluster_resource_id>/*."
  value       = aws_neptune_cluster.this.cluster_resource_id
}

output "instance_id" {
  description = "Identifier of the one instance."
  value       = aws_neptune_cluster_instance.this.id
}
