# ai-service — image slim (ADR-0029 D7 : ~250 Mo, 512 Mo de RAM, CPU only).
# Python 3.12 épinglé par l'ADR ; le code tourne aussi sur 3.13 (poste de dev).
FROM python:3.12-slim

WORKDIR /srv/ai-service

# Dépendances d'abord (cache de layer stable tant que le lockfile ne bouge pas).
# Contexte de build = ai-service/ (compose) — pas la racine du dépôt.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Jamais root au runtime.
RUN useradd --system --no-create-home appuser
USER appuser

EXPOSE 8084

# Healthcheck sans curl (absent de slim) : urllib suffit.
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=10s \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8084/ai/health', timeout=3)"]

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
