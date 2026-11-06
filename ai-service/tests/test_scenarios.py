from pathlib import Path

import yaml


SCENARIOS_FILE = (
    Path(__file__).resolve().parents[1]
    / "data"
    / "scenarios.yaml"
)

REQUIRED_SCENARIO_FIELDS = {
    "scenario_id",
    "switch",
    "service",
    "what_it_breaks",
    "expected_signals",
    "real_cause",
    "ai_first_hypothesis",
}

REQUIRED_SIGNAL_FIELDS = {
    "monitor",
    "metric",
    "log_patterns",
    "deploy_event",
}

EXPECTED_SWITCHES = {
    "slow_gateway",
    "error_spike",
    "bad_deploy",
    "queue_backlog",
    "expiring_certificate",
}


def load_scenarios():
    with SCENARIOS_FILE.open(encoding="utf-8") as file:
        return yaml.safe_load(file)


def test_scenarios_file_exists():
    assert SCENARIOS_FILE.is_file()


def test_scenarios_yaml_structure():
    data = load_scenarios()

    assert isinstance(data, dict)
    assert data.get("schema_version") == 1
    assert isinstance(data.get("scenarios"), list)


def test_all_five_fault_scenarios_are_present():
    data = load_scenarios()
    scenarios = data["scenarios"]

    assert len(scenarios) == 5

    switches = [scenario["switch"] for scenario in scenarios]

    assert set(switches) == EXPECTED_SWITCHES
    assert len(switches) == len(set(switches))


def test_every_scenario_has_required_fields():
    data = load_scenarios()

    for scenario in data["scenarios"]:
        assert REQUIRED_SCENARIO_FIELDS.issubset(scenario.keys())

        for field in REQUIRED_SCENARIO_FIELDS - {"expected_signals"}:
            assert isinstance(scenario[field], str)
            assert scenario[field].strip()

        assert isinstance(scenario["expected_signals"], dict)


def test_every_scenario_has_all_expected_signals():
    data = load_scenarios()

    for scenario in data["scenarios"]:
        signals = scenario["expected_signals"]

        assert isinstance(signals, dict)
        assert REQUIRED_SIGNAL_FIELDS.issubset(signals.keys())

        # monitor and metric are non-empty strings
        for field in ("monitor", "metric"):
            assert isinstance(signals[field], str)
            assert signals[field].strip()

        # log_patterns is a non-empty list of non-empty strings
        assert isinstance(signals["log_patterns"], list)
        assert len(signals["log_patterns"]) > 0
        for pattern in signals["log_patterns"]:
            assert isinstance(pattern, str)
            assert pattern.strip()

        # deploy_event is either null or a non-empty string
        deploy = signals["deploy_event"]
        if deploy is not None:
            assert isinstance(deploy, str)
            assert deploy.strip()
