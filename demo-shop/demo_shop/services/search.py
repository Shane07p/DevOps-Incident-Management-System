"""
Search service component for Demo Shop v1.
Provides deterministic product catalog and search operations.
"""

from __future__ import annotations

import time
from typing import List, Optional

from demo_shop.models import Product, SearchResponse
from demo_shop.telemetry import StructuredLogger

CATALOG: List[Product] = [
    Product(
        id="prod-101",
        name="Wireless Noise-Canceling Headphones",
        category="Audio",
        price=199.99,
        description="High-fidelity over-ear headphones with active noise cancelation.",
    ),
    Product(
        id="prod-102",
        name="Ergonomic Mechanical Keyboard",
        category="Computing",
        price=129.50,
        description="RGB backlit tactile mechanical keyboard with wrist rest.",
    ),
    Product(
        id="prod-103",
        name="Ultra-Wide 4K Monitor 34-inch",
        category="Computing",
        price=499.00,
        description="Curved IPS 144Hz panel with USB-C 90W power delivery.",
    ),
    Product(
        id="prod-104",
        name="Precision Optical Gaming Mouse",
        category="Computing",
        price=59.99,
        description="Lightweight 26K DPI optical sensor with braided cable.",
    ),
    Product(
        id="prod-105",
        name="USB-C Multiport Hub 7-in-1",
        category="Accessories",
        price=39.99,
        description="Aluminum hub with HDMI 4K, SD card slot, and 100W PD passthrough.",
    ),
    Product(
        id="prod-106",
        name="Studio Condenser Microphone",
        category="Audio",
        price=149.00,
        description="Cardioid studio USB microphone for streaming and podcasting.",
    ),
    Product(
        id="prod-107",
        name="Braided USB-C to USB-C Cable (2m)",
        category="Accessories",
        price=14.99,
        description="Heavy-duty 240W fast-charging nylon braided cable.",
    ),
    Product(
        id="prod-108",
        name="Smart Desk LED Lamp with Wireless Charger",
        category="Accessories",
        price=45.00,
        description="Dimmable touch-control lamp with 15W Qi charging base.",
    ),
    Product(
        id="prod-109",
        name="True Wireless Earbuds with ANC",
        category="Audio",
        price=89.99,
        description="IPX5 water-resistant in-ear buds with 30-hour battery case.",
    ),
    Product(
        id="prod-110",
        name="Alloy Laptop Stand Foldable",
        category="Accessories",
        price=29.99,
        description="Portable ergonomic riser for laptops up to 16 inches.",
    ),
]


class SearchService:
    """Deterministic product and search engine for demo shop."""

    @staticmethod
    def get_all_products() -> List[Product]:
        return list(CATALOG)

    @staticmethod
    def get_by_id(product_id: str) -> Optional[Product]:
        for prod in CATALOG:
            if prod.id == product_id:
                return prod
        return None

    @staticmethod
    def search(query: str, request_id: Optional[str] = None) -> SearchResponse:
        start_time = time.perf_counter()
        q_norm = query.strip().lower()

        if not q_norm:
            results = list(CATALOG)
        else:
            results = [
                p for p in CATALOG
                if q_norm in p.name.lower() or q_norm in p.category.lower() or q_norm in p.description.lower()
            ]

        latency_ms = (time.perf_counter() - start_time) * 1000.0

        StructuredLogger.info(
            service="search",
            event="search.executed",
            request_id=request_id,
            details={
                "query": q_norm,
                "matches": len(results),
                "latency_ms": round(latency_ms, 2),
            },
        )

        return SearchResponse(query=query, results=results, count=len(results))


search_service = SearchService()
