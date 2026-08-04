import sys
import json
import os
import datetime

script_dir = os.path.dirname(os.path.abspath(__file__))
repo_root = os.path.abspath(os.path.join(script_dir, "..", ".."))
artifact_dir = os.path.join(repo_root, ".omc", "e2e-artifacts")
os.makedirs(artifact_dir, exist_ok=True)
summary_file = os.path.join(artifact_dir, "summary.json")

overall_status = sys.argv[1] if len(sys.argv) > 1 else "FAILED"
unit_only = (sys.argv[2].lower() == "true") if len(sys.argv) > 2 else True
total_tiers = int(sys.argv[3]) if len(sys.argv) > 3 else 4
passed_tiers = int(sys.argv[4]) if len(sys.argv) > 4 else 0
failed_tiers = int(sys.argv[5]) if len(sys.argv) > 5 else 4
duration_seconds = int(sys.argv[6]) if len(sys.argv) > 6 else 0
timestamp = sys.argv[7] if len(sys.argv) > 7 and sys.argv[7] else ""
if not timestamp:
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

tier_details = {}
for arg in sys.argv[8:]:
    if "=" in arg:
        k, v = arg.split("=", 1)
        tier_details[k] = v

data = {
    "suite": "Master E2E Test Suite",
    "overall_status": overall_status,
    "unit_only": unit_only,
    "total_tiers": total_tiers,
    "passed_tiers": passed_tiers,
    "failed_tiers": failed_tiers,
    "duration_seconds": duration_seconds,
    "timestamp": timestamp,
    "tier_details": tier_details
}

with open(summary_file, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2)
    f.flush()
    os.fsync(f.fileno())

print(f"[write-summary.py] Saved master summary to {summary_file}")
