FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg tzdata && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY eyespy ./eyespy
ENV EYESPY_DATA=/data PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8080/healthz')"
CMD ["uvicorn", "eyespy.main:app", "--host", "0.0.0.0", "--port", "8080", "--ws-ping-interval", "20", "--ws-ping-timeout", "20", "--proxy-headers"]
