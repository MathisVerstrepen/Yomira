#!/usr/bin/env bash
# KMK -->
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
export LOCAL_UID=$(id -u)
export LOCAL_GID=$(id -g)
mkdir -p "$repo_root/.local/sfw-screenshots/data"
docker compose -f "$repo_root/tools/screenshots/compose.yaml" up -d
python3 - <<'PY'
import time
import urllib.error
import urllib.request
for attempt in range(60):
    try:
        with urllib.request.urlopen('http://127.0.0.1:4569/api/v1/settings/about', timeout=2) as response:
            if response.status == 200:
                break
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        time.sleep(1)
else:
    raise SystemExit('Suwayomi did not start; inspect docker compose logs')
PY
python3 "$repo_root/tools/screenshots/seed.py" --disposable
# KMK <--
