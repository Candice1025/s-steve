package com.steve.ai.ai;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;

import java.util.ArrayList;
import java.util.List;

public class TaskPlanner {
    private final OpenAIClient openAIClient;
    private final GeminiClient geminiClient;
    private final GroqClient groqClient;
    private final OllamaClient ollamaClient;

    public TaskPlanner() {
        this.openAIClient = new OpenAIClient();
        this.geminiClient = new GeminiClient();
        this.groqClient = new GroqClient();
        this.ollamaClient = new OllamaClient();
    }

    public ResponseParser.ParsedResponse planTasks(SteveEntity steve, String command) {
        try {
            // ========== 开始 AI 调用 ==========
            SteveMod.LOGGER.info("╔════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ 🎮 GAME COMMAND RECEIVED");
            SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ Steve Name: {}", steve.getSteveName());
            SteveMod.LOGGER.info("║ Player Command: \"{}\"", command);
            SteveMod.LOGGER.info("║ Steve Position: [{}, {}, {}]", 
                steve.blockPosition().getX(), 
                steve.blockPosition().getY(), 
                steve.blockPosition().getZ());
            
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(steve);
            String userPrompt = PromptBuilder.buildUserPrompt(steve, command, worldKnowledge);
            
            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            SteveMod.LOGGER.info("║ AI Provider: {}", provider.toUpperCase());
            SteveMod.LOGGER.info("║ Model: {}", SteveConfig.OLLAMA_MODEL.get());
            SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ 📤 SENDING TO AI MODEL...");
            SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");
            
            // 打印用户提示词的关键部分（用于调试）
            if (command.toLowerCase().contains("build")) {
                SteveMod.LOGGER.info("🏗️  Building command detected - AI will analyze terrain and materials");
            }
            
            long startTime = System.currentTimeMillis();
            String response = getAIResponse(provider, systemPrompt, userPrompt);
            long endTime = System.currentTimeMillis();
            
            if (response == null) {
                SteveMod.LOGGER.error("╔════════════════════════════════════════════════════════════════");
                SteveMod.LOGGER.error("║ ❌ AI RESPONSE FAILED");
                SteveMod.LOGGER.error("╠════════════════════════════════════════════════════════════════");
                SteveMod.LOGGER.error("║ Command: {}", command);
                SteveMod.LOGGER.error("║ Provider: {}", provider);
                SteveMod.LOGGER.error("╚════════════════════════════════════════════════════════════════");
                return null;
            }
            
            SteveMod.LOGGER.info("╔════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ 📥 AI RESPONSE RECEIVED");
            SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ Response Time: {} ms", (endTime - startTime));
            SteveMod.LOGGER.info("║ Response Length: {} characters", response.length());
            
            ResponseParser.ParsedResponse parsedResponse = ResponseParser.parseAIResponse(response);
            
            if (parsedResponse == null) {
                SteveMod.LOGGER.error("║ ❌ FAILED TO PARSE AI RESPONSE");
                SteveMod.LOGGER.error("║ Raw Response (first 200 chars): {}", 
                    response.substring(0, Math.min(200, response.length())));
                SteveMod.LOGGER.error("╚════════════════════════════════════════════════════════════════");
                return null;
            }
            
            SteveMod.LOGGER.info("║ ✅ PARSING SUCCESSFUL");
            SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ 🧠 AI REASONING: {}", parsedResponse.getReasoning());
            SteveMod.LOGGER.info("║ 📋 PLAN: {}", parsedResponse.getPlan());
            SteveMod.LOGGER.info("║ 🎯 TASKS: {} task(s) generated", parsedResponse.getTasks().size());
            SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
            
            // 打印每个任务的详细信息
            for (int i = 0; i < parsedResponse.getTasks().size(); i++) {
                Task task = parsedResponse.getTasks().get(i);
                SteveMod.LOGGER.info("║ Task {}: {} - {}", 
                    (i + 1), 
                    task.getAction().toUpperCase(), 
                    task.getParameters());
            }
            
            SteveMod.LOGGER.info("╠════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.info("║ 🚀 STARTING EXECUTION...");
            SteveMod.LOGGER.info("╚════════════════════════════════════════════════════════════════");
            
            return parsedResponse;
            
        } catch (Exception e) {
            SteveMod.LOGGER.error("╔════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.error("║ ⚠️  ERROR IN AI PLANNING");
            SteveMod.LOGGER.error("╠════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.error("║ Error: {}", e.getMessage());
            SteveMod.LOGGER.error("╚════════════════════════════════════════════════════════════════");
            SteveMod.LOGGER.error("Error planning tasks", e);
            return null;
        }
    }

    private String getAIResponse(String provider, String systemPrompt, String userPrompt) {
        String response = switch (provider) {
            case "groq" -> groqClient.sendRequest(systemPrompt, userPrompt);
            case "gemini" -> geminiClient.sendRequest(systemPrompt, userPrompt);
            case "openai" -> openAIClient.sendRequest(systemPrompt, userPrompt);
            case "ollama" -> ollamaClient.sendRequest(systemPrompt, userPrompt);
            default -> {
                SteveMod.LOGGER.warn("Unknown AI provider '{}', using Groq", provider);
                yield groqClient.sendRequest(systemPrompt, userPrompt);
            }
        };
        
        if (response == null && !provider.equals("groq")) {
            SteveMod.LOGGER.warn("{} failed, trying Groq as fallback", provider);
            response = groqClient.sendRequest(systemPrompt, userPrompt);
        }
        
        return response;
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();
        
        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> task.hasParameters("block", "quantity");
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> task.hasParameters("item", "quantity");
            case "attack" -> task.hasParameters("target");
            case "follow" -> task.hasParameters("player");
            case "gather" -> task.hasParameters("resource", "quantity");
            case "build" -> task.hasParameters("structure"); // Only structure is required, blocks and dimensions are optional
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        List<Task> validTasks = new ArrayList<>();
        for (Task task : tasks) {
            boolean isValid = validateTask(task);
            if (isValid) {
                validTasks.add(task);
                SteveMod.LOGGER.info("✅ Task VALID: {} with parameters: {}", task.getAction(), task.getParameters());
            } else {
                SteveMod.LOGGER.warn("❌ Task INVALID: {} with parameters: {} - Missing required parameters!", 
                    task.getAction(), task.getParameters());
            }
        }
        return validTasks;
    }
}

