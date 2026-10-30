"""Synthetic shoppers: browse, search, add to cart and check out through the frontend.

Runs in-cluster (headless) so dashboards and traces always have traffic.
"""
import random
import uuid

from locust import HttpUser, between, task

QUERIES = ["earbuds", "keyboard", "kubernetes", "shoes", "coffee", "drone", "yoga", "monitor", "novel", "xyznothing"]
CATEGORIES = ["Electronics", "Books", "Home & Kitchen", "Fashion", "Sports & Fitness", "Toys & Games"]


class Shopper(HttpUser):
    wait_time = between(1, 4)

    def on_start(self):
        email = f"shopper-{uuid.uuid4().hex[:10]}@loadgen.jhanvi-trends.local"
        res = self.client.post("/api/auth/register", json={"name": "Load Shopper", "email": email, "password": "loadgen123"},
                               name="/api/auth/register")
        token = res.json().get("token") if res.ok else None
        self.client.headers["authorization"] = f"Bearer {token}" if token else ""

    @task(10)
    def browse(self):
        self.client.get("/api/products?limit=20", name="/api/products")
        pid = random.randint(1, 24)
        self.client.get(f"/api/products/{pid}", name="/api/products/:id")
        self.client.get(f"/api/images/{pid}.svg", name="/api/images/:id")

    @task(4)
    def search(self):
        self.client.get(f"/api/products?q={random.choice(QUERIES)}", name="/api/products?q")

    @task(3)
    def category(self):
        self.client.get("/api/products", params={"category": random.choice(CATEGORIES)}, name="/api/products?category")

    @task(3)
    def add_to_cart(self):
        self.client.post("/api/cart/items", json={"productId": random.randint(1, 24), "quantity": random.randint(1, 2)},
                         name="/api/cart/items")
        self.client.get("/api/cart", name="/api/cart")

    @task(1)
    def checkout(self):
        self.client.post("/api/cart/items", json={"productId": random.randint(1, 24), "quantity": 1}, name="/api/cart/items")
        with self.client.post("/api/checkout", name="/api/checkout", catch_response=True, json={
            "shippingAddress": {"fullName": "Load Shopper", "line1": "12 MG Road", "city": "Bengaluru",
                                "pincode": "560001", "state": "Karnataka"},
        }) as res:
            # Declines (402) and stock-outs (409) are expected business outcomes, not load-test failures.
            if res.status_code in (201, 402, 409):
                res.success()
        self.client.get("/api/orders", name="/api/orders")
