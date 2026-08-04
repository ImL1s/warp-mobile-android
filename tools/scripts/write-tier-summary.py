import sys
import json
import os
import datetime

tier_num = int(sys.argv[1]) if len(sys.argv) > 1 else 4
tier_name = sys.argv[2] if len(sys.argv) > 2 else "Real-World Workload"
status = sys.argv[3] if len(sys.argv) > 3 else "PASSED"
total = int(sys.argv[4]) if len(sys.argv) > 4 else 1
passed = int(sys.argv[5]) if len(sys.argv) > 5 else 1
failed = int(sys.argv[6]) if len(sys.argv) > 6 else 0
duration = int(sys.argv[7]) if len(sys.argv) > 7 else 0
timestamp = sys.argv[8] if len(sys.argv) > 8 and sys.argv[8] else ""
if not timestamp:
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

script_dir = os.path.dirname(os.path.abspath(__file__))
repo_root = os.path.abspath(os.path.join(script_dir, "..", ".."))
artifact_dir = os.path.join(repo_root, ".omc", "e2e-artifacts")
os.makedirs(artifact_dir, exist_ok=True)
summary_file = os.path.join(artifact_dir, f"tier{tier_num}-summary.json")

data = {
    "tier": tier_num,
    "name": tier_name,
    "status": status,
    "total": total,
    "passed": passed,
    "failed": failed,
    "duration_seconds": duration,
    "timestamp": timestamp
}

with open(summary_file, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2)
    f.flush()
    os.fsync(f.fileno())

print(f"[write-tier-summary.py] Saved tier {tier_num} summary to {summary_file}")
