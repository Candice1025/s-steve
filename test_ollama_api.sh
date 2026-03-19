#!/bin/bash

set -euo pipefail

if [[ -z "${OLLAMA_API_KEY:-}" ]]; then
  echo "Missing OLLAMA_API_KEY. Example:"
  echo "  export OLLAMA_API_KEY='your-token'"
  exit 1
fi

MODEL="${OLLAMA_MODEL:-gpt-oss:120b-cloud}"

echo "Testing Ollama Cloud API..."
echo "Model: ${MODEL}"
echo ""

endpoints=(
  "https://api.ollama.com/v1/chat/completions"
  "https://ollama.ai/api/v1/chat/completions"
  "https://cloud.ollama.ai/v1/chat/completions"
)

payload=$(cat <<EOF
{
  "model": "${MODEL}",
  "messages": [{"role": "user", "content": "test"}],
  "max_tokens": 10
}
EOF
)

for endpoint in "${endpoints[@]}"; do
  echo "Testing: $endpoint"
  response=$(curl -sS -w "\nHTTP_CODE:%{http_code}" -X POST "$endpoint" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${OLLAMA_API_KEY}" \
    -d "${payload}" \
    --max-time 10 2>&1)

  echo "$response"
  echo "---"
  echo ""
done
