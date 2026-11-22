# shellcheck shell=bash
# scripts/emulator/env.sh - points ONE terminal at the local AWS Emulator
# (https://127.0.0.1:9297). Owner: infra-engineer (Phase 16, decision 12
# item 2). SOURCE it from the repository root, in its own terminal:
#
#   source scripts/emulator/env.sh
#
# It must parse and behave the same in zsh (the human's shell) and bash, so it
# uses only POSIX-style tests, case, unset, export and printf (no arrays, no
# bash-only expansions, no functions). It starts no program, makes no network
# call and writes no file. It changes no shell option.
#
# What it does, in order:
#   1. Refuses (return 4) unless the current folder is the repository root
#      (config/emulator.json, scripts/emulator/env.sh, build.sbt and .git all
#      present), because SCHOLARCHAIN_CONFIG_FILE is set from $PWD and must be
#      absolute: forked test JVMs run from modules/storage or modules/services.
#   2. Unsets the eight variables the emulator safety contract (Phase 16
#      decision 6.6) requires to be unset: AWS_PROFILE, AWS_ACCESS_KEY_ID,
#      AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN, AWS_ROLE_ARN,
#      AWS_WEB_IDENTITY_TOKEN_FILE, AWS_CONTAINER_CREDENTIALS_RELATIVE_URI,
#      AWS_CONTAINER_CREDENTIALS_FULL_URI. It also unsets the four ScholarChain
#      API-key variables named in config/course.json (the emulator config uses
#      the fake embedding and LLM providers, so no key is needed) and every
#      SCHOLARCHAIN_CFG_* override ConfigLoader knows, so a value left from an
#      AWS session (for example a real queue URL) never overrides
#      config/emulator.json.
#   3. Exports the variables of the emulator documentation, "To point your SDK
#      at a local emulator"
#      (https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html):
#      AWS_ENDPOINT_URL, AWS_SHARED_CREDENTIALS_FILE, AWS_CONFIG_FILE,
#      AWS_CA_BUNDLE, AWS_EC2_METADATA_DISABLED, AWS_DISABLE_HOST_PREFIX_INJECTION,
#      AWS_REGION, AWS_DEFAULT_REGION (port 9297 only), plus
#      SCHOLARCHAIN_EMULATOR_ENDPOINT and SCHOLARCHAIN_CONFIG_FILE.
#   4. Prefixes the prompt with "[EMULATOR] " (once, even if sourced again).
#
# It never sets JAVA_TOOL_OPTIONS: the documentation warns that a trust store
# set there reaches every JVM started from the shell, sbt included. The sbt
# EmulatorTest configuration passes the trust store to the forked test JVM
# only (build.sbt, build-engineer). If JAVA_TOOL_OPTIONS is already set, this
# file prints a warning and leaves it alone.
#
# It does not set SCHOLARCHAIN_EMULATOR_RUN_ID or
# SCHOLARCHAIN_EMULATOR_RESULTS_DIR: the run book gives both lines per run.
#
# Return codes: 0 done; 2 executed instead of sourced (bash only); 4 refused
# (not at the repository root).
#
# Status: static: shellcheck clean; runtime: untested; human execution required.

if [ -n "${BASH_VERSION:-}" ] && [ "${BASH_SOURCE[0]:-}" = "$0" ]; then
  printf 'ERROR: env.sh: source this file (source scripts/emulator/env.sh); running it changes nothing\n' >&2
  exit 2
fi

if [ ! -f "${PWD}/config/emulator.json" ] || [ ! -f "${PWD}/scripts/emulator/env.sh" ] \
  || [ ! -f "${PWD}/build.sbt" ] || [ ! -e "${PWD}/.git" ]; then
  printf 'ERROR: env.sh: refused: source it from the ScholarChain repository root (no change made)\n' >&2
  return 4
fi

# 2. Credential sources the emulator safety contract requires to be unset.
unset AWS_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN \
  AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE \
  AWS_CONTAINER_CREDENTIALS_RELATIVE_URI AWS_CONTAINER_CREDENTIALS_FULL_URI

# ScholarChain API-key variables (names from config/course.json).
unset SCHOLARCHAIN_VOYAGE_API_KEY SCHOLARCHAIN_ANTHROPIC_API_KEY \
  SCHOLARCHAIN_SERVICE_API_KEY SCHOLARCHAIN_SERVICE_READONLY_API_KEY

# Every SCHOLARCHAIN_CFG_* variable ConfigLoader knows (ConfigLoader.allKeys
# through ConfigKey.envVarFor, modules/core/.../config/ConfigLoader.scala).
unset SCHOLARCHAIN_CFG_ENVIRONMENT SCHOLARCHAIN_CFG_AWS_REGION \
  SCHOLARCHAIN_CFG_AWS_ACCOUNT_ID SCHOLARCHAIN_CFG_STORAGE_RAW_BUCKET \
  SCHOLARCHAIN_CFG_STORAGE_CURATED_BUCKET SCHOLARCHAIN_CFG_STORAGE_GRAPH_BUCKET \
  SCHOLARCHAIN_CFG_STORAGE_VECTOR_BUCKET SCHOLARCHAIN_CFG_STORAGE_VECTOR_INDEX \
  SCHOLARCHAIN_CFG_STORAGE_NEPTUNE_ENDPOINT SCHOLARCHAIN_CFG_STORAGE_DYNAMO_DB_TABLE \
  SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_POLICY_VERSION \
  SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_MATCH_THRESHOLD \
  SCHOLARCHAIN_CFG_ENTITY_RESOLUTION_REVIEW_THRESHOLD \
  SCHOLARCHAIN_CFG_MODELS_EMBEDDING_MODEL_ID SCHOLARCHAIN_CFG_MODELS_ANSWER_MODEL_ID \
  SCHOLARCHAIN_CFG_GRAPH_WINDOWS SCHOLARCHAIN_CFG_GRAPH_MIN_EDGE_WEIGHT \
  SCHOLARCHAIN_CFG_BRIDGE_ALGORITHM SCHOLARCHAIN_CFG_BRIDGE_SAMPLE_SIZE \
  SCHOLARCHAIN_CFG_BRIDGE_RANDOM_SEED SCHOLARCHAIN_CFG_BRIDGE_TOP_K
unset SCHOLARCHAIN_CFG_OBJECT_STORE_RAW_PREFIX SCHOLARCHAIN_CFG_OBJECT_STORE_CURATED_PREFIX \
  SCHOLARCHAIN_CFG_OBJECT_STORE_GRAPH_PREFIX \
  SCHOLARCHAIN_CFG_OBJECT_STORE_SEMANTIC_CONTEXT_PREFIX \
  SCHOLARCHAIN_CFG_OBJECT_STORE_REPORTS_PREFIX SCHOLARCHAIN_CFG_OBJECT_STORE_LOGS_PREFIX \
  SCHOLARCHAIN_CFG_OBJECT_STORE_SEMANTIC_CONTEXT_BUCKET \
  SCHOLARCHAIN_CFG_OBJECT_STORE_REPORTS_BUCKET SCHOLARCHAIN_CFG_OBJECT_STORE_LOGS_BUCKET \
  SCHOLARCHAIN_CFG_DYNAMO_DB_RUN_LEDGER_TABLE SCHOLARCHAIN_CFG_DYNAMO_DB_ENTITY_LINK_CACHE_TABLE \
  SCHOLARCHAIN_CFG_DYNAMO_DB_QUERY_SESSION_TABLE SCHOLARCHAIN_CFG_DYNAMO_DB_EVIDENCE_BUNDLE_TABLE \
  SCHOLARCHAIN_CFG_DYNAMO_DB_ARTIFACT_REGISTRY_TABLE \
  SCHOLARCHAIN_CFG_DYNAMO_DB_RESEARCHER_METRIC_CACHE_TABLE \
  SCHOLARCHAIN_CFG_NEPTUNE_PORT SCHOLARCHAIN_CFG_NEPTUNE_QUERY_TIMEOUT_MILLIS \
  SCHOLARCHAIN_CFG_NEPTUNE_MERGE_ROW_CAP
unset SCHOLARCHAIN_CFG_VECTORS_RESEARCHER_PROFILE_INDEX \
  SCHOLARCHAIN_CFG_VECTORS_COLLABORATION_COMMUNITY_INDEX \
  SCHOLARCHAIN_CFG_VECTORS_PAPER_ABSTRACT_INDEX SCHOLARCHAIN_CFG_VECTORS_COLLABORATION_PATH_INDEX \
  SCHOLARCHAIN_CFG_VECTORS_TOPIC_INSTITUTION_SUMMARY_INDEX \
  SCHOLARCHAIN_CFG_VECTORS_PUT_BATCH_SIZE SCHOLARCHAIN_CFG_VECTORS_MAX_RETRIES \
  SCHOLARCHAIN_CFG_VECTORS_RETRY_BASE_DELAY_MILLIS SCHOLARCHAIN_CFG_VECTORS_RETRY_MAX_DELAY_MILLIS \
  SCHOLARCHAIN_CFG_VECTORS_QUERY_TOP_K \
  SCHOLARCHAIN_CFG_EMBEDDING_PROVIDER SCHOLARCHAIN_CFG_EMBEDDING_DIMENSIONS \
  SCHOLARCHAIN_CFG_EMBEDDING_BATCH_SIZE SCHOLARCHAIN_CFG_EMBEDDING_MAX_RETRIES \
  SCHOLARCHAIN_CFG_EMBEDDING_RETRY_BASE_DELAY_MILLIS \
  SCHOLARCHAIN_CFG_EMBEDDING_RETRY_MAX_DELAY_MILLIS \
  SCHOLARCHAIN_CFG_EMBEDDING_REQUESTS_PER_MINUTE SCHOLARCHAIN_CFG_EMBEDDING_TOKENS_PER_MINUTE \
  SCHOLARCHAIN_CFG_EMBEDDING_API_KEY_ENV_VAR
unset SCHOLARCHAIN_CFG_LLM_PROVIDER SCHOLARCHAIN_CFG_LLM_MAX_OUTPUT_TOKENS \
  SCHOLARCHAIN_CFG_LLM_REQUEST_TIMEOUT_MILLIS SCHOLARCHAIN_CFG_LLM_MAX_RETRIES \
  SCHOLARCHAIN_CFG_LLM_RETRY_BASE_DELAY_MILLIS SCHOLARCHAIN_CFG_LLM_RETRY_MAX_DELAY_MILLIS \
  SCHOLARCHAIN_CFG_LLM_REQUESTS_PER_MINUTE SCHOLARCHAIN_CFG_LLM_MAX_STRUCTURED_RETRIES \
  SCHOLARCHAIN_CFG_LLM_CONTEXT_TOKEN_BUDGET SCHOLARCHAIN_CFG_LLM_API_KEY_ENV_VAR \
  SCHOLARCHAIN_CFG_RUNTIME_DEFAULT_WINDOW_LABEL SCHOLARCHAIN_CFG_RUNTIME_RANKING_CACHE_TOP_K \
  SCHOLARCHAIN_CFG_RUNTIME_MAX_BUNDLE_ITEMS SCHOLARCHAIN_CFG_RUNTIME_MAX_PATH_DEPTH \
  SCHOLARCHAIN_CFG_RUNTIME_GRAPH_RESULT_LIMIT SCHOLARCHAIN_CFG_RUNTIME_GRAPH_TIMEOUT_MILLIS \
  SCHOLARCHAIN_CFG_RUNTIME_MAX_RESEARCHER_NAMES
unset SCHOLARCHAIN_CFG_SERVICE_PORT SCHOLARCHAIN_CFG_SERVICE_API_KEY_ENV_VAR \
  SCHOLARCHAIN_CFG_SERVICE_READ_ONLY_API_KEY_ENV_VAR SCHOLARCHAIN_CFG_SERVICE_MAX_REQUEST_BYTES \
  SCHOLARCHAIN_CFG_SERVICE_SYNC_DEADLINE_MILLIS SCHOLARCHAIN_CFG_SERVICE_MAX_CONCURRENT_QUERIES \
  SCHOLARCHAIN_CFG_SERVICE_WORKER_CONCURRENCY SCHOLARCHAIN_CFG_SERVICE_JOB_MAX_ATTEMPTS \
  SCHOLARCHAIN_CFG_SERVICE_JOB_LEASE_MILLIS SCHOLARCHAIN_CFG_SERVICE_JOB_TTL_SECONDS \
  SCHOLARCHAIN_CFG_SERVICE_CIRCUIT_FAILURE_THRESHOLD SCHOLARCHAIN_CFG_SERVICE_CIRCUIT_OPEN_MILLIS \
  SCHOLARCHAIN_CFG_SERVICE_REQUESTS_PER_MINUTE SCHOLARCHAIN_CFG_SERVICE_JOB_QUEUE_URL \
  SCHOLARCHAIN_CFG_SERVICE_EMBEDDING_QUEUE_URL

# 3. The documented emulator variables (port 9297 only; the safety contract
# accepts no other port and no host other than 127.0.0.1).
export AWS_ENDPOINT_URL="https://127.0.0.1:9297"
export AWS_SHARED_CREDENTIALS_FILE="${HOME}/.aws/emulator/9297/emulator_credentials"
export AWS_CONFIG_FILE="${HOME}/.aws/emulator/9297/config"
export AWS_CA_BUNDLE="${HOME}/.aws/emulator/9297/ca.pem"
export AWS_EC2_METADATA_DISABLED="true"
export AWS_DISABLE_HOST_PREFIX_INJECTION="true"
export AWS_REGION="us-east-1"
export AWS_DEFAULT_REGION="us-east-1"
export SCHOLARCHAIN_EMULATOR_ENDPOINT="https://127.0.0.1:9297"
export SCHOLARCHAIN_CONFIG_FILE="${PWD}/config/emulator.json"

if [ -n "${JAVA_TOOL_OPTIONS:-}" ]; then
  printf 'WARN: env.sh: JAVA_TOOL_OPTIONS is set in this shell; it reaches sbt and every JVM. Unset it unless you set it on purpose (this file never sets it).\n' >&2
fi

# 4. Prompt marker (zsh reads PS1 as PROMPT; a prompt theme may redraw it).
case "${PS1:-}" in
  "[EMULATOR] "*) ;;
  *) PS1="[EMULATOR] ${PS1:-}" ;;
esac

SC_EMU_CREDENTIALS_PRESENT="no (start the emulator first)"
if [ -f "${AWS_SHARED_CREDENTIALS_FILE}" ]; then
  SC_EMU_CREDENTIALS_PRESENT="yes"
fi
printf 'INFO: env.sh: this terminal now targets the AWS Emulator at %s (config %s). Credentials file present: %s\n' \
  "${SCHOLARCHAIN_EMULATOR_ENDPOINT}" "config/emulator.json" "${SC_EMU_CREDENTIALS_PRESENT}"
unset SC_EMU_CREDENTIALS_PRESENT
