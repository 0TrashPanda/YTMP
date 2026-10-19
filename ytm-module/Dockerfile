# YTMP YouTube Music module. Build from ytm-module/:
#   docker build -t ytmp-ytm .
# The published image (ghcr.io/0trashpanda/ytmp-ytm) is rebuilt every night when a new
# yt-dlp, ytmusicapi or PO token plugin is out (.github/workflows/docker.yml), with these set
# to it. The plugin's server (the pot service in deploy/docker-compose.yml) follows its own
# latest image, so the two stay on the same version.

FROM python:3.13-slim
# yt-dlp needs a JavaScript runtime for YouTube; Deno is its default.
COPY --from=denoland/deno:bin /deno /usr/local/bin/deno
WORKDIR /app
COPY pyproject.toml ./
COPY ytmp_ytm ytmp_ytm
ARG YT_DLP_VERSION=
ARG YTMUSICAPI_VERSION=
ARG POT_PROVIDER_VERSION=
RUN pip install --no-cache-dir ".[pot]" \
    && if [ -n "$YT_DLP_VERSION" ]; then pip install --no-cache-dir "yt-dlp[default]==$YT_DLP_VERSION"; fi \
    && if [ -n "$YTMUSICAPI_VERSION" ]; then pip install --no-cache-dir "ytmusicapi==$YTMUSICAPI_VERSION"; fi \
    && if [ -n "$POT_PROVIDER_VERSION" ]; then pip install --no-cache-dir "bgutil-ytdlp-pot-provider==$POT_PROVIDER_VERSION"; fi \
    && useradd --system --create-home ytmp
USER ytmp
ENV YTMP_YTM_HOST=0.0.0.0 \
    YTMP_YTM_PORT=8401
EXPOSE 8401
CMD ["ytmp-ytm"]
