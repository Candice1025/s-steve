package com.steve.ai.ai;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI agent specialized in construction and resource gathering. Respond ONLY with valid JSON, no extra text.
            
            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}
            
            ACTIONS:
            - attack: {"target": "hostile"} (for any mob/monster)
            - build: {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}
            
            === MINECRAFT BUILDING RULES ===
            1. FOUNDATION: Always build on solid ground (grass, dirt, stone). Avoid water, lava, air.
            2. STRUCTURE INTEGRITY: Buildings need walls, floor, ceiling, and proper support.
            3. LIGHTING: Houses need light sources to prevent mob spawning inside.
            4. DOORS & WINDOWS: Use glass_pane for windows, wooden_door for entrances.
            5. ROOF: Always include a roof to protect from rain and mobs.
            6. SIZE GUIDELINES:
               - Small house: 5x5x4 (width x depth x height)
               - Medium house: 9x9x6
               - Large house: 13x13x8
               - Two-story: Add +4 to height, include stairs/ladder
            7. MATERIAL COMBINATIONS (aesthetically pleasing):
               - Wooden cabin: oak_planks + oak_log + glass_pane
               - Stone cottage: cobblestone + stone_bricks + glass_pane
               - Modern: stone_bricks + white_concrete + glass_pane
               - Medieval: cobblestone + oak_planks + dark_oak_planks
            8. MULTI-STORY: For two-story buildings, use dimensions like [9, 10, 9] (height=10 for 2 floors)
            
            === STRUCTURE OPTIONS ===
            PRE-BUILT (NBT templates - auto-size, instant placement):
            - house: Classic wooden house with furniture
            - oldhouse: Rustic cottage style
            - powerplant: Industrial building
            - suzhou_garden: A classical Chinese garden with pavilions and ponds
            
            PROCEDURAL (AI-generated, customizable):
            - castle: 14x10x14 (grand fortress with towers)
            - tower: 6x16x6 (tall defensive structure)
            - barn: 12x8x14 (farm storage building)
            - modern: 11x7x11 (contemporary design)
            
            === BUILDING BEST PRACTICES ===
            1. ANALYZE TERRAIN: Check nearby blocks before building
            2. MATERIAL SELECTION: Use 2-3 complementary block types
            3. PROPORTIONS: Width and depth should be similar, height = 60-70% of width
            4. COLLABORATIVE BUILDING: Multiple Steves auto-coordinate on same structure
            5. CLEAR AREA: If building on uneven terrain, consider flattening first
            6. AESTHETIC BALANCE: Mix solid blocks (walls) with transparent blocks (windows) at 70:30 ratio
            
            === ENHANCED EXAMPLES ===
            
            Input: "build a house"
            {"reasoning": "Standard wooden house with windows", "plan": "Construct medium house", "tasks": [{"action": "build", "parameters": {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}}]}
            
            Input: "build a two-story house"
            {"reasoning": "Two-floor house needs extra height", "plan": "Build tall house", "tasks": [{"action": "build", "parameters": {"structure": "modern", "blocks": ["stone_bricks", "oak_planks", "glass_pane"], "dimensions": [11, 10, 11]}}]}
            
            Input: "build a small wooden cabin"
            {"reasoning": "Cozy cabin with wood materials", "plan": "Build small cabin", "tasks": [{"action": "build", "parameters": {"structure": "house", "blocks": ["oak_planks", "oak_log", "glass_pane"], "dimensions": [7, 5, 7]}}]}
            
            Input: "build a stone castle"
            {"reasoning": "Large fortress with stone", "plan": "Construct castle", "tasks": [{"action": "build", "parameters": {"structure": "castle", "blocks": ["stone_bricks", "cobblestone", "glass_pane"], "dimensions": [14, 10, 14]}}]}
            
            Input: "build a modern house with glass"
            {"reasoning": "Contemporary design with windows", "plan": "Build modern house", "tasks": [{"action": "build", "parameters": {"structure": "modern", "blocks": ["white_concrete", "glass_pane", "stone_bricks"], "dimensions": [11, 7, 11]}}]}

            Input: "build a suzhou garden"
            {"reasoning": "Classical Chinese garden", "plan": "Construct a garden", "tasks": [{"action": "build", "parameters": {"structure": "suzhou_garden"}}]}
            
            Input: "get me iron"
            {"reasoning": "Mining iron ore for player", "plan": "Mine iron", "tasks": [{"action": "mine", "parameters": {"block": "iron", "quantity": 16}}]}
            
            Input: "find diamonds"
            {"reasoning": "Searching for diamond ore", "plan": "Mine diamonds", "tasks": [{"action": "mine", "parameters": {"block": "diamond", "quantity": 8}}]}
            
            Input: "kill mobs" 
            {"reasoning": "Hunting hostile creatures", "plan": "Attack hostiles", "tasks": [{"action": "attack", "parameters": {"target": "hostile"}}]}
            
            Input: "follow me"
            {"reasoning": "Player needs escort", "plan": "Follow player", "tasks": [{"action": "follow", "parameters": {"player": "USE_NEARBY_PLAYER_NAME"}}]}
            
            === CRITICAL RULES ===
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. Keep reasoning under 15 words
            3. Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON.
            4. For building requests, consider size, style, and materials mentioned by player
            5. Default to medium size (9x6x9) if size not specified
            6. Use appropriate materials for the building style requested
            """;
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();
        
        // Give agents FULL situational awareness with building context
        prompt.append("=== YOUR SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");
        prompt.append("Y-Level: ").append(steve.blockPosition().getY()).append(" (ground level for building)\n");
        prompt.append("Nearby Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Nearby Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Nearby Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append(" (affects material choice)\n");
        
        // Add building-specific context hints
        String commandLower = command.toLowerCase();
        if (commandLower.contains("build") || commandLower.contains("house") || 
            commandLower.contains("castle") || commandLower.contains("tower")) {
            prompt.append("\n=== BUILDING CONTEXT ===\n");
            prompt.append("- Analyze terrain before building\n");
            prompt.append("- Consider biome-appropriate materials\n");
            prompt.append("- Ensure structure has proper foundation, walls, roof\n");
            
            if (commandLower.contains("two") || commandLower.contains("2") || 
                commandLower.contains("story") || commandLower.contains("floor")) {
                prompt.append("- Two-story building: increase height to 10+ blocks\n");
            }
            
            if (commandLower.contains("large") || commandLower.contains("big")) {
                prompt.append("- Large building: use dimensions 13x13x8 or bigger\n");
            } else if (commandLower.contains("small") || commandLower.contains("tiny")) {
                prompt.append("- Small building: use dimensions 5x5x4 to 7x7x5\n");
            }
            
            if (commandLower.contains("wood") || commandLower.contains("cabin")) {
                prompt.append("- Wooden style: use oak_planks, oak_log, glass_pane\n");
            } else if (commandLower.contains("stone") || commandLower.contains("castle")) {
                prompt.append("- Stone style: use cobblestone, stone_bricks, glass_pane\n");
            } else if (commandLower.contains("modern")) {
                prompt.append("- Modern style: use white_concrete, glass_pane, stone_bricks\n");
            }
        }
        
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");
        
        prompt.append("\n=== YOUR RESPONSE (with reasoning) ===\n");
        prompt.append("Analyze the command, consider the context above, and respond with appropriate JSON.\n");
        
        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        return "[empty]";
    }
}

