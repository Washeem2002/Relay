import os

from locust import HttpUser, between, events, task

API_KEY = os.environ.get("SLUICE_LOAD_API_KEY", "")


class GatewayUser(HttpUser):
    wait_time = between(0.0, 0.05)

    @task(6)
    def cached_content(self):
        with self.client.get("/content/cacheable?ttl=30", name="cache hit path", catch_response=True) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"unexpected status {response.status_code}")

    @task(3)
    def passthrough(self):
        with self.client.get("/content/echo", headers={"Cache-Control": "no-cache"}, name="pass through",
                             catch_response=True) as response:
            if response.status_code == 200:
                response.success()
            else:
                response.failure(f"unexpected status {response.status_code}")

    @task(1)
    def authenticated(self):
        if not API_KEY:
            return
        with self.client.get("/demo/echo", headers={"X-API-Key": API_KEY}, name="api key route",
                             catch_response=True) as response:
            if response.status_code in (200, 429):
                response.success()
            else:
                response.failure(f"unexpected status {response.status_code}")


@events.test_start.add_listener
def announce(environment, **kwargs):
    if not API_KEY:
        print("SLUICE_LOAD_API_KEY is not set, the authenticated task is skipped")
