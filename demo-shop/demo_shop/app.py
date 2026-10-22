"""
FastAPI application for Demo Shop v1.
Exposes payments, checkout, search, fault control switches, and traffic generator.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
import time
import uuid
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Query, Request, Response, status
from fastapi.responses import JSONResponse

from demo_shop.config import Config
from demo_shop.faults import fault_manager
from demo_shop.models import (
    CheckoutRequest,
    CheckoutResponse,
    DeployEvent,
    FaultToggleRequest,
    FaultsStatusResponse,
    PaymentRequest,
    PaymentResponse,
    Product,
    SearchResponse,
    TrafficStartRequest,
    TrafficStatusResponse,
)
from demo_shop.queue import order_queue
from demo_shop.services.checkout import checkout_service
from demo_shop.services.payments import payment_service
from demo_shop.services.search import search_service
from demo_shop.telemetry import StructuredLogger, telemetry
from demo_shop.traffic import traffic_generator


# Register queue with fault manager at import time
fault_manager.register_queue(order_queue)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: ensure queue worker is running
    order_queue.start()
    StructuredLogger.info(
        service="demo-shop",
        event="app.started",
        details={
            "version": Config.DEFAULT_VERSION,
            "port": Config.PORT,
            "remote_telemetry": Config.is_remote_telemetry_enabled(),
        },
    )
    try:
        yield
    finally:
        # Graceful shutdown
        await traffic_generator.stop()
        await order_queue.stop()
        telemetry.shutdown()
        StructuredLogger.info(
            service="demo-shop",
            event="app.shutdown",
            details={"message": "Demo shop shutdown cleanly"},
        )


app = FastAPI(
    title="Demo Shop v1",
    description="Simulated production retail app with OpenTelemetry and 5 fault switches (Issue #76)",
    version=Config.DEFAULT_VERSION,
    lifespan=lifespan,
)


# ---------------------------------------------------------------------------
# Middleware: Request Correlation ID, Telemetry Metrics & Access Logs
# ---------------------------------------------------------------------------

@app.middleware("http")
async def telemetry_middleware(request: Request, call_next):
    req_id = request.headers.get("X-Request-Id", f"req-{uuid.uuid4().hex[:12]}")
    request.state.request_id = req_id
    start_time = time.perf_counter()

    path = request.url.path
    method = request.method
    version = fault_manager.get_version()

    try:
        response = await call_next(request)
        status_code = response.status_code
    except HTTPException as exc:
        status_code = exc.status_code
        response = JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})
    except Exception as exc:
        status_code = 500
        StructuredLogger.error(
            service="demo-shop",
            event="http.unhandled_exception",
            request_id=req_id,
            details={"path": path, "method": method, "error": str(exc)},
        )
        response = JSONResponse(status_code=500, content={"detail": "Internal Server Error"})

    duration = time.perf_counter() - start_time

    # Record OpenTelemetry metrics
    # Normalise high-cardinality paths (strip numeric/uuid query params)
    endpoint_label = path.split("?")[0]
    telemetry.http_requests_total.add(
        1,
        {
            "service": "demo-shop",
            "method": method,
            "endpoint": endpoint_label,
            "status": str(status_code),
            "version": version,
        },
    )
    telemetry.http_request_duration_seconds.record(
        duration,
        {"service": "demo-shop", "endpoint": endpoint_label},
    )

    response.headers["X-Request-Id"] = req_id
    response.headers["X-Service-Version"] = version

    # Structured access log
    StructuredLogger.info(
        service="demo-shop",
        event="http.access",
        request_id=req_id,
        details={
            "method": method,
            "path": path,
            "status": status_code,
            "duration_ms": round(duration * 1000.0, 2),
            "version": version,
        },
    )

    return response


# ---------------------------------------------------------------------------
# Health and Readiness Endpoints
# ---------------------------------------------------------------------------

@app.get("/health", summary="Instant liveness check")
def health() -> dict:
    return {
        "status": "ok",
        "service": "demo-shop",
        "version": fault_manager.get_version(),
    }


@app.get("/ready", summary="Readiness check")
def ready() -> dict:
    return {
        "status": "ok",
        "service": "demo-shop",
        "version": fault_manager.get_version(),
        "queue_depth": order_queue.size,
        "telemetry_remote": Config.is_remote_telemetry_enabled(),
    }


# ---------------------------------------------------------------------------
# Search Endpoints
# ---------------------------------------------------------------------------

@app.get("/products", response_model=List[Product], summary="List all products in catalog")
def list_products() -> List[Product]:
    return search_service.get_all_products()


@app.get("/products/{product_id}", response_model=Product, summary="Get single product by ID")
def get_product(product_id: str) -> Product:
    prod = search_service.get_by_id(product_id)
    if not prod:
        raise HTTPException(status_code=404, detail=f"Product '{product_id}' not found")
    return prod


@app.get("/search", response_model=SearchResponse, summary="Search product catalog")
def search(request: Request, q: str = Query("", description="Search term")) -> SearchResponse:
    req_id = getattr(request.state, "request_id", None)
    return search_service.search(q, request_id=req_id)


# ---------------------------------------------------------------------------
# Payments Endpoints
# ---------------------------------------------------------------------------

@app.post("/payments/authorize", response_model=PaymentResponse, summary="Authorize payment")
async def authorize_payment(request: Request, payment_req: PaymentRequest) -> PaymentResponse:
    req_id = getattr(request.state, "request_id", None)
    return await payment_service.authorize(payment_req, request_id=req_id)


# ---------------------------------------------------------------------------
# Checkout Endpoints
# ---------------------------------------------------------------------------

@app.post("/checkout", response_model=CheckoutResponse, summary="Process checkout flow")
async def checkout(request: Request, checkout_req: CheckoutRequest) -> CheckoutResponse:
    req_id = getattr(request.state, "request_id", None)
    return await checkout_service.process_checkout(checkout_req, request_id=req_id)


# ---------------------------------------------------------------------------
# Fault Switches Control Endpoints (SCN-01 to SCN-05)
# ---------------------------------------------------------------------------

@app.get("/faults", response_model=FaultsStatusResponse, summary="List status of all 5 fault switches")
def list_faults() -> FaultsStatusResponse:
    return fault_manager.get_status()


@app.post("/faults/reset", summary="Disable all faults and restore healthy baseline")
def reset_faults() -> dict:
    fault_manager.reset()
    return {
        "status": "ok",
        "message": "All 5 fault switches disabled, healthy baseline restored",
        "version": fault_manager.get_version(),
    }


@app.get("/faults/deploy-event", response_model=Optional[DeployEvent], summary="Get last deploy event")
def get_last_deploy_event() -> Optional[DeployEvent]:
    return fault_manager.get_last_deploy_event()


@app.post("/faults/{fault_name}", summary="Enable or disable a specific fault switch")
def toggle_fault(fault_name: str, payload: FaultToggleRequest) -> dict:
    try:
        if payload.enabled:
            fault_manager.enable(fault_name)
        else:
            fault_manager.disable(fault_name)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    return {
        "fault": fault_name,
        "enabled": payload.enabled,
        "version": fault_manager.get_version(),
    }


# ---------------------------------------------------------------------------
# Traffic Generator Endpoints
# ---------------------------------------------------------------------------

@app.post("/traffic/start", summary="Start bounded traffic generation")
def start_traffic(payload: TrafficStartRequest) -> dict:
    traffic_generator.start(
        rate=payload.rate,
        duration_seconds=payload.duration_seconds,
        target_url=payload.target_url,
    )
    return {
        "status": "started",
        "rate": payload.rate,
        "duration_seconds": payload.duration_seconds,
        "target_url": payload.target_url,
    }


@app.post("/traffic/stop", summary="Stop traffic generator cleanly")
async def stop_traffic() -> dict:
    await traffic_generator.stop()
    return {"status": "stopped"}


@app.get("/traffic/status", response_model=TrafficStatusResponse, summary="Get traffic generator progress")
def traffic_status() -> TrafficStatusResponse:
    return traffic_generator.get_status()
