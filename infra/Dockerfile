# Single Dockerfile, one image, three services -- picks which app to run via CMD arg.
# Bare-bones on purpose: no multi-stage build, no slim optimization yet.
# Swap-in path: split into per-service images once dependencies diverge
# (e.g. the Java retrieval hot-path rewrite gets its own image entirely).
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY services/ services/
COPY phrase_library/ phrase_library/
COPY eval/ eval/

# Overridden per-service by docker-compose `command:`
CMD ["uvicorn", "services.retrieval_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
