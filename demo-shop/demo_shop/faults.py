"""
Fault switches manager for Demo Shop v1.
Implements the 5 independent failure scenarios specified in Issue #64 and D40.
"""

from __future__ import annotations

import datetime
from threading import Lock
from typing import Dict, List

from demo_shop.config import Config
from demo_shop.models import DeployEvent, FaultName, FaultState, FaultsStatusResponse
from demo_shop.telemetry import StructuredLogger, telemetry

SCENARIO_METADATA = {
    FaultName.SLOW_GATEWAY.value: {
        "scenario_id": "SCN-01",
        "service": "payments",
        "description": "Adds 2.0s bounded latency to payment gateway authorization path.",
    },
    FaultName.ERROR_SPIKE.value: {
        "scenario_id": "SCN-02",
        "service": "checkout",
        "description": "Causes simulated database deadlocks and 80% failure rate on checkouts.",
    },
    FaultName.BAD_DEPLOY.value: {
        "scenario_id": "SCN-03",
        "service": "checkout",
        "description": "Simulates deployment of v1.0.1-broken causing immediate checkout failure.",
    },
    FaultName.QUEUE_BACKLOG.value: {
        "scenario_id": "SCN-04",
        "service": "order-fulfillment",
        "description": "Pauses background order queue worker, accumulating backlog.",
    },
    FaultName.EXPIRING_CERTIFICATE.value: {
        "scenario_id": "SCN-05",
        "service": "payments",
        "description": "Drops payment gateway TLS certificate validity to 0 days, emitting SSL warnings.",
    },
}


class FaultManager:
    """Thread-safe manager for the 5 demo-shop fault switches."""

    def __init__(self):
        self._lock = Lock()
        self._switches: Dict[str, bool] = {
            FaultName.SLOW_GATEWAY.value: False,
            FaultName.ERROR_SPIKE.value: False,
            FaultName.BAD_DEPLOY.value: False,
            FaultName.QUEUE_BACKLOG.value: False,
            FaultName.EXPIRING_CERTIFICATE.value: False,
        }
        self._current_version: str = Config.DEFAULT_VERSION
        self._last_deploy_event: DeployEvent | None = None
        self._queue_reference = None  # Injected reference to OrderQueue

    def register_queue(self, queue):
        """Register the OrderQueue instance so fault toggles can pause/unpause it."""
        self._queue_reference = queue

    def is_enabled(self, fault_name: str | FaultName) -> bool:
        name = fault_name.value if isinstance(fault_name, FaultName) else fault_name
        with self._lock:
            return self._switches.get(name, False)

    def get_version(self) -> str:
        with self._lock:
            return self._current_version

    def get_last_deploy_event(self) -> DeployEvent | None:
        with self._lock:
            return self._last_deploy_event

    def enable(self, fault_name: str | FaultName) -> bool:
        name = fault_name.value if isinstance(fault_name, FaultName) else fault_name
        if name not in self._switches:
            raise ValueError(f"Unknown fault switch: '{name}'. Valid switches: {list(self._switches.keys())}")

        with self._lock:
            if self._switches[name]:
                return True
            self._switches[name] = True
            telemetry.set_fault_active(name, True)

            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

            # Side effects specific to fault
            if name == FaultName.BAD_DEPLOY.value:
                prev_version = self._current_version
                self._current_version = Config.BAD_DEPLOY_VERSION
                deploy_event = DeployEvent(
                    service="checkout",
                    version=Config.BAD_DEPLOY_VERSION,
                    previous_version=prev_version,
                    timestamp=now_iso,
                    commit="a1b2c3d",
                    author="ci-bot",
                    status="completed",
                    message=f"Deployed {Config.BAD_DEPLOY_VERSION} to checkout service",
                )
                self._last_deploy_event = deploy_event
                telemetry.deploy_events_total.add(
                    1,
                    {
                        "service": "checkout",
                        "version": Config.BAD_DEPLOY_VERSION,
                        "previous_version": prev_version,
                    },
                )
                StructuredLogger.warning(
                    service="checkout",
                    event="deploy.event",
                    fault_id="SCN-03",
                    details=deploy_event.model_dump(),
                )

            elif name == FaultName.EXPIRING_CERTIFICATE.value:
                telemetry.set_cert_days(0)
                telemetry.ssl_errors_total.add(1, {"host": "gateway.payments.internal"})
                StructuredLogger.error(
                    service="payments",
                    event="ssl.cert_expiring",
                    fault_id="SCN-05",
                    details={
                        "host": "gateway.payments.internal",
                        "days_remaining": 0,
                        "message": "TLS certificate on gateway.payments.internal has expired",
                    },
                )

            elif name == FaultName.QUEUE_BACKLOG.value:
                if self._queue_reference:
                    self._queue_reference.pause()
                StructuredLogger.warning(
                    service="order-fulfillment",
                    event="queue.paused",
                    fault_id="SCN-04",
                    details={"message": "Order fulfillment queue worker paused, backlog will accumulate"},
                )

            StructuredLogger.info(
                service="demo-shop",
                event="fault.activated",
                fault_id=SCENARIO_METADATA.get(name, {}).get("scenario_id"),
                details={"switch": name, "enabled": True},
            )

        return True

    def disable(self, fault_name: str | FaultName) -> bool:
        name = fault_name.value if isinstance(fault_name, FaultName) else fault_name
        if name not in self._switches:
            raise ValueError(f"Unknown fault switch: '{name}'. Valid switches: {list(self._switches.keys())}")

        with self._lock:
            if not self._switches[name]:
                return False
            self._switches[name] = False
            telemetry.set_fault_active(name, False)

            now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()

            # Side effects specific to fault recovery
            if name == FaultName.BAD_DEPLOY.value:
                prev_version = self._current_version
                self._current_version = Config.DEFAULT_VERSION
                rollback_event = DeployEvent(
                    service="checkout",
                    version=Config.DEFAULT_VERSION,
                    previous_version=prev_version,
                    timestamp=now_iso,
                    commit="f9e8d7c",
                    author="lead-sre",
                    status="completed",
                    message=f"Rollback to {Config.DEFAULT_VERSION} completed",
                )
                self._last_deploy_event = rollback_event
                telemetry.deploy_events_total.add(
                    1,
                    {
                        "service": "checkout",
                        "version": Config.DEFAULT_VERSION,
                        "previous_version": prev_version,
                    },
                )
                StructuredLogger.info(
                    service="checkout",
                    event="deploy.event",
                    fault_id="SCN-03",
                    details=rollback_event.model_dump(),
                )

            elif name == FaultName.EXPIRING_CERTIFICATE.value:
                telemetry.set_cert_days(90)
                StructuredLogger.info(
                    service="payments",
                    event="ssl.cert_restored",
                    fault_id="SCN-05",
                    details={
                        "host": "gateway.payments.internal",
                        "days_remaining": 90,
                        "message": "TLS certificate renewed successfully (valid for 90 days)",
                    },
                )

            elif name == FaultName.QUEUE_BACKLOG.value:
                if self._queue_reference:
                    self._queue_reference.resume()
                StructuredLogger.info(
                    service="order-fulfillment",
                    event="queue.resumed",
                    fault_id="SCN-04",
                    details={"message": "Order fulfillment queue worker resumed, draining backlog"},
                )

            StructuredLogger.info(
                service="demo-shop",
                event="fault.deactivated",
                fault_id=SCENARIO_METADATA.get(name, {}).get("scenario_id"),
                details={"switch": name, "enabled": False},
            )

        return False

    def reset(self):
        """Disable all faults and restore healthy baseline."""
        for name in list(self._switches.keys()):
            if self.is_enabled(name):
                self.disable(name)
        StructuredLogger.info(
            service="demo-shop",
            event="faults.reset_all",
            details={"message": "All fault switches disabled, baseline healthy state restored"},
        )

    def get_status(self) -> FaultsStatusResponse:
        with self._lock:
            states = []
            for name, enabled in self._switches.items():
                meta = SCENARIO_METADATA.get(name, {})
                states.append(
                    FaultState(
                        name=name,
                        enabled=enabled,
                        scenario_id=meta.get("scenario_id", ""),
                        service=meta.get("service", ""),
                        description=meta.get("description", ""),
                        details={},
                    )
                )
            return FaultsStatusResponse(switches=dict(self._switches), states=states)


# Global fault manager singleton
fault_manager = FaultManager()
