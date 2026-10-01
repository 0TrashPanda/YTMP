# YTMP YouTube Music module. Build from ytm-module/:
#   docker build -t ytmp-ytm .

FROM python:3.13-slim
# yt-dlp needs a JavaScript runtime for YouTube; Deno is its default.
COPY --from=denoland/deno:bin /deno /usr/local/bin/deno
WORKDIR /app
COPY pyproject.toml ./
COPY ytmp_ytm ytmp_ytm
RUN pip install --no-cache-dir . && useradd --system --create-home ytmp
USER ytmp
ENV YTMP_YTM_HOST=0.0.0.0 \
    YTMP_YTM_PORT=8401
EXPOSE 8401
CMD ["ytmp-ytm"]
