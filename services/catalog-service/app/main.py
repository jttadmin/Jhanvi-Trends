"""catalog-service: product listing, search, categories, product images and product admin (MongoDB).

Admin endpoints (create/update/delete products, upload photos) are only reachable inside the
cluster; the frontend BFF checks the caller's admin role before forwarding to them.
"""
import logging
import os
import re
import time
from contextlib import asynccontextmanager
from html import escape
from typing import List, Optional

import gridfs
from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, Counter, generate_latest
from pydantic import BaseModel, Field, field_validator, model_validator
from pymongo import ASCENDING, DESCENDING, MongoClient
from pymongo.errors import PyMongoError

from .obs import log, metrics_middleware, setup_logging
from .seed import CATEGORIES, PRODUCTS

logger = setup_logging()

client = MongoClient(os.getenv("MONGODB_URI", "mongodb://localhost:27017"), serverSelectionTimeoutMS=3000,
                     appname="catalog-service")
db = client[os.getenv("MONGODB_DATABASE", "catalog")]
products = db["products"]
images = gridfs.GridFS(db, collection="images")

SEARCHES = Counter("catalog_searches_total", "Product searches", ["has_results"])
ADMIN_CHANGES = Counter("catalog_admin_changes_total", "Catalog changes made from the admin panel", ["action"])

SORTS = {
    "price_asc": [("price", ASCENDING)],
    "price_desc": [("price", DESCENDING)],
    "rating": [("rating", DESCENDING), ("reviewCount", DESCENDING)],
    "newest": [("_id", DESCENDING)],
    "": [("reviewCount", DESCENDING)],
}
FIELDS = ["name", "category", "brand", "price", "mrp", "rating", "reviewCount", "icon", "features", "description"]
IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp", "image/gif"}
MAX_IMAGE_BYTES = 3 * 1024 * 1024
DEFAULT_ICON = "🛍️"


def migrate() -> None:
    for attempt in range(1, 61):
        try:
            # Seed the demo catalog only into an empty database, so products the admin
            # deletes or edits are never brought back on the next restart.
            if products.estimated_document_count() == 0:
                now = time.time()
                for p in PRODUCTS:
                    doc = dict(zip(FIELDS, p[1:]), active=True, updatedAt=now)
                    products.update_one({"_id": p[0]}, {"$setOnInsert": doc}, upsert=True)
                log(logger, logging.INFO, "seeded demo catalog", products=len(PRODUCTS))
            products.create_index([("category", ASCENDING)])
            products.create_index([("reviewCount", DESCENDING)])
            log(logger, logging.INFO, "database ready", products=products.estimated_document_count())
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


@app.exception_handler(RequestValidationError)
async def validation_error(_request: Request, exc: RequestValidationError):
    # One readable message for the admin form, e.g. "price: Input should be greater than 0".
    parts = []
    for e in exc.errors():
        field = ".".join(str(x) for x in e.get("loc", []) if x not in ("body", "query", "path"))
        msg = str(e.get("msg", "invalid value")).removeprefix("Value error, ")
        parts.append(f"{field}: {msg}" if field else msg)
    return JSONResponse(status_code=422, content={"error": "; ".join(parts) or "invalid request"})


def to_api(doc: dict) -> dict:
    pid = doc["_id"]
    version = doc.get("imageVersion")
    return {
        "id": pid,
        "name": doc["name"],
        "category": doc["category"],
        "brand": doc["brand"],
        "price": float(doc["price"]),
        "mrp": float(doc["mrp"]),
        "rating": float(doc.get("rating", 0)),
        "reviewCount": int(doc.get("reviewCount", 0)),
        "features": doc.get("features", []),
        "description": doc.get("description", ""),
        "active": doc.get("active", True),
        "hasPhoto": bool(version),
        # Served by this service (proxied by the frontend under /api/images).
        "imageUrl": f"/api/images/{pid}?v={version}" if version else f"/api/images/{pid}.svg",
    }


def active_filter(include_inactive: bool) -> dict:
    return {} if include_inactive else {"active": {"$ne": False}}


@app.get("/products")
def list_products(
    q: Optional[str] = None,
    category: Optional[str] = None,
    ids: Optional[str] = None,
    sort: str = "",
    limit: int = Query(20, ge=1, le=200),
    offset: int = Query(0, ge=0),
    includeInactive: bool = False,
):
    query: dict = active_filter(includeInactive)
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
def get_product(product_id: int, includeInactive: bool = False):
    doc = products.find_one({"_id": product_id, **active_filter(includeInactive)})
    if not doc:
        raise HTTPException(404, "product not found")
    return to_api(doc)


@app.get("/categories")
def categories(includeInactive: bool = False):
    rows = products.aggregate([
        {"$match": active_filter(includeInactive)},
        {"$group": {"_id": "$category", "n": {"$sum": 1}}},
        {"$sort": {"_id": 1}},
    ])
    return [{"name": r["_id"], "count": r["n"]} for r in rows]


# ---------- admin: products ----------

class ProductIn(BaseModel):
    name: str = Field(min_length=2, max_length=200)
    category: str = Field(min_length=2, max_length=60)
    brand: str = Field(min_length=1, max_length=60)
    price: float = Field(gt=0, le=10_000_000)
    mrp: float = Field(gt=0, le=10_000_000)
    description: str = Field(default="", max_length=3000)
    features: List[str] = Field(default_factory=list, max_length=12)
    active: bool = True
    icon: Optional[str] = Field(default=None, max_length=8)

    @field_validator("name", "category", "brand", "description")
    @classmethod
    def strip(cls, v: str) -> str:
        return v.strip()

    @field_validator("features")
    @classmethod
    def clean_features(cls, v: List[str]) -> List[str]:
        return [f.strip()[:200] for f in v if f and f.strip()]

    @model_validator(mode="after")
    def mrp_not_below_price(self):
        if self.mrp < self.price:
            raise ValueError("MRP cannot be lower than the selling price")
        return self


def _doc_from(body: ProductIn) -> dict:
    doc = body.model_dump()
    doc["icon"] = doc.get("icon") or DEFAULT_ICON
    doc["updatedAt"] = time.time()
    return doc


@app.post("/products", status_code=201)
def create_product(body: ProductIn):
    last = products.find_one(sort=[("_id", DESCENDING)], projection={"_id": 1})
    new_id = (last["_id"] + 1) if last else 1
    doc = {"_id": new_id, **_doc_from(body), "rating": 0.0, "reviewCount": 0}
    products.insert_one(doc)
    ADMIN_CHANGES.labels("create").inc()
    log(logger, logging.INFO, "product created", productId=new_id, name=body.name)
    return to_api(doc)


@app.put("/products/{product_id}")
def update_product(product_id: int, body: ProductIn):
    existing = products.find_one({"_id": product_id})
    if not existing:
        raise HTTPException(404, "product not found")
    update = _doc_from(body)
    if not body.icon:
        update["icon"] = existing.get("icon", DEFAULT_ICON)
    products.update_one({"_id": product_id}, {"$set": update})
    ADMIN_CHANGES.labels("update").inc()
    log(logger, logging.INFO, "product updated", productId=product_id, active=body.active)
    return to_api(products.find_one({"_id": product_id}))


@app.delete("/products/{product_id}", status_code=204)
def delete_product(product_id: int):
    result = products.delete_one({"_id": product_id})
    if result.deleted_count == 0:
        raise HTTPException(404, "product not found")
    _delete_photo_files(product_id)
    ADMIN_CHANGES.labels("delete").inc()
    log(logger, logging.INFO, "product deleted", productId=product_id)
    return Response(status_code=204)


# ---------- images ----------

def _delete_photo_files(product_id: int) -> None:
    for f in images.find({"filename": str(product_id)}):
        images.delete(f._id)


@app.put("/products/{product_id}/image")
async def upload_image(product_id: int, request: Request):
    if not products.find_one({"_id": product_id}, {"_id": 1}):
        raise HTTPException(404, "product not found")
    content_type = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if content_type not in IMAGE_TYPES:
        raise HTTPException(415, "photo must be a JPEG, PNG, WebP or GIF image")
    data = await request.body()
    if not data:
        raise HTTPException(400, "empty upload")
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(413, "photo must be 3 MB or smaller")
    _delete_photo_files(product_id)
    images.put(data, filename=str(product_id), contentType=content_type)
    version = int(time.time() * 1000)
    products.update_one({"_id": product_id}, {"$set": {"imageVersion": version, "updatedAt": time.time()}})
    ADMIN_CHANGES.labels("photo_upload").inc()
    log(logger, logging.INFO, "product photo uploaded", productId=product_id, bytes=len(data), type=content_type)
    return to_api(products.find_one({"_id": product_id}))


@app.delete("/products/{product_id}/image")
def delete_image(product_id: int):
    if not products.find_one({"_id": product_id}, {"_id": 1}):
        raise HTTPException(404, "product not found")
    _delete_photo_files(product_id)
    products.update_one({"_id": product_id}, {"$unset": {"imageVersion": ""}, "$set": {"updatedAt": time.time()}})
    ADMIN_CHANGES.labels("photo_delete").inc()
    return to_api(products.find_one({"_id": product_id}))


def _svg(product_id: int) -> Response:
    """Generated product artwork, used when no photo has been uploaded."""
    row = products.find_one({"_id": product_id}, {"name": 1, "brand": 1, "category": 1, "icon": 1})
    if not row:
        raise HTTPException(404, "product not found")
    c1, c2 = CATEGORIES.get(row["category"], ("#475467", "#1d2939"))
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 600">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient></defs>
<rect width="600" height="600" fill="#f4f6f8"/>
<circle cx="300" cy="270" r="190" fill="url(#g)"/>
<circle cx="440" cy="120" r="40" fill="#5eead4" opacity=".85"/>
<text x="300" y="330" font-size="190" text-anchor="middle">{escape(row.get('icon') or DEFAULT_ICON)}</text>
<text x="300" y="530" font-size="34" font-family="Segoe UI, Arial, sans-serif" font-weight="700" fill="#1d2939" text-anchor="middle">{escape(row['brand'])}</text>
</svg>"""
    return Response(svg, media_type="image/svg+xml", headers={"Cache-Control": "public, max-age=3600"})


@app.get("/images/{product_id}.svg")
def image_svg(product_id: int):
    return _svg(product_id)


@app.get("/images/{product_id}")
def image(product_id: int):
    """The uploaded photo if there is one, otherwise the generated artwork."""
    f = images.find_one({"filename": str(product_id)})
    if not f:
        return _svg(product_id)
    return Response(f.read(), media_type=getattr(f, "contentType", None) or "image/jpeg",
                    headers={"Cache-Control": "public, max-age=86400"})


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
