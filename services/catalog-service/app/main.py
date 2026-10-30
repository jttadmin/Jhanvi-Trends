"""catalog-service: product listing, search, categories and product images (MongoDB)."""
import logging
import os
import re
import time
from contextlib import asynccontextmanager
from html import escape
from typing import Optional

from fastapi import FastAPI, HTTPException, Query, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, generate_latest
from pymongo import ASCENDING, DESCENDING, MongoClient
from pymongo.errors import PyMongoError

from .obs import log, metrics_middleware, setup_logging
from .seed import CATEGORIES, PRODUCTS

logger = setup_logging()

client = MongoClient(os.getenv("MONGODB_URI", "mongodb://localhost:27017"), serverSelectionTimeoutMS=3000,
                     appname="catalog-service")
products = client[os.getenv("MONGODB_DATABASE", "catalog")]["products"]

SEARCHES = Counter("catalog_searches_total", "Product searches", ["has_results"])

SORTS = {
    "price_asc": [("price", ASCENDING)],
    "price_desc": [("price", DESCENDING)],
    "rating": [("rating", DESCENDING), ("reviewCount", DESCENDING)],
    "": [("reviewCount", DESCENDING)],
}
FIELDS = ["name", "category", "brand", "price", "mrp", "rating", "reviewCount", "icon", "features", "description"]


def migrate() -> None:
    for attempt in range(1, 61):
        try:
            for p in PRODUCTS:
                doc = dict(zip(FIELDS, p[1:]))
                products.update_one({"_id": p[0]}, {"$setOnInsert": doc}, upsert=True)
            products.create_index([("category", ASCENDING)])
            products.create_index([("reviewCount", DESCENDING)])
            log(logger, logging.INFO, "database ready", products=len(PRODUCTS))
            return
        except PyMongoError as exc:
            log(logger, logging.WARNING, "database not ready, retrying", attempt=attempt, error=str(exc))
            time.sleep(min(attempt, 10))
    raise RuntimeError("database never became ready")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    migrate()
    yield
    client.close()


app = FastAPI(title="catalog-service", lifespan=lifespan)
app.middleware("http")(metrics_middleware(logger))


def to_api(doc: dict) -> dict:
    return {
        "id": doc["_id"],
        "name": doc["name"],
        "category": doc["category"],
        "brand": doc["brand"],
        "price": float(doc["price"]),
        "mrp": float(doc["mrp"]),
        "rating": float(doc["rating"]),
        "reviewCount": doc["reviewCount"],
        "features": doc.get("features", []),
        "description": doc.get("description", ""),
        # Served by this service (proxied by the frontend under /api/images).
        "imageUrl": f"/api/images/{doc['_id']}.svg",
    }


@app.get("/products")
def list_products(
    q: Optional[str] = None,
    category: Optional[str] = None,
    ids: Optional[str] = None,
    sort: str = "",
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
):
    query: dict = {}
    if q:
        rx = {"$regex": re.escape(q), "$options": "i"}
        query["$or"] = [{"name": rx}, {"brand": rx}, {"category": rx}, {"description": rx}]
    if category:
        query["category"] = category
    if ids:
        try:
            query["_id"] = {"$in": [int(i) for i in ids.split(",") if i.strip()]}
        except ValueError as exc:
            raise HTTPException(400, "ids must be comma-separated integers") from exc
    order = SORTS.get(sort, SORTS[""]) + [("_id", ASCENDING)]
    total = products.count_documents(query)
    docs = list(products.find(query).sort(order).skip(offset).limit(limit))
    if q:
        SEARCHES.labels("true" if total else "false").inc()
        log(logger, logging.INFO, "product search", query=q, results=total)
    return {"items": [to_api(d) for d in docs], "total": total}


@app.get("/products/{product_id}")
def get_product(product_id: int):
    doc = products.find_one({"_id": product_id})
    if not doc:
        raise HTTPException(404, "product not found")
    return to_api(doc)


@app.get("/categories")
def categories():
    rows = products.aggregate([{"$group": {"_id": "$category", "n": {"$sum": 1}}}, {"$sort": {"_id": 1}}])
    return [{"name": r["_id"], "count": r["n"]} for r in rows]


@app.get("/images/{product_id}.svg")
def image(product_id: int):
    """Generated product artwork so the demo needs no external image hosting."""
    row = products.find_one({"_id": product_id}, {"name": 1, "brand": 1, "category": 1, "icon": 1})
    if not row:
        raise HTTPException(404, "product not found")
    c1, c2 = CATEGORIES.get(row["category"], ("#475467", "#1d2939"))
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 600">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient></defs>
<rect width="600" height="600" fill="#f4f6f8"/>
<circle cx="300" cy="270" r="190" fill="url(#g)"/>
<circle cx="440" cy="120" r="40" fill="#5eead4" opacity=".85"/>
<text x="300" y="330" font-size="190" text-anchor="middle">{row['icon']}</text>
<text x="300" y="530" font-size="34" font-family="Segoe UI, Arial, sans-serif" font-weight="700" fill="#1d2939" text-anchor="middle">{escape(row['brand'])}</text>
</svg>"""
    return Response(svg, media_type="image/svg+xml", headers={"Cache-Control": "public, max-age=86400"})


@app.get("/healthz")
def healthz():
    return {"status": "ok"}


@app.get("/readyz")
def readyz():
    try:
        client.admin.command("ping")
        return {"status": "ready"}
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(503, f"database unavailable: {exc}") from exc


@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
