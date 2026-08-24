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

# Bake the embedding model into the image at build time instead of
# downloading it from Hugging Face the first time a pod starts. Without
# this, every fresh pod (a deploy, a crash restart, a node reschedule --
# there's no persistent volume for the download cache) re-downloads ~180MB
# over the network before it can serve a single request, which is slow and
# was the direct cause of an OOMKilled crash loop the first time this
# shipped (see infra/k8s/retrieval-service.yaml's resource limits, bumped
# separately to also cover this). FASTEMBED_CACHE_PATH is set before the
# warm-up call and stays set for the running container too, so both build
# time and runtime resolve to the same cache directory.
# NOTE: the model name here is duplicated from
# services/retrieval_service/embeddings.py -- keep the two in sync if that
# ever changes.
ENV FASTEMBED_CACHE_PATH=/opt/fastembed_cache
RUN python -c "from fastembed import TextEmbedding; TextEmbedding(model_name='sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')"

# Overridden per-service by docker-compose `command:`
CMD ["uvicorn", "services.retrieval_service.main:app", "--host", "0.0.0.0", "--port", "8001"]
