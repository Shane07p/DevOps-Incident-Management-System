FROM python:3.12-slim

WORKDIR /app

# Install dependencies first (Docker layer cache)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy all required Python source modules
COPY main.py .
COPY agent_loop.py .
COPY agent_tools.py .
COPY providers.py .
COPY log_patterns.py .
COPY mcp_server.py .

# Copy runtime data (recorded Demo Shop fixtures used by evidence tools)
COPY data/ ./data/

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
