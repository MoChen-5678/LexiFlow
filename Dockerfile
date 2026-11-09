FROM docker.m.daocloud.io/library/python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df
RUN sed -i 's|http://deb.debian.org|https://repo.huaweicloud.com|g' /etc/apt/sources.list.d/debian.sources \
 && apt-get update && apt-get install -y --no-install-recommends espeak-ng gunicorn git \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server /app
COPY app/src/main/assets/exam_questions.json /app/questions.json
COPY app/src/main/assets/dicts/cet4.json app/src/main/assets/dicts/cet6.json app/src/main/assets/dicts/ielts.json /app/dicts/
RUN install -d -o 10001 -g 10001 /data
USER 10001:10001
ENV LEXIFLOW_DB=/data/questions.sqlite3 PYTHONDONTWRITEBYTECODE=1
EXPOSE 5000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5000/health',timeout=3).read()"
CMD ["/usr/bin/gunicorn","--bind","0.0.0.0:5000","--workers","1","--threads","8","--timeout","60","service:app"]
