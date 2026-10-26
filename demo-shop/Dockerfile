FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY demo_shop/ demo_shop/

ENV PORT=8001
EXPOSE 8001

CMD ["uvicorn", "demo_shop.app:app", "--host", "0.0.0.0", "--port", "8001"]
