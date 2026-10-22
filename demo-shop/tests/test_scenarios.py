"""
Validates consistency of scenario definitions (Issue #64).
Ensures ai-service/data/scenarios.yaml and docs/plans/demo-shop-scenarios.md
have all 5 required scenarios with all necessary schema fields.
"""

from pathlib import Path
import pytest
import yaml

from demo_shop.faults import fault_manager


def test_scenarios_yaml_has_all_five_scenarios_with_required_fields():
    # Resolve path to ai-service/data/scenarios.yaml
    repo_root = Path(__file__).resolve().parent.parent.parent
    yaml_path = repo_root / "ai-service" / "data" / "scenarios.yaml"

    assert yaml_path.exists(), f"Missing scenarios.yaml at {yaml_path}"

    with open(yaml_path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)

    assert "scenarios" in data
    scenarios = data["scenarios"]
    assert len(scenarios) == 5

    required_fields = {
        "id",
        "name",
        "switch",
        "service",
        "what_it_breaks",
        "expected_signals",
        "root_cause",
        "ai_hypothesis",
    }

    switches_found = set()
    for s in scenarios:
        for field in required_fields:
            assert field in s, f"Scenario {s.get('id')} missing field: {field}"

        assert isinstance(s["expected_signals"], dict)
        assert "monitor" in s["expected_signals"]
        assert "metrics" in s["expected_signals"]
        assert "log_pattern" in s["expected_signals"]

        switches_found.add(s["switch"])

    expected_switches = {
        "slow_gateway",
        "error_spike",
        "bad_deploy",
        "queue_backlog",
        "expiring_certificate",
    }
    assert switches_found == expected_switches

    # Ensure all switches in YAML match the implementation switches in fault_manager
    manager_switches = set(fault_manager.get_status().switches.keys())
    assert switches_found == manager_switches


def test_demo_shop_scenarios_markdown_exists_and_contains_switches():
    repo_root = Path(__file__).resolve().parent.parent.parent
    md_path = repo_root / "docs" / "plans" / "demo-shop-scenarios.md"

    assert md_path.exists(), f"Missing {md_path}"

    with open(md_path, "r", encoding="utf-8") as f:
        content = f.read()

    for switch in ("slow_gateway", "error_spike", "bad_deploy", "queue_backlog", "expiring_certificate"):
        assert switch in content, f"Switch '{switch}' missing from markdown scenarios table"
