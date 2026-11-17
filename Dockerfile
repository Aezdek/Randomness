FROM python:3.13-alpine

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PASTPAPERS_DATA=/data \
    PASTPAPERS_CONFIG=/config/config.toml

WORKDIR /app
COPY pastpapers/ ./pastpapers/

ENTRYPOINT ["python", "-m", "pastpapers"]
CMD ["run"]
