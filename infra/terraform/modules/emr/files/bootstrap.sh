#!/usr/bin/env bash
# ScholarChain EMR bootstrap action (uploaded by Terraform to
# s3://<data bucket>/artifacts/emr/bootstrap/; referenced by the pipeline state
# machine's createCluster call). It runs on every node before applications
# start. It only records facts in the bootstrap log (logs/emr/ in the data
# lake); it installs nothing and reads no secret. Exit code 0 on success.
set -euo pipefail

echo "STEP: scholarchain-bootstrap start"
echo "STEP: scholarchain-bootstrap host-role $(if grep -q '"isMaster": *true' /mnt/var/lib/info/instance.json 2>/dev/null; then echo primary; else echo core; fi)"
if command -v java >/dev/null 2>&1; then
  echo "STEP: scholarchain-bootstrap java $(java -version 2>&1 | head -n 1)"
else
  echo "STEP: scholarchain-bootstrap java not-found"
fi
echo "STEP: scholarchain-bootstrap done"
exit 0
