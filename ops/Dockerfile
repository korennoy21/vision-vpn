FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 iptables ca-certificates && rm -rf /var/lib/apt/lists/*
COPY requirements.txt requirements-control.txt ./
RUN pip install --no-cache-dir -r requirements-control.txt
COPY sever ./sever
COPY control ./control
COPY ops ./ops
RUN chmod +x /app/ops/node-entrypoint.sh
CMD ["python", "-m", "control.app", "serve"]
