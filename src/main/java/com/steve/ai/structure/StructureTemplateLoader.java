package com.steve.ai.structure;

import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads Minecraft structure templates from NBT files for sequential block-by-block placement
 */
public class StructureTemplateLoader {
    
    public static class TemplateBlock {
        public final BlockPos relativePos;
        public final BlockState blockState;
        
        public TemplateBlock(BlockPos relativePos, BlockState blockState) {
            this.relativePos = relativePos;
            this.blockState = blockState;
        }
    }
    
    public static class LoadedTemplate {
        public final String name;
        public final List<TemplateBlock> blocks;
        public final int width;
        public final int height;
        public final int depth;
        
        public LoadedTemplate(String name, List<TemplateBlock> blocks, int width, int height, int depth) {
            this.name = name;
            this.blocks = blocks;
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }
    
    /**
     * Load a structure from an NBT file (either custom or Minecraft's native format)
     */
    public static LoadedTemplate loadFromNBT(ServerLevel level, String structureName) {        File structuresDir = new File(System.getProperty("user.dir"), "structures");
        SteveMod.LOGGER.info("Structures directory: {}", structuresDir.getAbsolutePath());
        
        File exactMatch = new File(structuresDir, structureName + ".nbt");
        if (exactMatch.exists()) {
            SteveMod.LOGGER.info("Found structure (exact match): {}", exactMatch.getName());
            return loadFromFile(exactMatch, structureName);
        }
        
        String withSpaces = structureName.replaceAll("(\\w)(\\p{Upper})", "$1 $2").toLowerCase();
        File spacedMatch = new File(structuresDir, withSpaces + ".nbt");
        if (spacedMatch.exists()) {
            SteveMod.LOGGER.info("Found structure (spaced match): {}", spacedMatch.getName());
            return loadFromFile(spacedMatch, structureName);
        }
        
        if (structuresDir.exists() && structuresDir.isDirectory()) {
            File[] files = structuresDir.listFiles((dir, name) -> {
                if (!name.endsWith(".nbt")) return false;
                
                String nameWithoutExt = name.substring(0, name.length() - 4);
                
                // Normalize both strings: lowercase, remove spaces and underscores
                String normalizedFile = nameWithoutExt.toLowerCase().replace(" ", "").replace("_", "");
                String normalizedSearch = structureName.toLowerCase().replace(" ", "").replace("_", "");
                
                return normalizedFile.equals(normalizedSearch);
            });
            
            if (files != null && files.length > 0) {
                SteveMod.LOGGER.info("Found structure (fuzzy match): {}", files[0].getName());
                return loadFromFile(files[0], structureName);
            }
        }
        
        try {
            ResourceLocation resourceLocation = new ResourceLocation("steve", structureName);
            var templateManager = level.getStructureManager();
            var template = templateManager.get(resourceLocation);
            
            if (template.isPresent()) {                return loadFromMinecraftTemplate(template.get(), structureName);
            }
        } catch (Exception e) {        }
        
        SteveMod.LOGGER.warn("Structure '{}' not found. Available structures: {}", 
            structureName, getAvailableStructures());
        return null;
    }
    
    /**
     * Load from a custom NBT file
     */
    private static LoadedTemplate loadFromFile(File file, String name) {
        try (InputStream inputStream = new FileInputStream(file)) {
            CompoundTag nbt = NbtIo.readCompressed(inputStream);
            return parseNBTStructure(nbt, name);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load structure from file: {}", file, e);
            return null;
        }
    }
    
    /**
     * Load from Minecraft's native StructureTemplate
     * Note: This is a simplified version that works with NBT directly
     */
    private static LoadedTemplate loadFromMinecraftTemplate(StructureTemplate template, String name) {
        List<TemplateBlock> blocks = new ArrayList<>();
        
        var size = template.getSize();
        int width = size.getX();
        int height = size.getY();
        int depth = size.getZ();
        
        // This method is here for future compatibility with Minecraft's template system
        
        SteveMod.LOGGER.warn("Direct template loading not fully implemented, please use NBT files directly");
        return null;
    }
    
    /**
     * Parse a structure from raw NBT data
     */
    private static LoadedTemplate parseNBTStructure(CompoundTag nbt, String name) {
        List<TemplateBlock> blocks = new ArrayList<>();
        
        var sizeList = nbt.getList("size", 3); // 3 = TAG_Int
        int width = sizeList.getInt(0);
        int height = sizeList.getInt(1);
        int depth = sizeList.getInt(2);
        
        var paletteList = nbt.getList("palette", 10); // 10 = TAG_Compound
        List<BlockState> palette = new ArrayList<>();
        
        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag blockTag = paletteList.getCompound(i);
            String blockName = blockTag.getString("Name");
            
            try {
                ResourceLocation blockLocation = new ResourceLocation(blockName);
                Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockLocation);
                palette.add(block.defaultBlockState());
            } catch (Exception e) {
                SteveMod.LOGGER.warn("Unknown block in structure: {}", blockName);
                palette.add(Blocks.AIR.defaultBlockState());
            }
        }
        
        var blocksList = nbt.getList("blocks", 10);
        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag blockTag = blocksList.getCompound(i);
            
            int paletteIndex = blockTag.getInt("state");
            var posList = blockTag.getList("pos", 3);
            
            BlockPos pos = new BlockPos(
                posList.getInt(0),
                posList.getInt(1),
                posList.getInt(2)
            );
            
            BlockState state = palette.get(paletteIndex);
            if (!state.isAir()) {
                blocks.add(new TemplateBlock(pos, state));
            }
        }
        
        SteveMod.LOGGER.info("Loaded {} blocks from NBT '{}' ({}x{}x{})", blocks.size(), name, width, height, depth);
        return new LoadedTemplate(name, blocks, width, height, depth);
    }
    
    /**
     * Get list of available structure templates
     */
    public static List<String> getAvailableStructures() {
        List<String> structures = new ArrayList<>();
        
        File structuresDir = new File(System.getProperty("user.dir"), "structures");
        if (structuresDir.exists() && structuresDir.isDirectory()) {
            File[] files = structuresDir.listFiles((dir, name) -> name.endsWith(".nbt"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".nbt", "");
                    structures.add(name);
                }
            }
        }
        
        return structures;
    }
}

