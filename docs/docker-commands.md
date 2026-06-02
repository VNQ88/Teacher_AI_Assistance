# Docker Commands

## Build & Push Image (máy local)

```bash
# x86
docker build -t vnq88/teacher-assistant-ai:1.0.0 .
docker push vnq88/teacher-assistant-ai:1.0.0

# ARM (Apple Silicon) → build cho linux/amd64
docker buildx build --platform linux/amd64 \
  -t vnq88/teacher-assistant-ai:1.0.0 \
  --push .
```

## Deploy (Droplet)

```bash
cd /opt/teacher-assistant-ai

# Pull tất cả images
docker compose --env-file .env.prod -f compose.prod.yml --profile proxy pull

# Khởi động stack
docker compose --env-file .env.prod -f compose.prod.yml --profile proxy up -d
```

## Kiểm tra

```bash
# Trạng thái containers
docker compose --env-file .env.prod -f compose.prod.yml ps

# Log app real-time
docker compose --env-file .env.prod -f compose.prod.yml logs -f app

# Test API
curl -I https://api.filemanager.io.vn/api/v3/api-docs
```

## Deploy Version Mới

```bash
# 1. Sửa APP_IMAGE trong .env.prod
# 2. Trên máy local: build & push tag mới
docker buildx build --platform linux/amd64 \
  -t vnq88/teacher-assistant-ai:1.0.1 \
  --push .

# 3. Trên Droplet
docker compose --env-file .env.prod -f compose.prod.yml --profile proxy pull
docker compose --env-file .env.prod -f compose.prod.yml --profile proxy up -d
```

## Restart / Stop

```bash
# Restart 1 service
docker compose --env-file .env.prod -f compose.prod.yml restart app

# Dừng toàn bộ stack
docker compose --env-file .env.prod -f compose.prod.yml --profile proxy down
```

## Chạy Không Có Domain (test nội bộ)

```bash
# Bỏ --profile proxy để không chạy Caddy
docker compose --env-file .env.prod -f compose.prod.yml pull
docker compose --env-file .env.prod -f compose.prod.yml up -d
```

Sau đó tạo SSH tunnel trong Termius:

| Local | Remote |
|---|---|
| `127.0.0.1:8080` | `127.0.0.1:8080` |
| `127.0.0.1:9100` | `127.0.0.1:9100` |
| `127.0.0.1:9101` | `127.0.0.1:9101` |
