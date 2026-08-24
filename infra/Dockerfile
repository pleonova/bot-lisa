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

# Where fastembed caches the downloaded embedding model. NOT baked into
# the image (tried that first -- it worked, but every image push then
# carried the ~220MB model too, which blew through the DigitalOcean
# registry's storage quota after a handful of pushes). Instead
# infra/k8s/retrieval-service.yaml mounts a small persistent volume at this
# exact path, so the model downloads once ever (first pod to use a fresh
# volume) and survives every deploy/restart/reschedule after that, without
# the image itself growing at all. Local/docker-compose runs without that
# volume mounted will just re-download into container-local storage each
# time, same as before this existed -- fine for occasional local testing.
ENV FASTEMBED_CACHE_PATH=/opt/fastembed_cache

# Overridden per-service by docker-compose `command:`
CMD ["uvicorn", "services.retrieval_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
