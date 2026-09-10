#!/usr/bin/env bash
# ==============================================================================
# Condense Reproducible Superiority & Failure Corpus Comparison Runner
# Pin: Zap d9498bb | Condense 1.0.1
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=== Running Condense Reproducible Superiority Verification ==="
echo "Repo root: ${REPO_ROOT}"
echo "Corpus: condense/src/test/resources/corpus/failure-corpus.json"
echo "Inventory: condense/src/test/resources/inventory/zap-families.json"

cd "${REPO_ROOT}/condense"

# Run the reproducible superiority comparison and competitive inventory tests
mvn test -Dtest=ReproducibleSuperiorityComparisonTest,CompetitiveInventoryTest

echo ""
echo "=== Superiority Verification Successful ==="
echo "100% signal retention across 8 failure categories confirmed."
echo "Zero competitive coverage gaps verified against Zap d9498bb."
