#!/usr/bin/env bash
# ScholarChain dataset validation command (dataset-engineer primary gate).
# Runs: bash -n on every datasets/scripts/*.sh, the manifest/sample validator,
# and the dataset unit tests. Offline: no internet access (unit tests use a 127.0.0.1
# test server only), no AWS.
# Usage: bash datasets/scripts/validate.sh
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "== bash -n"
for s in "$here"/*.sh; do
  bash -n "$s"
  echo "ok: $(basename "$s")"
done

echo "== validate_datasets.py"
python3 "$here/validate_datasets.py"

echo "== test_datasets.py"
python3 "$here/test_datasets.py" -v
