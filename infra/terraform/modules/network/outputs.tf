# Outputs of the network module (decision 6 area 2). IDs only; no public IP
# address is exported.

output "vpc_id" {
  description = "ID of the VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "Private IPv4 range of the VPC."
  value       = aws_vpc.this.cidr_block
}

output "availability_zones" {
  description = "The two AZ names used (read at plan time)."
  value       = local.azs
}

output "public_subnet_ids" {
  description = "Public subnets (NAT gateway only)."
  value       = aws_subnet.public[*].id
}

output "app_subnet_ids" {
  description = "Private app subnets: internal ALB, ECS tasks (services and ops), API Gateway VPC link."
  value       = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  description = "Private data subnets: Neptune and EMR."
  value       = aws_subnet.data[*].id
}

output "private_route_table_id" {
  description = "Route table of all private subnets (default route via the single NAT gateway)."
  value       = aws_route_table.private.id
}

output "nat_gateway_id" {
  description = "The single NAT gateway."
  value       = aws_nat_gateway.this.id
}

output "s3_gateway_endpoint_id" {
  description = "S3 gateway endpoint."
  value       = aws_vpc_endpoint.s3.id
}

output "dynamodb_gateway_endpoint_id" {
  description = "DynamoDB gateway endpoint."
  value       = aws_vpc_endpoint.dynamodb.id
}

output "service_port" {
  description = "Container port of the HTTP services (config service.port must match)."
  value       = var.service_port
}

output "alb_listener_port" {
  description = "Port of the internal ALB listener."
  value       = var.alb_listener_port
}

output "neptune_port" {
  description = "Neptune port allowed by the security groups."
  value       = var.neptune_port
}

output "security_group_ids" {
  description = "Every security group of the environment, by role: vpc_link, alb, query, evidence, graph, worker, ops, neptune, emr_primary, emr_core, emr_service_access."
  value = {
    vpc_link           = aws_security_group.vpc_link.id
    alb                = aws_security_group.alb.id
    query              = aws_security_group.service["query"].id
    evidence           = aws_security_group.service["evidence"].id
    graph              = aws_security_group.service["graph"].id
    worker             = aws_security_group.service["worker"].id
    ops                = aws_security_group.service["ops"].id
    neptune            = aws_security_group.neptune.id
    emr_primary        = aws_security_group.emr["primary"].id
    emr_core           = aws_security_group.emr["core"].id
    emr_service_access = aws_security_group.emr["service-access"].id
  }
}

output "emr_managed_policy_tag" {
  description = "Tag that EMR clusters must carry for AmazonEMRServicePolicy_v2 (the state machine passes it on createCluster)."
  value       = var.emr_managed_policy_tag
}
