FROM docker.m.daocloud.io/library/python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df
RUN sed -i 's|http://deb.debian.org|https://repo.huaweicloud.com|g' /etc/apt/sources.list.d/debian.sources \
 && apt-get update && apt-get install -y --no-install-recommends espeak-ng gunicorn git \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server-wheels/ /wheels/
COPY server/requirements.txt /app/requirements.txt
RUN if find /wheels -name '*.whl' | grep -q .; then \
      pip install --no-cache-dir --no-index --find-links /wheels --require-hashes -r requirements.txt; \
    else \
      pip install --no-cache-dir --index-url https://pypi.tuna.tsinghua.edu.cn/simple --require-hashes -r requirements.txt; \
    fi \
 && rm -rf /wheels
COPY models/ /models/
COPY server/download_models.py /app/download_models.py
RUN python /app/download_models.py /models
COPY server /app
COPY app/src/main/assets/exam_questions.json /app/questions.json
COPY app/src/main/assets/ielts_sets.json /app/ielts_sets.json
COPY app/src/main/assets/dicts/ /app/dicts/
COPY app/src/main/assets/exam_frequency.json app/src/main/assets/vocabulary_sources.json app/src/main/assets/vocabulary_license.txt /app/
COPY licenses /app/licenses
RUN install -d -o 10001 -g 10001 /data
USER 10001:10001
ENV LEXIFLOW_DB=/data/questions.sqlite3 PYTHONDONTWRITEBYTECODE=1 ORT_DISABLE_TELEMETRY=1 TTS_THREADS=2
EXPOSE 5000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5000/health',timeout=3).read()"
CMD ["python","-m","gunicorn","--bind","0.0.0.0:5000","--workers","1","--threads","8","--timeout","90","service:app"]
