# Outputs of the emr module (decision 6 area 5).

output "cluster_settings" {
  description = "Everything the pipeline state machine needs to create a bounded transient EMR cluster (release labels, network, roles, log and bootstrap locations, capacity, managed scaling, idle timeout, tags)."
  value       = local.cluster_settings
}

output "bootstrap_script_uri" {
  description = "S3 URI of the bootstrap action script."
  value       = local.cluster_settings.bootstrap_script_uri
}

output "hadoop_release_label" {
  description = "Release label of the Hadoop cluster (EMR 7.x)."
  value       = var.hadoop_release_label
}

output "spark_release_label" {
  description = "Release label of the Spark cluster."
  value       = var.spark_release_label
}
