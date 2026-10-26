"""Shared fixtures for Demo Shop tests."""

import pytest
from fastapi.testclient import TestClient

from demo_shop.app import app
from demo_shop.faults import fault_manager
from demo_shop.queue import order_queue


@pytest.fixture(autouse=True)
def reset_state():
    """Ensure every test runs with clean baseline fault switches and empty queue."""
    fault_manager.reset()
    yield
    fault_manager.reset()


@pytest.fixture
def client():
    return TestClient(app)
