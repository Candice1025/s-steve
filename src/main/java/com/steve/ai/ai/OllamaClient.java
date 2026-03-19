package com.steve.ai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Client for Ollama API - Cloud-based LLM inference
 * Supports custom models like gpt-oss:120b-cloud
 */
public class OllamaClient {
    private static final int MAX_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 1000;

    private final HttpClient client;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public OllamaClient() {
        this.apiUrl = SteveConfig.OLLAMA_API_URL.get();
        this.apiKey = SteveConfig.OLLAMA_API_KEY.get();
        this.model = SteveConfig.OLLAMA_MODEL.get();
        this.client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public String sendRequest(String systemPrompt, String userPrompt) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            SteveMod.LOGGER.error("Ollama API URL not configured!");
            return null;
        }

        SteveMod.LOGGER.info("╔════════════════════════════════════════════════════════════════");
        SteveMod.LOGGER.info("║ 🌐 OLLAMA CLOUD API CALL");
        SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
        SteveMod.LOGGER.info("║ API URL: {}", apiUrl);
        SteveMod.LOGGER.info("║ Model: {}", model);
        SteveMod.LOGGER.info("║ Temperature: {}", SteveConfig.TEMPERATURE.get());
        SteveMod.LOGGER.info("║ Max Tokens: {}", SteveConfig.MAX_TOKENS.get());
        
        // 显示 API Key 的部分信息（安全）
        if (apiKey != null && !apiKey.isEmpty()) {
            String maskedKey = apiKey.substring(0, Math.min(8, apiKey.length())) + "..." + 
                              (apiKey.length() > 12 ? apiKey.substring(apiKey.length() - 4) : "");
            SteveMod.LOGGER.info("║ API Key: {}", maskedKey);
        } else {
            SteveMod.LOGGER.info("║ API Key: Not configured");
        }
        
        // 显示用户提示词的前 150 个字符
        String promptPreview = userPrompt.length() > 150 ? 
            userPrompt.substring(0, 150) + "..." : userPrompt;
        SteveMod.LOGGER.info("║ Prompt Preview: {}", promptPreview.replace("\n", " "));
        SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");

        JsonObject requestBody = buildRequestBody(systemPrompt, userPrompt);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));

        // Add API key if configured
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpRequest request = requestBuilder.build();

        // Retry logic with exponential backoff
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    SteveMod.LOGGER.info("🔄 Retry attempt {}/{}", attempt + 1, MAX_RETRIES);
                }
                
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                SteveMod.LOGGER.info("╔════════════════════════════════════════════════════════════════");
                SteveMod.LOGGER.info("║ 📡 OLLAMA API RESPONSE");
                SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
                SteveMod.LOGGER.info("║ HTTP Status: {}", response.statusCode());

                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    if (responseBody == null || responseBody.isEmpty()) {
                        SteveMod.LOGGER.error("║ ❌ ERROR: Empty response body");
                        SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");
                        return null;
                    }
                    
                    SteveMod.LOGGER.info("║ Response Size: {} bytes", responseBody.length());
                    
                    String parsedResponse = parseResponse(responseBody);
                    
                    if (parsedResponse != null) {
                        SteveMod.LOGGER.info("║ ✅ SUCCESS!");
                        SteveMod.LOGGER.info("║ Parsed Content Length: {} characters", parsedResponse.length());
                        
                        // 显示响应的前 200 个字符
                        String responsePreview = parsedResponse.length() > 200 ? 
                            parsedResponse.substring(0, 200) + "..." : parsedResponse;
                        SteveMod.LOGGER.info("║ Response Preview: {}", responsePreview.replace("\n", " "));
                        SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");
                    } else {
                        SteveMod.LOGGER.error("║ ❌ ERROR: Failed to parse response");
                        SteveMod.LOGGER.error("║ Raw response (first 300 chars): {}", 
                            responseBody.substring(0, Math.min(300, responseBody.length())));
                        SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");
                    }
                    
                    return parsedResponse;
                }

                // Check if error is retryable
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < MAX_RETRIES - 1) {
                        int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                        SteveMod.LOGGER.warn("⚠️  Status {}, retrying in {}ms (attempt {}/{})",
                            response.statusCode(), delayMs, attempt + 1, MAX_RETRIES);
                        Thread.sleep(delayMs);
                        continue;
                    }
                }

                SteveMod.LOGGER.error("║ ❌ ERROR: Request failed");
                SteveMod.LOGGER.error("║ Status Code: {}", response.statusCode());
                SteveMod.LOGGER.error("║ Response Body: {}", response.body());
                SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");
                return null;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SteveMod.LOGGER.error("❌ Request interrupted", e);
                return null;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                    SteveMod.LOGGER.warn("⚠️  Error: {}, retrying in {}ms (attempt {}/{})",
                        e.getMessage(), delayMs, attempt + 1, MAX_RETRIES);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    SteveMod.LOGGER.error("❌ Error after {} attempts: {}", MAX_RETRIES, e.getMessage());
                    SteveMod.LOGGER.error("Full error:", e);
                    return null;
                }
            }
        }

        return null;
    }

    private JsonObject buildRequestBody(String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", SteveConfig.TEMPERATURE.get());
        body.addProperty("max_tokens", SteveConfig.MAX_TOKENS.get());

        JsonArray messages = new JsonArray();
        
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        body.add("messages", messages);
        
        return body;
    }

    private String parseResponse(String responseBody) {
        try {
            // Handle streaming response - multiple JSON objects separated by newlines
            String[] lines = responseBody.split("\n");
            StringBuilder fullContent = new StringBuilder();
            
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                try {
                    JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                    
                    // Check if this is the final message
                    boolean isDone = json.has("done") && json.get("done").getAsBoolean();
                    
                    // Extract content from message
                    if (json.has("message")) {
                        JsonObject message = json.getAsJsonObject("message");
                        if (message.has("content")) {
                            String content = message.get("content").getAsString();
                            if (!content.isEmpty()) {
                                fullContent.append(content);
                            }
                        }
                    }
                    
                    // If done, return the accumulated content
                    if (isDone) {
                        String result = fullContent.toString().trim();
                        if (!result.isEmpty()) {
                            return result;
                        }
                    }
                } catch (Exception e) {
                    // Skip malformed JSON lines
                    continue;
                }
            }
            
            // If we got content but no done flag, return what we have
            String result = fullContent.toString().trim();
            if (!result.isEmpty()) {
                return result;
            }
            
            // Try OpenAI-compatible format as fallback
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                JsonObject firstChoice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (firstChoice.has("message")) {
                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message.has("content")) {
                        return message.get("content").getAsString();
                    }
                }
            }
            
            SteveMod.LOGGER.error("Unexpected Ollama response format: {}", responseBody.substring(0, Math.min(200, responseBody.length())));
            return null;
            
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error parsing Ollama response", e);
            return null;
        }
    }
}
