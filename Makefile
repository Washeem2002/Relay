MVN ?= mvn
COMPOSE ?= docker compose
GATEWAY_URL ?= http://localhost:8080
LOCUST_USERS ?= 200
LOCUST_SPAWN_RATE ?= 50
LOCUST_RUN_TIME ?= 60s

.PHONY: build test verify clean up up-scale down logs seed loadtest loadtest-ui

build:
	$(MVN) -B -DskipTests package

test:
	$(MVN) -B test

verify:
	$(MVN) -B verify

clean:
	$(MVN) -B clean

up:
	test -f .env || cp .env.example .env
	$(COMPOSE) up --build -d

up-scale:
	test -f .env || cp .env.example .env
	$(COMPOSE) --profile scale up --build -d

down:
	$(COMPOSE) --profile scale down -v

logs:
	$(COMPOSE) logs -f sluice

seed:
	./demo/seed.sh

loadtest:
	locust -f loadtest/locustfile.py --host $(GATEWAY_URL) --headless -u $(LOCUST_USERS) -r $(LOCUST_SPAWN_RATE) -t $(LOCUST_RUN_TIME)

loadtest-ui:
	locust -f loadtest/locustfile.py --host $(GATEWAY_URL)
