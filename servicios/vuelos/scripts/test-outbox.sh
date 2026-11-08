#!/usr/bin/env bash
set -euo pipefail
export VUELOS_RABBIT_INTEGRATION=true
bash "$(dirname "$(realpath "${BASH_SOURCE[0]}")")/test-postgres.sh"
