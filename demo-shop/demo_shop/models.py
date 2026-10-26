"""Pydantic models for Demo Shop v1."""

from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Search models
# ---------------------------------------------------------------------------

class Product(BaseModel):
    id: str = Field(..., examples=["prod-101"])
    name: str = Field(..., examples=["Wireless Noise-Canceling Headphones"])
    category: str = Field(..., examples=["Audio"])
    price: float = Field(..., gt=0, examples=[199.99])
    description: str = Field(..., examples=["High-fidelity over-ear headphones with ANC."])
    in_stock: bool = Field(True, examples=[True])


class SearchResponse(BaseModel):
    query: str
    results: List[Product]
    count: int


# ---------------------------------------------------------------------------
# Payments models
# ---------------------------------------------------------------------------

class PaymentRequest(BaseModel):
    order_id: str = Field(..., min_length=1, examples=["ord-98765"])
    amount: float = Field(..., gt=0, examples=[249.98])
    currency: str = Field("USD", min_length=3, max_length=3, examples=["USD"])
    card_token: str = Field(..., min_length=4, examples=["tok_visa_4242"])


class PaymentResponse(BaseModel):
    transaction_id: str
    order_id: str
    status: str = Field(..., examples=["authorized", "failed", "delayed"])
    amount: float
    currency: str
    latency_ms: float
    message: Optional[str] = None


# ---------------------------------------------------------------------------
# Checkout models
# ---------------------------------------------------------------------------

class CartItem(BaseModel):
    product_id: str = Field(..., examples=["prod-101"])
    quantity: int = Field(1, ge=1, le=100)
    unit_price: float = Field(..., gt=0)


class CheckoutRequest(BaseModel):
    customer_email: str = Field(..., examples=["shopper@example.com"])
    items: List[CartItem] = Field(..., min_length=1)
    payment_method: str = Field("credit_card", examples=["credit_card"])


class CheckoutResponse(BaseModel):
    order_id: str
    status: str = Field(..., examples=["completed", "failed"])
    total_amount: float
    payment: Optional[PaymentResponse] = None
    version: str
    error: Optional[str] = None


# ---------------------------------------------------------------------------
# Fault switches models (#64)
# ---------------------------------------------------------------------------

class FaultName(str, Enum):
    SLOW_GATEWAY = "slow_gateway"
    ERROR_SPIKE = "error_spike"
    BAD_DEPLOY = "bad_deploy"
    QUEUE_BACKLOG = "queue_backlog"
    EXPIRING_CERTIFICATE = "expiring_certificate"


class FaultToggleRequest(BaseModel):
    enabled: bool = Field(..., description="Whether to enable or disable the fault switch")


class FaultState(BaseModel):
    name: str
    enabled: bool
    scenario_id: str
    service: str
    description: str
    details: Dict[str, Any] = Field(default_factory=dict)


class FaultsStatusResponse(BaseModel):
    switches: Dict[str, bool]
    states: List[FaultState]


# ---------------------------------------------------------------------------
# Deploy event model
# ---------------------------------------------------------------------------

class DeployEvent(BaseModel):
    service: str = Field("checkout", examples=["checkout"])
    version: str = Field(..., examples=["v1.0.1-broken"])
    previous_version: str = Field(..., examples=["v1.0.0"])
    timestamp: str = Field(..., description="ISO-8601 UTC timestamp")
    commit: str = Field("a1b2c3d", examples=["a1b2c3d"])
    author: str = Field("ci-bot", examples=["ci-bot"])
    status: str = Field("completed", examples=["completed"])
    message: str = Field(..., examples=["Simulated deployment event"])


# ---------------------------------------------------------------------------
# Traffic generator models
# ---------------------------------------------------------------------------

class TrafficStartRequest(BaseModel):
    rate: float = Field(2.0, gt=0, le=50.0, description="Requests per second")
    duration_seconds: int = Field(30, ge=1, le=300, description="Total run duration (bounded)")
    target_url: str = Field("http://localhost:8001", description="Target base URL")


class TrafficStatusResponse(BaseModel):
    running: bool
    requests_sent: int
    success_count: int
    error_count: int
    duration_seconds: int
    remaining_seconds: float
