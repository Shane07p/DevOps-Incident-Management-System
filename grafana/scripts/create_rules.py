"""Creates the demo-shop alert rules (#88) in a Grafana Cloud stack, from alerts/alert-rules.yaml.

Run it in your own terminal, with a service account token (Admin role) in an environment variable:

    PowerShell:  $env:GRAFANA_URL = "https://<stack>.grafana.net"
                 $env:GRAFANA_TOKEN = "<service account token>"
                 python grafana/scripts/create_rules.py

It needs PyYAML (python -m pip install pyyaml). It creates the folder named in the file if needed,
finds the stack's Prometheus data source, and writes each rule group with its evaluation interval.
Each rule sends to the contact point its notification_settings names, so no notification policy
changes. Running it again replaces the same groups. The rules stay editable in the UI, and the
token is never printed. Delete the service account afterwards.
"""
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

import yaml

RULES_FILE = Path(__file__).resolve().parent.parent / "alerts" / "alert-rules.yaml"
GROUPS = yaml.safe_load(RULES_FILE.read_text(encoding="utf-8"))["groups"]
RULE_COUNT = sum(len(group["rules"]) for group in GROUPS)

URL = os.environ.get("GRAFANA_URL", "").rstrip("/")
TOKEN = os.environ.get("GRAFANA_TOKEN", "")
if not URL or not TOKEN:
    sys.exit("Set GRAFANA_URL and GRAFANA_TOKEN first (see the top of this file).")


def call(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(URL + path, data=data, method=method, headers={
        "Authorization": "Bearer " + TOKEN,
        "Content-Type": "application/json",
        "Accept": "application/json",
        # Keeps the rules editable in the Grafana UI.
        "X-Disable-Provenance": "true",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()[:500]


def prometheus_uid():
    status, sources = call("GET", "/api/datasources")
    if status != 200:
        sys.exit(f"Could not list data sources ({status}): {sources}")
    proms = [s for s in sources if s.get("type") == "prometheus"]
    for source in proms:
        if source.get("uid") == "grafanacloud-prom":
            return source["uid"], source["name"]
    for source in proms:
        if source.get("name", "").startswith("grafanacloud-") and source["name"].endswith("-prom"):
            return source["uid"], source["name"]
    names = ", ".join(f'{s.get("name")} ({s.get("uid")})' for s in proms)
    sys.exit(f"No grafanacloud Prometheus data source found. Prometheus sources: {names}")


def folder_uid(title):
    status, folders = call("GET", "/api/folders?limit=1000")
    if status != 200:
        sys.exit(f"Could not list folders ({status}): {folders}")
    for folder in folders:
        if folder.get("title") == title:
            return folder["uid"]
    status, created = call("POST", "/api/folders", {"title": title})
    if status not in (200, 201):
        sys.exit(f"Could not create folder {title!r} ({status}): {created}")
    print(f"Created folder {title!r}")
    return created["uid"]


def query(item, prom_uid):
    model = dict(item["model"], refId=item["refId"])
    if item["datasourceUid"] == "__expr__":
        model["datasource"] = {"type": "__expr__", "uid": "__expr__"}
        return {"refId": item["refId"], "datasourceUid": "__expr__", "relativeTimeRange": {"from": 0, "to": 0}, "model": model}
    model["datasource"] = {"type": "prometheus", "uid": prom_uid}
    return {"refId": item["refId"], "datasourceUid": prom_uid, "relativeTimeRange": item["relativeTimeRange"], "model": model}


def interval_seconds(text):
    return int(text[:-1]) * {"s": 1, "m": 60, "h": 3600}[text[-1]]


def main():
    prom_uid, prom_name = prometheus_uid()
    print(f"Prometheus data source: {prom_name}")
    created = 0
    for group in GROUPS:
        folder = folder_uid(group["folder"])
        rules = []
        for rule in group["rules"]:
            rules.append({
                "uid": rule["uid"],
                "title": rule["title"],
                "folderUID": folder,
                "ruleGroup": group["name"],
                "condition": rule["condition"],
                "data": [query(item, prom_uid) for item in rule["data"]],
                "for": rule["for"],
                "noDataState": rule["noDataState"],
                "execErrState": rule["execErrState"],
                "labels": rule["labels"],
                "annotations": rule["annotations"],
                "notification_settings": rule["notification_settings"],
                "isPaused": False,
            })
        body = {"title": group["name"], "folderUid": folder, "interval": interval_seconds(group["interval"]), "rules": rules}
        status, answer = call("PUT", f"/api/v1/provisioning/folder/{folder}/rule-groups/{group['name']}", body)
        if status == 200:
            created += len(rules)
            print(f"OK   {group['name']}: " + ", ".join(r["title"] for r in rules))
        else:
            print(f"FAIL {group['name']} ({status}): {answer}")
    print(f"{created} of {RULE_COUNT} rules in place.")
    sys.exit(0 if created == RULE_COUNT else 1)


if __name__ == "__main__":
    main()
