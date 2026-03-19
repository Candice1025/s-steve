package com.steve.ai.agent;

// TODO: Will be implemented later - tool abstraction for agent framework
// Currently tools are executed directly through ActionExecutor
// Placeholder for future tool-calling capabilities
public class ToolWrapper {
    private final String name;
    private final String description;
    private final boolean returnDirect;
    
    public ToolWrapper(String name, String description) {
        this(name, description, false);
    }
    
    public ToolWrapper(String name, String description, boolean returnDirect) {
        this.name = name;
        this.description = description;
        this.returnDirect = returnDirect;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean shouldReturnDirect() {
        return returnDirect;
    }
    
    public String invoke(String input) {
        return "Executing tool: " + name + " with input: " + input;
    }
}
