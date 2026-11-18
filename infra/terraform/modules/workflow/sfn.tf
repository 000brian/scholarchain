# The bounded pipeline state machine (decision 6 area 11), built only on the
# documented optimized EMR integrations (Step Functions developer guide,
# "Create and manage Amazon EMR clusters with Step Functions", read with the
# aws-docs MCP server in step 2b):
#   arn:aws:states:::elasticmapreduce:createCluster.sync   (waits for WAITING;
#     Instances.KeepJobFlowAliveWhenNoSteps must be true; Steps not allowed)
#   arn:aws:states:::elasticmapreduce:addStep.sync          (one step, waits)
#   arn:aws:states:::elasticmapreduce:terminateCluster.sync (ends, waits)
#
# Flow: create the EMR 7.x cluster -> Hadoop pipeline step -> end it ->
# create the emr-spark-8.1.0 cluster -> copy the Spark job config to the
# driver -> GraphAnalyticsJob -> (optional) JsonlExport -> end it. Either half is skipped when its
# input section is absent (for example a Spark-only rerun).
#
# Failure: every state after a successful create catches every error, ends
# that cluster with terminateCluster.sync (with Retry), then fails the
# execution with the original error. If ending the cluster fails, the
# execution fails loudly (ClusterCleanupFailed) and the cluster's idle
# auto-termination policy is the backstop. Step Functions does NOT end a
# cluster when an execution is stopped or times out; the idle policy covers
# those cases too (README).
#
# Cluster IDs live only in state variables (Assign), never in the definition.
# Execution input (never hard-coded; scripts/run-bounded-pipeline.sh builds it):
# {
#   "codeVersion": "<git commit SHA>",            -> -D scholarchain.code.version / --code-version
#   "hadoop": {                                     (optional section)
#     "jar": "s3://.../artifacts/.../hadoop.jar",
#     "output": "s3://.../curated/...",
#     "dblp": "s3://.../raw/dblp/...",
#     "dblpSnapshot": "<snapshot id>",
#     "extraArgs": ["--dblp-fingerprint", "sha256:...", "--source", "kind:ds:snap:path"]  (optional)
#   },
#   "spark": {                                      (optional section)
#     "jar": "s3://.../artifacts/.../spark.jar",
#     "config": "s3://.../artifacts/.../spark-job-config.json",
#     "hadoopExport": "s3://.../curated/.../run=<id>",
#     "identityAssignments": "s3://.../curated/.../identity_assignment",
#     "outRoot": "s3://.../graph/...",
#     "exportSourceRun": "s3://.../graph/.../run=<32 hex>",  (optional, Phase 13)
#     "exportRoot": "s3://.../graph/exports/<attempt>"       (optional, Phase 13)
#   }
# }
#
# Optional JSON Lines export (Phase 13 decision 8.3): when spark.exportRoot is
# present, a second step on the Spark cluster runs
# org.scholarchain.spark.JsonlExport after GraphAnalyticsJob with
# --source-run exportSourceRun --out-root exportRoot --code-version codeVersion.
# exportSourceRun is the FULL graph-run path (ending in run=<32 hex>): the
# graph job derives that hex from its input content, config and code version
# while it runs, so the state machine cannot know it beforehand (spark-engineer
# handoff). The script passes the run path of an earlier execution; with the
# same spark inputs GraphAnalyticsJob reuses that verified run (Pipeline.scala)
# before the export runs. Both fields are given together or not at all, and
# both must lie under graph_uri_prefix; otherwise the execution fails at
# ExportInputCheck, before any cluster is created.

locals {
  cs = var.cluster_settings

  # Tags on every cluster: the provider default tags (project, course,
  # environment, owner, expires) plus the tag AmazonEMRServicePolicy_v2 needs.
  cluster_tag_map = merge(
    data.aws_default_tags.current.tags,
    local.cs.tags,
    { "managed-by" = "step-functions" },
  )
  cluster_tags = [for k in sort(keys(local.cluster_tag_map)) : { Key = k, Value = local.cluster_tag_map[k] }]

  cluster_instances = {
    Ec2SubnetId                   = local.cs.subnet_id
    EmrManagedMasterSecurityGroup = local.cs.security_groups.emr_managed_primary
    EmrManagedSlaveSecurityGroup  = local.cs.security_groups.emr_managed_core
    ServiceAccessSecurityGroup    = local.cs.security_groups.service_access
    KeepJobFlowAliveWhenNoSteps   = local.cs.keep_alive_no_steps
    TerminationProtected          = false
    InstanceGroups = [
      {
        Name          = "primary"
        InstanceRole  = "MASTER"
        Market        = local.cs.market
        InstanceType  = local.cs.primary_instance_type
        InstanceCount = 1
      },
      {
        Name          = "core"
        InstanceRole  = "CORE"
        Market        = local.cs.market
        InstanceType  = local.cs.core_instance_type
        InstanceCount = local.cs.core_instance_count
      },
    ]
  }

  # Common createCluster arguments (RunJobFlow request syntax).
  cluster_common = {
    LogUri            = local.cs.log_uri
    ServiceRole       = local.cs.service_role_arn
    JobFlowRole       = local.cs.instance_profile
    VisibleToAllUsers = true
    BootstrapActions = [{
      Name                  = "scholarchain-bootstrap"
      ScriptBootstrapAction = { Path = local.cs.bootstrap_script_uri, Args = [] }
    }]
    Instances            = local.cluster_instances
    StepConcurrencyLevel = local.cs.step_concurrency_level
    ManagedScalingPolicy = {
      ComputeLimits = {
        UnitType                     = local.cs.managed_scaling.unit_type
        MinimumCapacityUnits         = local.cs.managed_scaling.minimum_capacity_units
        MaximumCapacityUnits         = local.cs.managed_scaling.maximum_capacity_units
        MaximumOnDemandCapacityUnits = local.cs.managed_scaling.maximum_on_demand_capacity_units
        MaximumCoreCapacityUnits     = local.cs.managed_scaling.maximum_core_capacity_units
      }
    }
    AutoTerminationPolicy = { IdleTimeout = local.cs.idle_timeout_seconds }
    Tags                  = local.cluster_tags
  }

  spark_config_local = "/home/hadoop/scholarchain-spark-job-config.json"

  # ExportInputCheck conditions (JSONata). The prefix is a bucket URI, which
  # never contains a quote.
  graph_prefix_len = length(var.graph_uri_prefix)
  export_absent    = "{% $not($exists($pipelineInput.spark.exportRoot)) and $not($exists($pipelineInput.spark.exportSourceRun)) %}"
  export_valid = join(" and ", [
    "{% $type($pipelineInput.spark.exportRoot) = 'string'",
    "$type($pipelineInput.spark.exportSourceRun) = 'string'",
    "$length($pipelineInput.spark.exportRoot) > ${local.graph_prefix_len}",
    "$length($pipelineInput.spark.exportSourceRun) > ${local.graph_prefix_len}",
    "$substring($pipelineInput.spark.exportRoot, 0, ${local.graph_prefix_len}) = '${var.graph_uri_prefix}'",
    "$substring($pipelineInput.spark.exportSourceRun, 0, ${local.graph_prefix_len}) = '${var.graph_uri_prefix}' %}",
  ])

  catch_to = {
    for next in ["CleanupHadoopCluster", "CleanupSparkCluster", "FailPipeline"] : next => [{
      ErrorEquals = ["States.ALL"]
      Assign = {
        failError = "{% $states.errorOutput.Error %}"
        failCause = "{% $states.errorOutput.Cause %}"
      }
      Next = next
    }]
  }

  catch_cleanup = [{
    ErrorEquals = ["States.ALL"]
    Assign = {
      cleanupError = "{% $states.errorOutput.Error %}"
    }
    Next = "FailClusterCleanup"
  }]

  terminate_retry = [{
    ErrorEquals     = ["States.ALL"]
    IntervalSeconds = 30
    MaxAttempts     = 3
    BackoffRate     = 2
  }]

  definition = {
    Comment        = "ScholarChain bounded pipeline: transient EMR 7.x Hadoop cluster, then transient emr-spark-8.1.0 Spark cluster. Every cluster is ended on success and on failure."
    QueryLanguage  = "JSONata"
    TimeoutSeconds = var.pipeline_timeout_seconds
    StartAt        = "ReadInput"
    States = {
      ReadInput = {
        Type = "Pass"
        Assign = {
          pipelineInput = "{% $states.input %}"
          failError     = "none"
          failCause     = "none"
          cleanupError  = "none"
        }
        Next = "ExportInputCheck"
      }

      # Phase 13: the optional export fields are checked before any cluster
      # exists, so a bad input costs nothing.
      ExportInputCheck = {
        Type = "Choice"
        Choices = [
          { Condition = local.export_absent, Next = "HadoopRequested" },
          { Condition = local.export_valid, Next = "HadoopRequested" },
        ]
        Default = "InvalidExportInput"
      }

      InvalidExportInput = {
        Type = "Pass"
        Assign = {
          failError = "InvalidExportInput"
          failCause = "spark.exportRoot and spark.exportSourceRun must be given together, as strings under the data lake's graph/ prefix."
        }
        Next = "FailPipeline"
      }

      HadoopRequested = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $exists($pipelineInput.hadoop) %}", Next = "CreateHadoopCluster" }]
        Default = "SparkRequested"
      }

      CreateHadoopCluster = {
        Type     = "Task"
        Resource = "arn:${var.partition}:states:::elasticmapreduce:createCluster.sync"
        Arguments = merge(local.cluster_common, {
          Name         = "${var.name_prefix}-hadoop"
          ReleaseLabel = local.cs.hadoop_release_label
          Applications = [{ Name = "Hadoop" }]
        })
        Assign = { hadoopClusterId = "{% $states.result.ClusterId %}" }
        Catch  = local.catch_to["FailPipeline"]
        Next   = "HadoopPipelineStep"
      }

      HadoopPipelineStep = {
        Type     = "Task"
        Resource = "arn:${var.partition}:states:::elasticmapreduce:addStep.sync"
        Arguments = {
          ClusterId = "{% $hadoopClusterId %}"
          Step = {
            Name            = "scholarchain-hadoop-pipeline"
            ActionOnFailure = "TERMINATE_CLUSTER"
            HadoopJarStep = {
              Jar       = "{% $pipelineInput.hadoop.jar %}"
              MainClass = "org.scholarchain.hadoop.pipeline.ScholarChainHadoop"
              Args      = "{% $append(['pipeline', '-D', 'scholarchain.code.version=' & $pipelineInput.codeVersion, '--output', $pipelineInput.hadoop.output, '--dblp', $pipelineInput.hadoop.dblp, '--dblp-snapshot', $pipelineInput.hadoop.dblpSnapshot], $exists($pipelineInput.hadoop.extraArgs) ? $pipelineInput.hadoop.extraArgs : []) %}"
            }
          }
        }
        Catch = local.catch_to["CleanupHadoopCluster"]
        Next  = "TerminateHadoopCluster"
      }

      TerminateHadoopCluster = {
        Type      = "Task"
        Resource  = "arn:${var.partition}:states:::elasticmapreduce:terminateCluster.sync"
        Arguments = { ClusterId = "{% $hadoopClusterId %}" }
        Retry     = local.terminate_retry
        Catch     = local.catch_cleanup
        Next      = "SparkRequested"
      }

      CleanupHadoopCluster = {
        Type      = "Task"
        Resource  = "arn:${var.partition}:states:::elasticmapreduce:terminateCluster.sync"
        Arguments = { ClusterId = "{% $hadoopClusterId %}" }
        Retry     = local.terminate_retry
        Catch     = local.catch_cleanup
        Next      = "FailPipeline"
      }

      SparkRequested = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $exists($pipelineInput.spark) %}", Next = "CreateSparkCluster" }]
        Default = "PipelineSucceeded"
      }

      CreateSparkCluster = {
        Type     = "Task"
        Resource = "arn:${var.partition}:states:::elasticmapreduce:createCluster.sync"
        Arguments = merge(local.cluster_common, {
          Name         = "${var.name_prefix}-spark"
          ReleaseLabel = local.cs.spark_release_label
          Applications = [{ Name = "Spark" }]
        })
        Assign = { sparkClusterId = "{% $states.result.ClusterId %}" }
        Catch  = local.catch_to["FailPipeline"]
        Next   = "CopySparkJobConfig"
      }

      # GraphAnalyticsJob reads --config from the driver's local disk
      # (client deploy mode: the driver runs on the primary node).
      CopySparkJobConfig = {
        Type     = "Task"
        Resource = "arn:${var.partition}:states:::elasticmapreduce:addStep.sync"
        Arguments = {
          ClusterId = "{% $sparkClusterId %}"
          Step = {
            Name            = "scholarchain-spark-job-config"
            ActionOnFailure = "TERMINATE_CLUSTER"
            HadoopJarStep = {
              Jar  = "command-runner.jar"
              Args = "{% ['aws', 's3', 'cp', $pipelineInput.spark.config, '${local.spark_config_local}'] %}"
            }
          }
        }
        Catch = local.catch_to["CleanupSparkCluster"]
        Next  = "SparkGraphAnalytics"
      }

      SparkGraphAnalytics = {
        Type     = "Task"
        Resource = "arn:${var.partition}:states:::elasticmapreduce:addStep.sync"
        Arguments = {
          ClusterId = "{% $sparkClusterId %}"
          Step = {
            Name            = "scholarchain-spark-graph-analytics"
            ActionOnFailure = "TERMINATE_CLUSTER"
            HadoopJarStep = {
              Jar  = "command-runner.jar"
              Args = "{% ['spark-submit', '--deploy-mode', 'client', '--class', 'org.scholarchain.spark.GraphAnalyticsJob', $pipelineInput.spark.jar, '--hadoop-export', $pipelineInput.spark.hadoopExport, '--identity-assignments', $pipelineInput.spark.identityAssignments, '--out-root', $pipelineInput.spark.outRoot, '--config', '${local.spark_config_local}', '--code-version', $pipelineInput.codeVersion] %}"
            }
          }
        }
        Catch = local.catch_to["CleanupSparkCluster"]
        Next  = "SparkExportRequested"
      }

      SparkExportRequested = {
        Type    = "Choice"
        Choices = [{ Condition = "{% $exists($pipelineInput.spark.exportRoot) %}", Next = "SparkJsonlExport" }]
        Default = "TerminateSparkCluster"
      }

      # Phase 13 decision 8.3: JSON Lines export of a graph run on the same
      # cluster. Options from JsonlExport.Required and JsonlExport.Optional
      # (modules/spark/.../JsonlExport.scala); records-per-file keeps its default.
      SparkJsonlExport = {
        Type     = "Task"
        Resource = "arn:${var.partition}:states:::elasticmapreduce:addStep.sync"
        Arguments = {
          ClusterId = "{% $sparkClusterId %}"
          Step = {
            Name            = "scholarchain-spark-jsonl-export"
            ActionOnFailure = "TERMINATE_CLUSTER"
            HadoopJarStep = {
              Jar  = "command-runner.jar"
              Args = "{% ['spark-submit', '--deploy-mode', 'client', '--class', 'org.scholarchain.spark.JsonlExport', $pipelineInput.spark.jar, '--source-run', $pipelineInput.spark.exportSourceRun, '--out-root', $pipelineInput.spark.exportRoot, '--code-version', $pipelineInput.codeVersion] %}"
            }
          }
        }
        Catch = local.catch_to["CleanupSparkCluster"]
        Next  = "TerminateSparkCluster"
      }

      TerminateSparkCluster = {
        Type      = "Task"
        Resource  = "arn:${var.partition}:states:::elasticmapreduce:terminateCluster.sync"
        Arguments = { ClusterId = "{% $sparkClusterId %}" }
        Retry     = local.terminate_retry
        Catch     = local.catch_cleanup
        Next      = "PipelineSucceeded"
      }

      CleanupSparkCluster = {
        Type      = "Task"
        Resource  = "arn:${var.partition}:states:::elasticmapreduce:terminateCluster.sync"
        Arguments = { ClusterId = "{% $sparkClusterId %}" }
        Retry     = local.terminate_retry
        Catch     = local.catch_cleanup
        Next      = "FailPipeline"
      }

      PipelineSucceeded = { Type = "Succeed" }

      # Fails with the original error after the cluster was ended.
      FailPipeline = {
        Type  = "Fail"
        Error = "{% $failError %}"
        Cause = "{% $failCause %}"
      }

      # Ending a cluster failed: loud failure; the idle auto-termination
      # policy is the backstop. The cause names both errors.
      FailClusterCleanup = {
        Type  = "Fail"
        Error = "ClusterCleanupFailed"
        Cause = "{% 'terminateCluster failed (' & $cleanupError & '); original error: ' & $failError & '. The EMR idle auto-termination policy ends the cluster.' %}"
      }
    }
  }
}

data "aws_default_tags" "current" {}

# ---- role --------------------------------------------------------------------------

data "aws_iam_policy_document" "sfn_trust" {
  statement {
    sid     = "StepFunctionsOfThisAccount"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${var.partition}:states:${var.region}:${var.account_id}:stateMachine:*"]
    }
  }
}

resource "aws_iam_role" "sfn" {
  name_prefix          = "${var.name_prefix}-pipeline-sfn-"
  description          = "Step Functions role of the bounded EMR pipeline (creates, steps and ends transient clusters)."
  assume_role_policy   = data.aws_iam_policy_document.sfn_trust.json
  permissions_boundary = var.permissions_boundary_arn

  tags = { Name = "${var.name_prefix}-pipeline-sfn" }
}

locals {
  cluster_arns = "arn:${var.partition}:elasticmapreduce:${var.region}:${var.account_id}:cluster/*"
}

data "aws_iam_policy_document" "sfn" {
  # createCluster: RunJobFlow has no resource type in the Service
  # Authorization Reference, so "*" is the narrowest resource; the request
  # must carry the project tag and the EMR managed-policy tag
  # (aws:RequestTag is listed for RunJobFlow).
  statement {
    sid       = "CreateTaggedClusters"
    actions   = ["elasticmapreduce:RunJobFlow"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/project"
      values   = ["scholarchain"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/for-use-with-amazon-emr-managed-policies"
      values   = ["true"]
    }
  }

  # Repair G-14: RunJobFlow with Tags also authorizes elasticmapreduce:AddTags
  # (Service Authorization Reference for Amazon Elastic MapReduce, API
  # operations table: RunJobFlow -> AddTags, RunJobFlow, iam:PassRole; EMR
  # Management Guide, "IAM policies for tag-based access to clusters": "To
  # create a cluster with a tag, you must also have permission for the
  # AddTags action"). AddTags supports the cluster resource type and the
  # aws:RequestTag and aws:TagKeys keys, so it is limited to cluster ARNs of
  # this account and Region, to requests that carry the same two tags as
  # RunJobFlow, and to the tag keys the state machine sends (cluster_tag_map).
  statement {
    sid       = "TagProjectClustersOnCreate"
    actions   = ["elasticmapreduce:AddTags"]
    resources = [local.cluster_arns]

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/project"
      values   = ["scholarchain"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/for-use-with-amazon-emr-managed-policies"
      values   = ["true"]
    }

    condition {
      test     = "ForAllValues:StringEquals"
      variable = "aws:TagKeys"
      values   = sort(keys(local.cluster_tag_map))
    }
  }

  # createCluster.sync, addStep.sync, terminateCluster.sync on cluster ARNs
  # (the guide's dynamic-resource templates), plus the policies passed on
  # create: auto-termination (EMR Management Guide, "Permissions to use
  # auto-termination": Put/Get/Remove) and managed scaling
  # (PutManagedScalingPolicy: unverified as required on create; added).
  statement {
    sid = "DriveProjectClusters"
    actions = [
      "elasticmapreduce:DescribeCluster",
      "elasticmapreduce:TerminateJobFlows",
      "elasticmapreduce:AddJobFlowSteps",
      "elasticmapreduce:DescribeStep",
      "elasticmapreduce:CancelSteps",
      "elasticmapreduce:PutAutoTerminationPolicy",
      "elasticmapreduce:GetAutoTerminationPolicy",
      "elasticmapreduce:RemoveAutoTerminationPolicy",
      "elasticmapreduce:PutManagedScalingPolicy",
    ]
    resources = [local.cluster_arns]
  }

  # Only the EMR service role and the EMR instance-profile role.
  statement {
    sid       = "PassEmrRoles"
    actions   = ["iam:PassRole"]
    resources = [var.emr_service_role_arn, var.emr_instance_role_arn]
  }

  # EMR Management Guide, "Using service-linked roles for Amazon EMR for
  # cleanup": the statement, with this account instead of "*".
  statement {
    sid       = "ElasticMapReduceServiceLinkedRole"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["arn:${var.partition}:iam::${var.account_id}:role/aws-service-role/elasticmapreduce.amazonaws.com*/AWSServiceRoleForEMRCleanup*"]

    condition {
      test     = "StringEquals"
      variable = "iam:AWSServiceName"
      values   = ["elasticmapreduce.amazonaws.com", "elasticmapreduce.amazonaws.com.cn"]
    }
  }

  # CloudWatch Logs delivery for the state machine's log group. The Step
  # Functions guide ("IAM Policies for logging to CloudWatch Logs") says these
  # delivery actions support no resource type, so "*" is the narrowest.
  statement {
    sid = "LogDelivery"
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "WriteOwnLogGroup"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${var.state_machine_log_group_arn}:*"]
  }
}

resource "aws_iam_role_policy" "sfn" {
  name   = "pipeline-emr"
  role   = aws_iam_role.sfn.id
  policy = data.aws_iam_policy_document.sfn.json
}

# ---- state machine -------------------------------------------------------------------

resource "aws_sfn_state_machine" "pipeline" {
  name       = "${var.name_prefix}-pipeline"
  type       = "STANDARD"
  role_arn   = aws_iam_role.sfn.arn
  definition = jsonencode(local.definition)

  # Execution data (input, output) is not logged: inputs name S3 locations.
  logging_configuration {
    log_destination        = "${var.state_machine_log_group_arn}:*"
    include_execution_data = false
    level                  = "ALL"
  }

  tags = { Name = "${var.name_prefix}-pipeline" }

  depends_on = [aws_iam_role_policy.sfn]
}
