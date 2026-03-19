package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.CollaborativeBuildManager;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.StructureRegistry;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class BuildStructureAction extends BaseAction {
    private static class BlockPlacement {
        BlockPos pos;
        Block block;
        
        BlockPlacement(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
        }
    }
    
    private String structureType;
    private List<BlockPlacement> buildPlan;
    private int currentBlockIndex;
    private List<Block> buildMaterials;
    private int ticksRunning;
    private CollaborativeBuildManager.CollaborativeBuild collaborativeBuild; // For multi-Steve collaboration
    private boolean isCollaborative;
    private static final int MAX_TICKS = 120000;
    private static final int BLOCKS_PER_TICK = 1;
    private static final double BUILD_SPEED_MULTIPLIER = 1.5;

    public BuildStructureAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        structureType = task.getStringParameter("structure").toLowerCase();
        currentBlockIndex = 0;
        ticksRunning = 0;
        
        SteveMod.LOGGER.info("🏗️  BuildStructureAction starting for structure: {}", structureType);
        SteveMod.LOGGER.info("   Task parameters: {}", task.getParameters());
        
        // Check if there's an active build NEARBY (within 50 blocks)
        collaborativeBuild = findNearbyActiveBuild(structureType, 50);
        
        if (collaborativeBuild != null) {
            isCollaborative = true;
            
            steve.setFlying(true);
            
            SteveMod.LOGGER.info("Steve '{}' JOINING nearby collaborative build of '{}' at {} ({}% complete) - FLYING & INVULNERABLE ENABLED", 
                steve.getSteveName(), structureType, collaborativeBuild.startPos, collaborativeBuild.getProgressPercentage());
            
            buildMaterials = new ArrayList<>();
            buildMaterials.add(Blocks.OAK_PLANKS); // Default material
            buildMaterials.add(Blocks.COBBLESTONE);
            buildMaterials.add(Blocks.GLASS_PANE);
            
            return; // Skip structure generation, just join the existing build
        }
        
        isCollaborative = false;
        
        buildMaterials = new ArrayList<>();
        Object blocksParam = task.getParameter("blocks");
        if (blocksParam instanceof List) {
            List<?> blocksList = (List<?>) blocksParam;
            for (Object blockObj : blocksList) {
                Block block = parseBlock(blockObj.toString());
                if (block != Blocks.AIR) {
                    buildMaterials.add(block);
                }
            }
        }
        
        if (buildMaterials.isEmpty()) {
            String materialName = task.getStringParameter("material", "oak_planks");
            Block block = parseBlock(materialName);
            buildMaterials.add(block != Blocks.AIR ? block : Blocks.OAK_PLANKS);
        }
        
        SteveMod.LOGGER.info("   Build materials: {}", buildMaterials);
        
        Object dimensionsParam = task.getParameter("dimensions");
        int width = 15;  // Increased from 9
        int height = 8; // Increased from 6
        int depth = 15;  // Increased from 9
        
        if (dimensionsParam instanceof List) {
            List<?> dims = (List<?>) dimensionsParam;
            if (dims.size() >= 3) {
                width = ((Number) dims.get(0)).intValue();
                height = ((Number) dims.get(1)).intValue();
                depth = ((Number) dims.get(2)).intValue();
            }
        } else {
            width = task.getIntParameter("width", 9);
            height = task.getIntParameter("height", 6);
            depth = task.getIntParameter("depth", 9);
        }
        
        SteveMod.LOGGER.info("   Build dimensions: {}x{}x{} (WxHxD)", width, height, depth);
        
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        BlockPos groundPos;
        
        if (nearestPlayer != null) {
            net.minecraft.world.phys.Vec3 eyePos = nearestPlayer.getEyePosition(1.0F);
            net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();
            
            net.minecraft.world.phys.Vec3 targetPos = eyePos.add(lookVec.scale(12));
            
            BlockPos lookTarget = new BlockPos(
                (int)Math.floor(targetPos.x),
                (int)Math.floor(targetPos.y),
                (int)Math.floor(targetPos.z)
            );
            
            groundPos = findGroundLevel(lookTarget);
            
            if (groundPos == null) {
                groundPos = findGroundLevel(nearestPlayer.blockPosition().offset(
                    (int)Math.round(lookVec.x * 10),
                    0,
                    (int)Math.round(lookVec.z * 10)
                ));
            }
            
            SteveMod.LOGGER.info("Building in player's field of view at {} (looking from {} towards {})", 
                groundPos, eyePos, targetPos);
        } else {
            BlockPos buildPos = steve.blockPosition().offset(2, 0, 2);
            groundPos = findGroundLevel(buildPos);
            SteveMod.LOGGER.warn("No player found, building near Steve at {}", groundPos);
        }
        
        if (groundPos == null) {
            SteveMod.LOGGER.error("❌ Cannot find suitable ground for building!");
            result = ActionResult.failure("Cannot find suitable ground for building in your field of view");
            return;
        }
        
        SteveMod.LOGGER.info("Found ground at Y={} (Build starting at {})", groundPos.getY(), groundPos);
        
        BlockPos clearPos = groundPos;
        
        buildPlan = tryLoadFromTemplate(structureType, clearPos);
        
        if (buildPlan == null) {
            // Fall back to procedural generation
            SteveMod.LOGGER.info("No template found for '{}', using procedural generation", structureType);
            buildPlan = generateBuildPlan(structureType, clearPos, width, height, depth);
        } else {
            SteveMod.LOGGER.info("Loaded '{}' from NBT template with {} blocks", structureType, buildPlan.size());
        }
        
        if (buildPlan == null || buildPlan.isEmpty()) {
            SteveMod.LOGGER.error("❌ Cannot generate build plan for: {}", structureType);
            result = ActionResult.failure("Cannot generate build plan for: " + structureType);
            return;
        }
        
        SteveMod.LOGGER.info("✅ Build plan generated with {} blocks", buildPlan.size());
        
        StructureRegistry.register(clearPos, width, height, depth, structureType);
        
        // Check again for nearby builds (in case another Steve just started one)
        collaborativeBuild = findNearbyActiveBuild(structureType, 50);
        
        if (collaborativeBuild != null) {
            isCollaborative = true;
            SteveMod.LOGGER.info("Steve '{}' JOINING existing {} collaborative build at {}", 
                steve.getSteveName(), structureType, collaborativeBuild.startPos);
        } else {
            List<CollaborativeBuildManager.BlockPlacement> collaborativeBlocks = new ArrayList<>();
            for (BlockPlacement bp : buildPlan) {
                collaborativeBlocks.add(new CollaborativeBuildManager.BlockPlacement(bp.pos, bp.block));
            }
            
            collaborativeBuild = CollaborativeBuildManager.registerBuild(structureType, collaborativeBlocks, clearPos);
            isCollaborative = true;
            SteveMod.LOGGER.info("Steve '{}' CREATED new {} collaborative build at {}", 
                steve.getSteveName(), structureType, clearPos);
        }
        
        steve.setFlying(true);
        
        SteveMod.LOGGER.info("Steve '{}' starting COLLABORATIVE build of {} at {} with {} blocks using materials: {} [FLYING ENABLED]", 
            steve.getSteveName(), structureType, clearPos, buildPlan.size(), buildMaterials);
    }
    
    /**
     * Find an active build of the same type that is NEARBY (within maxDistance blocks)
     * This prevents Steves from joining builds that are too far away
     */
    private CollaborativeBuildManager.CollaborativeBuild findNearbyActiveBuild(String structureType, int maxDistance) {
        CollaborativeBuildManager.CollaborativeBuild activeBuild = CollaborativeBuildManager.findActiveBuild(structureType);
        
        if (activeBuild == null) {
            return null;
        }
        
        // Check if the build is nearby
        double distance = Math.sqrt(steve.blockPosition().distSqr(activeBuild.startPos));
        
        if (distance <= maxDistance) {
            SteveMod.LOGGER.info("Found nearby active build at {} ({}m away)", activeBuild.startPos, (int)distance);
            return activeBuild;
        } else {
            SteveMod.LOGGER.info("Found active build at {} but it's too far away ({}m), will create new build nearby", 
                activeBuild.startPos, (int)distance);
            return null;
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false); // Disable flying on timeout
            result = ActionResult.failure("Building timeout");
            return;
        }
        
        if (isCollaborative) {
            // 尝试重新获取collaborativeBuild（如果为null）
            if (collaborativeBuild == null) {
                SteveMod.LOGGER.info("Steve '{}' lost collaborative build reference, trying to find nearby builds...", steve.getSteveName());
                collaborativeBuild = findNearbyActiveBuild(structureType, 50);
                
                if (collaborativeBuild == null) {
                    SteveMod.LOGGER.warn("Steve '{}' cannot find nearby builds, creating a new one...", steve.getSteveName());
                    // 尝试重新生成buildPlan并创建新的协作建造
                    if (buildPlan != null && !buildPlan.isEmpty()) {
                        List<CollaborativeBuildManager.BlockPlacement> collaborativeBlocks = new ArrayList<>();
                        for (BlockPlacement bp : buildPlan) {
                            collaborativeBlocks.add(new CollaborativeBuildManager.BlockPlacement(bp.pos, bp.block));
                        }
                        
                        BlockPos groundPos = findGroundLevel(steve.blockPosition());
                        if (groundPos != null) {
                            collaborativeBuild = CollaborativeBuildManager.registerBuild(structureType, collaborativeBlocks, groundPos);
                            SteveMod.LOGGER.info("Steve '{}' CREATED new {} collaborative build at {}", 
                                steve.getSteveName(), structureType, groundPos);
                        }
                    }
                }
                
                if (collaborativeBuild == null) {
                    // 仍然找不到建造，禁用飞行并传送到底部
                    steve.setFlying(false);
                    
                    // Teleport Steve to the ground to prevent falling from height
                    net.minecraft.core.BlockPos currentPos = steve.blockPosition();
                    net.minecraft.core.BlockPos groundPos = findGroundLevel(currentPos);
                    if (groundPos != null) {
                        steve.teleportTo(
                            groundPos.getX() + 0.5,
                            groundPos.getY() + 1.0,
                            groundPos.getZ() + 0.5
                        );
                        SteveMod.LOGGER.info("Steve '{}' teleported to ground after build error at {}", 
                            steve.getSteveName(), groundPos);
                    }
                    
                    result = ActionResult.failure("Build system error: cannot find or create collaborative build");
                    return;
                }
            }
            if (collaborativeBuild.isComplete()) {
                CollaborativeBuildManager.completeBuild(collaborativeBuild.structureId);
                steve.setFlying(false);
                
                // Teleport Steve to the ground to prevent falling from height
                net.minecraft.core.BlockPos currentPos = steve.blockPosition();
                net.minecraft.core.BlockPos groundPos = findGroundLevel(currentPos);
                if (groundPos != null) {
                    steve.teleportTo(
                        groundPos.getX() + 0.5,
                        groundPos.getY() + 1.0,
                        groundPos.getZ() + 0.5
                    );
                    SteveMod.LOGGER.info("Steve '{}' teleported to ground after completing build at {}", 
                        steve.getSteveName(), groundPos);
                }
                
                result = ActionResult.success("Built " + structureType + " collaboratively!");
                return;
            }
            
            for (int i = 0; i < BLOCKS_PER_TICK; i++) {
                CollaborativeBuildManager.BlockPlacement placement = 
                    CollaborativeBuildManager.getNextBlock(collaborativeBuild, steve.getSteveName());
                
                if (placement == null) {
                    if (ticksRunning % 20 == 0) {
                        SteveMod.LOGGER.info("Steve '{}' has no more blocks! Build {}% complete", 
                            steve.getSteveName(), collaborativeBuild.getProgressPercentage());
                    }
                    // 继续循环，不要break，这样Steve会继续飞行并在下一个tick尝试获取新的区块
                    // 只有当建造真正完成时才会结束
                    continue;
                }
                
                BlockPos pos = placement.pos;
                double distance = Math.sqrt(steve.blockPosition().distSqr(pos));
                
                // Only teleport if VERY far away (more than 20 blocks), otherwise let Steve fly there
                if (distance > 20) {
                    // Teleport to a safe position near the block (slightly above and to the side)
                    double targetX = pos.getX() + 0.5;
                    double targetY = pos.getY() + 1.5; // Slightly above the block
                    double targetZ = pos.getZ() + 0.5;
                    
                    steve.teleportTo(targetX, targetY, targetZ);
                    SteveMod.LOGGER.info("Steve '{}' teleported to {} (was {} blocks away)", 
                        steve.getSteveName(), pos, (int)distance);
                } else if (distance > 5) {
                    // Use navigation for medium distances
                    steve.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.5);
                }
                
                steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                
                steve.swing(InteractionHand.MAIN_HAND, true);
                
                BlockState existingState = steve.level().getBlockState(pos);
                
                BlockState blockState = placement.block.defaultBlockState();
                steve.level().setBlock(pos, blockState, 3);
                
                SteveMod.LOGGER.info("Steve '{}' PLACED BLOCK at {} - Total: {}/{}", 
                    steve.getSteveName(), pos, collaborativeBuild.getBlocksPlaced(), 
                    collaborativeBuild.getTotalBlocks());
                
                // Particles and sound
                if (steve.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        15, 0.4, 0.4, 0.4, 0.15
                    );
                    
                    var soundType = blockState.getSoundType(steve.level(), pos, steve);
                    steve.level().playSound(null, pos, soundType.getPlaceSound(), 
                        SoundSource.BLOCKS, 1.0f, soundType.getPitch());
                }
            }
            
            if (ticksRunning % 100 == 0 && collaborativeBuild.getBlocksPlaced() > 0) {
                int percentComplete = collaborativeBuild.getProgressPercentage();
                SteveMod.LOGGER.info("{} build progress: {}/{} ({}%) - {} Steves working", 
                    structureType, 
                    collaborativeBuild.getBlocksPlaced(), 
                    collaborativeBuild.getTotalBlocks(), 
                    percentComplete,
                    collaborativeBuild.participatingSteves.size());
            }
        } else {
            // Disable flying and teleport to ground to prevent falling
            steve.setFlying(false);
            
            // Teleport Steve to the ground to prevent falling from height
            net.minecraft.core.BlockPos currentPos = steve.blockPosition();
            net.minecraft.core.BlockPos groundPos = findGroundLevel(currentPos);
            if (groundPos != null) {
                steve.teleportTo(
                    groundPos.getX() + 0.5,
                    groundPos.getY() + 1.0,
                    groundPos.getZ() + 0.5
                );
                SteveMod.LOGGER.info("Steve '{}' teleported to ground after build error at {}", 
                    steve.getSteveName(), groundPos);
            }
            
            result = ActionResult.failure("Build system error: not in collaborative mode");
        }
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false); // Disable flying when cancelled
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Build " + structureType + " (" + currentBlockIndex + "/" + (buildPlan != null ? buildPlan.size() : 0) + ")";
    }

    private List<BlockPlacement> generateBuildPlan(String type, BlockPos start, int width, int height, int depth) {
        SteveMod.LOGGER.info("🏗️  Generating build plan for type='{}' at {} with dimensions {}x{}x{}", 
            type, start, width, height, depth);
        
        List<BlockPlacement> plan = switch (type.toLowerCase()) {
            case "house", "home" -> buildAdvancedHouse(start, width, height, depth);
            case "castle", "catle", "fort" -> buildCastle(start, width, height, depth);
            case "tower" -> buildAdvancedTower(start, width, height);
            case "wall" -> buildWall(start, width, height);
            case "platform" -> buildPlatform(start, width, depth);
            case "barn", "shed" -> buildBarn(start, width, height, depth);
            case "modern", "modern_house" -> buildModernHouse(start, width, height, depth);
            case "box", "cube" -> buildBox(start, width, height, depth);
            default -> {
                SteveMod.LOGGER.warn("Unknown structure type '{}', building advanced house", type);
                yield buildAdvancedHouse(start, Math.max(5, width), Math.max(4, height), Math.max(5, depth));
            }
        };
        
        if (plan == null || plan.isEmpty()) {
            SteveMod.LOGGER.error("❌ Generated build plan is NULL or EMPTY for type '{}'!", type);
        } else {
            SteveMod.LOGGER.info("✅ Generated build plan with {} blocks", plan.size());
        }
        
        return plan;
    }
    
    private Block getMaterial(int index) {
        return buildMaterials.get(index % buildMaterials.size());
    }

    private List<BlockPlacement> buildHouse(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block floorMaterial = getMaterial(0);
        Block wallMaterial = getMaterial(1);
        Block roofMaterial = getMaterial(2);
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), floorMaterial));
            }
        }
        
        for (int y = 1; y < height; y++) {
            for (int x = 0; x < width; x++) {
                blocks.add(new BlockPlacement(start.offset(x, y, 0), wallMaterial)); // Front wall
                blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), wallMaterial)); // Back wall
            }
            for (int z = 1; z < depth - 1; z++) {
                blocks.add(new BlockPlacement(start.offset(0, y, z), wallMaterial)); // Left wall
                blocks.add(new BlockPlacement(start.offset(width - 1, y, z), wallMaterial)); // Right wall
            }
        }
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, height, z), roofMaterial));
            }
        }
        
        return blocks;
    }

    private List<BlockPlacement> buildWall(BlockPos start, int width, int height) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = getMaterial(0);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                blocks.add(new BlockPlacement(start.offset(x, y, 0), material));
            }
        }
        return blocks;
    }

    private List<BlockPlacement> buildTower(BlockPos start, int width, int height) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = getMaterial(0);
        Block accentMaterial = getMaterial(1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    // Hollow tower with accent corners
                    if (x == 0 || x == width - 1 || z == 0 || z == width - 1) {
                        boolean isCorner = (x == 0 || x == width - 1) && (z == 0 || z == width - 1);
                        Block blockToUse = isCorner ? accentMaterial : material;
                        blocks.add(new BlockPlacement(start.offset(x, y, z), blockToUse));
                    }
                }
            }
        }
        return blocks;
    }

    private List<BlockPlacement> buildPlatform(BlockPos start, int width, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = getMaterial(0);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), material));
            }
        }
        return blocks;
    }

    private List<BlockPlacement> buildBox(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block material = getMaterial(0);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    blocks.add(new BlockPlacement(start.offset(x, y, z), material));
                }
            }
        }
        return blocks;
    }
    
    private List<BlockPlacement> buildAdvancedHouse(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        
        // === MODERN MATERIALS ===
        Block floorMaterial = Blocks.SMOOTH_STONE;  // 现代光滑地板
        Block wallMaterial = Blocks.QUARTZ_BLOCK;   // 白色石英墙壁
        Block accentWall = Blocks.DARK_OAK_PLANKS;  // 深色木质装饰墙
        Block roofMaterial = Blocks.DARK_OAK_PLANKS; // 深色屋顶
        Block windowMaterial = Blocks.GLASS;         // 大玻璃窗
        Block doorMaterial = Blocks.DARK_OAK_DOOR;   // 深色门
        
        // === FOUNDATION & FLOOR ===
        // 地基（更宽以支撑大房子）
        for (int x = -2; x <= width + 1; x++) {
            for (int z = -2; z <= depth + 1; z++) {
                blocks.add(new BlockPlacement(start.offset(x, -1, z), Blocks.POLISHED_ANDESITE));
            }
        }
        
        // 主地板
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), floorMaterial));
            }
        }
        
        // === MODERN WALLS WITH LARGE WINDOWS ===
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                // FRONT WALL - 大面积玻璃设计
                if ((x >= width / 2 - 1 && x <= width / 2) && y <= 2) {
                    // 双开门
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), doorMaterial));
                } else if (y >= 2 && y <= height && x >= 2 && x < width - 2) {
                    // 大玻璃窗
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), windowMaterial));
                } else if (x == 0 || x == width - 1) {
                    // 边角用装饰墙
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), accentWall));
                } else {
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), wallMaterial));
                }
                
                // BACK WALL - 装饰墙和窗户组合
                if (y >= 2 && y <= height && x >= 3 && x < width - 3) {
                    // 大面积玻璃窗
                    blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), windowMaterial));
                } else if (x == 0 || x == width - 1) {
                    blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), accentWall));
                } else {
                    blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), wallMaterial));
                }
            }
            
            for (int z = 1; z < depth - 1; z++) {
                // LEFT & RIGHT WALLS - 现代条纹设计
                boolean isWindow = (y >= 2 && y <= height && z >= 3 && z < depth - 3);
                
                if (isWindow) {
                    blocks.add(new BlockPlacement(start.offset(0, y, z), windowMaterial));
                    blocks.add(new BlockPlacement(start.offset(width - 1, y, z), windowMaterial));
                } else {
                    blocks.add(new BlockPlacement(start.offset(0, y, z), accentWall));
                    blocks.add(new BlockPlacement(start.offset(width - 1, y, z), accentWall));
                }
            }
        }
        
        // === MODERN FURNITURE & ROOMS ===
        
        // 1. 厨房区（西北角落）
        int kitchenStartX = 2;
        int kitchenStartZ = 2;
        int kitchenSize = 6;
        
        // 厨房专用地板
        for (int x = kitchenStartX; x < kitchenStartX + kitchenSize; x++) {
            for (int z = kitchenStartZ; z < kitchenStartZ + kitchenSize; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.LIGHT_GRAY_TERRACOTTA));
            }
        }
        
        // U形厨房柜台
        for (int x = kitchenStartX; x < kitchenStartX + kitchenSize; x++) {
            blocks.add(new BlockPlacement(start.offset(x, 1, kitchenStartZ), Blocks.POLISHED_BLACKSTONE));
            blocks.add(new BlockPlacement(start.offset(x, 1, kitchenStartZ + kitchenSize - 1), Blocks.POLISHED_BLACKSTONE));
        }
        for (int z = kitchenStartZ + 1; z < kitchenStartZ + kitchenSize - 1; z++) {
            blocks.add(new BlockPlacement(start.offset(kitchenStartX, 1, z), Blocks.POLISHED_BLACKSTONE));
        }
        
        // 厨房岛台
        for (int x = kitchenStartX + 2; x < kitchenStartX + kitchenSize - 2; x++) {
            for (int z = kitchenStartZ + 2; z < kitchenStartZ + kitchenSize - 2; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 1, z), Blocks.POLISHED_BLACKSTONE));
            }
        }
        
        // 厨房设备
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 1, 2, kitchenStartZ + 1), Blocks.CRAFTING_TABLE));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 2, 2, kitchenStartZ + 1), Blocks.FURNACE));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 3, 2, kitchenStartZ + 1), Blocks.SMOKER));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 4, 2, kitchenStartZ + 1), Blocks.BLAST_FURNACE));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 1, 2, kitchenStartZ + kitchenSize - 2), Blocks.CHEST));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 2, 2, kitchenStartZ + kitchenSize - 2), Blocks.BARREL));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 3, 2, kitchenStartZ + kitchenSize - 2), Blocks.BREWING_STAND));
        
        // 厨房椅子
        for (int x = kitchenStartX + 2; x < kitchenStartX + kitchenSize - 2; x++) {
            blocks.add(new BlockPlacement(start.offset(x, 1, kitchenStartZ - 1), Blocks.DARK_OAK_STAIRS));
        }
        
        // 2. 餐厅区（中央）
        int diningX = width / 2;
        int diningZ = depth / 2;
        int diningSize = 5;
        
        // 餐厅专用地板
        for (int x = diningX - diningSize/2; x <= diningX + diningSize/2; x++) {
            for (int z = diningZ - diningSize/2; z <= diningZ + diningSize/2; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.BROWN_TERRACOTTA));
            }
        }
        
        // 大型餐桌
        for (int x = diningX - 2; x <= diningX + 2; x++) {
            for (int z = diningZ - 1; z <= diningZ + 1; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 1, z), Blocks.DARK_OAK_PLANKS));
            }
        }
        
        // 餐桌装饰
        blocks.add(new BlockPlacement(start.offset(diningX, 2, diningZ), Blocks.POTTED_AZURE_BLUET));
        
        // 餐厅椅子
        blocks.add(new BlockPlacement(start.offset(diningX - 3, 1, diningZ), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX + 3, 1, diningZ), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX - 2, 1, diningZ - 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX - 1, 1, diningZ - 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX + 1, 1, diningZ - 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX + 2, 1, diningZ - 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX - 2, 1, diningZ + 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX - 1, 1, diningZ + 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX + 1, 1, diningZ + 2), Blocks.QUARTZ_STAIRS));
        blocks.add(new BlockPlacement(start.offset(diningX + 2, 1, diningZ + 2), Blocks.QUARTZ_STAIRS));
        
        // 3. 客厅区（东南角落）
        int livingStartX = width - 8;
        int livingStartZ = depth - 8;
        int livingSize = 7;
        
        // 客厅地毯
        for (int x = livingStartX; x < livingStartX + livingSize; x++) {
            for (int z = livingStartZ; z < livingStartZ + livingSize; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.RED_CARPET));
            }
        }
        
        // L形沙发
        // 主沙发
        for (int x = livingStartX + 1; x < livingStartX + livingSize - 1; x++) {
            blocks.add(new BlockPlacement(start.offset(x, 1, livingStartZ + livingSize - 1), Blocks.WHITE_WOOL));
            blocks.add(new BlockPlacement(start.offset(x, 2, livingStartZ + livingSize - 1), Blocks.QUARTZ_STAIRS));
        }
        // 侧沙发
        for (int z = livingStartZ + 1; z < livingStartZ + livingSize - 1; z++) {
            blocks.add(new BlockPlacement(start.offset(livingStartX, 1, z), Blocks.WHITE_WOOL));
            blocks.add(new BlockPlacement(start.offset(livingStartX, 2, z), Blocks.QUARTZ_STAIRS));
        }
        
        // 茶几
        for (int x = livingStartX + 3; x < livingStartX + 5; x++) {
            for (int z = livingStartZ + 3; z < livingStartZ + 5; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 1, z), Blocks.DARK_OAK_SLAB));
            }
        }
        
        // 电视和壁炉
        blocks.add(new BlockPlacement(start.offset(livingStartX + 3, 1, livingStartZ + livingSize - 2), Blocks.DARK_OAK_PLANKS));
        blocks.add(new BlockPlacement(start.offset(livingStartX + 4, 1, livingStartZ + livingSize - 2), Blocks.BRICKS));
        blocks.add(new BlockPlacement(start.offset(livingStartX + 3, 2, livingStartZ + livingSize - 2), Blocks.LANTERN));
        
        // 4. 卧室区（东北角落）
        int bedroomStartX = width - 8;
        int bedroomStartZ = 2;
        int bedroomSize = 7;
        
        // 卧室地毯
        for (int x = bedroomStartX; x < bedroomStartX + bedroomSize; x++) {
            for (int z = bedroomStartZ; z < bedroomStartZ + bedroomSize; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.BLUE_CARPET));
            }
        }
        
        // 大床
        for (int x = bedroomStartX + 2; x < bedroomStartX + 5; x++) {
            for (int z = bedroomStartZ + 2; z < bedroomStartZ + 4; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 1, z), Blocks.CYAN_BED));
            }
        }
        
        // 床头柜
        blocks.add(new BlockPlacement(start.offset(bedroomStartX + 1, 1, bedroomStartZ + 2), Blocks.DARK_OAK_PLANKS));
        blocks.add(new BlockPlacement(start.offset(bedroomStartX + 5, 1, bedroomStartZ + 2), Blocks.DARK_OAK_PLANKS));
        blocks.add(new BlockPlacement(start.offset(bedroomStartX + 1, 2, bedroomStartZ + 2), Blocks.LANTERN));
        blocks.add(new BlockPlacement(start.offset(bedroomStartX + 5, 2, bedroomStartZ + 2), Blocks.LANTERN));
        
        // 衣柜
        for (int y = 1; y <= 3; y++) {
            for (int x = bedroomStartX + 1; x < bedroomStartX + 5; x++) {
                blocks.add(new BlockPlacement(start.offset(x, y, bedroomStartZ + 5), Blocks.DARK_OAK_PLANKS));
            }
        }
        
        // 5. 书房区（西南角落）
        int studyStartX = 2;
        int studyStartZ = depth - 8;
        int studySize = 6;
        
        // 书房地板
        for (int x = studyStartX; x < studyStartX + studySize; x++) {
            for (int z = studyStartZ; z < studyStartZ + studySize; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.GREEN_CARPET));
            }
        }
        
        // 书桌
        for (int x = studyStartX + 2; x < studyStartX + 5; x++) {
            blocks.add(new BlockPlacement(start.offset(x, 1, studyStartZ + 3), Blocks.DARK_OAK_PLANKS));
        }
        
        // 椅子
        blocks.add(new BlockPlacement(start.offset(studyStartX + 3, 1, studyStartZ + 2), Blocks.DARK_OAK_STAIRS));
        
        // 书架墙
        for (int y = 1; y <= 3; y++) {
            for (int z = studyStartZ + 1; z < studyStartZ + 5; z++) {
                blocks.add(new BlockPlacement(start.offset(studyStartX, y, z), Blocks.BOOKSHELF));
            }
        }
        
        // === MODERN LIGHTING ===
        // 嵌入式天花板灯
        if (height >= 4) {
            for (int x = 4; x < width - 4; x += 5) {
                for (int z = 4; z < depth - 4; z += 5) {
                    blocks.add(new BlockPlacement(start.offset(x, height, z), Blocks.SEA_LANTERN));
                }
            }
        }
        
        // 墙壁装饰灯
        for (int x = 3; x < width - 3; x += 6) {
            blocks.add(new BlockPlacement(start.offset(x, 3, 1), Blocks.LANTERN));
            blocks.add(new BlockPlacement(start.offset(x, 3, depth - 2), Blocks.LANTERN));
        }
        for (int z = 3; z < depth - 3; z += 6) {
            blocks.add(new BlockPlacement(start.offset(1, 3, z), Blocks.LANTERN));
            blocks.add(new BlockPlacement(start.offset(width - 2, 3, z), Blocks.LANTERN));
        }
        
        // 餐厅和客厅吊灯
        blocks.add(new BlockPlacement(start.offset(diningX, height, diningZ), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(diningX - 1, height - 1, diningZ), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(diningX + 1, height - 1, diningZ), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(diningX, height - 1, diningZ - 1), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(diningX, height - 1, diningZ + 1), Blocks.SEA_LANTERN));
        
        int livingCenterX = livingStartX + livingSize / 2;
        int livingCenterZ = livingStartZ + livingSize / 2;
        blocks.add(new BlockPlacement(start.offset(livingCenterX, height, livingCenterZ), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(livingCenterX - 1, height - 1, livingCenterZ), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(livingCenterX + 1, height - 1, livingCenterZ), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(livingCenterX, height - 1, livingCenterZ - 1), Blocks.SEA_LANTERN));
        blocks.add(new BlockPlacement(start.offset(livingCenterX, height - 1, livingCenterZ + 1), Blocks.SEA_LANTERN));
        
        // === DECORATIONS ===
        // 装饰植物
        blocks.add(new BlockPlacement(start.offset(3, 1, 3), Blocks.POTTED_BAMBOO));
        blocks.add(new BlockPlacement(start.offset(width - 4, 1, 3), Blocks.POTTED_FERN));
        blocks.add(new BlockPlacement(start.offset(3, 1, depth - 4), Blocks.POTTED_FERN));
        blocks.add(new BlockPlacement(start.offset(width - 4, 1, depth - 4), Blocks.POTTED_FERN));
        blocks.add(new BlockPlacement(start.offset(kitchenStartX + 5, 1, kitchenStartZ + 3), Blocks.POTTED_AZURE_BLUET));
        blocks.add(new BlockPlacement(start.offset(bedroomStartX + 3, 1, bedroomStartZ + 1), Blocks.POTTED_POPPY));
        blocks.add(new BlockPlacement(start.offset(studyStartX + 3, 1, studyStartZ + 1), Blocks.POTTED_DANDELION));
        
        // 墙壁装饰
        blocks.add(new BlockPlacement(start.offset(width / 2, 2, 1), Blocks.CHISELED_QUARTZ_BLOCK));
        blocks.add(new BlockPlacement(start.offset(width / 2, 2, depth - 2), Blocks.CHISELED_QUARTZ_BLOCK));
        blocks.add(new BlockPlacement(start.offset(1, 2, depth / 2), Blocks.QUARTZ_PILLAR));
        blocks.add(new BlockPlacement(start.offset(width - 2, 2, depth / 2), Blocks.QUARTZ_PILLAR));
        
        // === EXTERIOR ===
        // 大型门廊
        for (int x = width / 2 - 3; x <= width / 2 + 3; x++) {
            for (int z = -2; z < 0; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.POLISHED_ANDESITE));
            }
        }
        
        // 门廊柱子和灯
        for (int y = 0; y <= 3; y++) {
            blocks.add(new BlockPlacement(start.offset(width / 2 - 4, y, -1), Blocks.DARK_OAK_FENCE));
            blocks.add(new BlockPlacement(start.offset(width / 2 + 4, y, -1), Blocks.DARK_OAK_FENCE));
        }
        blocks.add(new BlockPlacement(start.offset(width / 2 - 4, 4, -1), Blocks.LANTERN));
        blocks.add(new BlockPlacement(start.offset(width / 2 + 4, 4, -1), Blocks.LANTERN));
        
        // 门廊屋顶
        for (int x = width / 2 - 5; x <= width / 2 + 5; x++) {
            for (int z = -2; z < 1; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 4, z), roofMaterial));
            }
        }
        
        // 窗台花盆
        for (int x = 3; x < width - 3; x += 6) {
            blocks.add(new BlockPlacement(start.offset(x, 2, -1), Blocks.POTTED_POPPY));
        }
        
        // === MODERN FLAT ROOF ===
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, height + 1, z), roofMaterial));
            }
        }
        
        // 屋顶边缘装饰
        for (int x = 0; x < width; x++) {
            blocks.add(new BlockPlacement(start.offset(x, height + 2, 0), Blocks.DARK_OAK_SLAB));
            blocks.add(new BlockPlacement(start.offset(x, height + 2, depth - 1), Blocks.DARK_OAK_SLAB));
        }
        for (int z = 1; z < depth - 1; z++) {
            blocks.add(new BlockPlacement(start.offset(0, height + 2, z), Blocks.DARK_OAK_SLAB));
            blocks.add(new BlockPlacement(start.offset(width - 1, height + 2, z), Blocks.DARK_OAK_SLAB));
        }
        
        // 屋顶花园
        if (width >= 12 && depth >= 12) {
            int roofGardenX = width / 2;
            int roofGardenZ = depth / 2;
            // 花园区域
            for (int x = roofGardenX - 2; x <= roofGardenX + 2; x++) {
                for (int z = roofGardenZ - 2; z <= roofGardenZ + 2; z++) {
                    blocks.add(new BlockPlacement(start.offset(x, height + 2, z), Blocks.GRASS_BLOCK));
                }
            }
            // 花园植物
            blocks.add(new BlockPlacement(start.offset(roofGardenX, height + 3, roofGardenZ), Blocks.POTTED_AZALEA));
            blocks.add(new BlockPlacement(start.offset(roofGardenX - 1, height + 3, roofGardenZ - 1), Blocks.POTTED_BAMBOO));
            blocks.add(new BlockPlacement(start.offset(roofGardenX + 1, height + 3, roofGardenZ - 1), Blocks.POTTED_FERN));
            blocks.add(new BlockPlacement(start.offset(roofGardenX - 1, height + 3, roofGardenZ + 1), Blocks.POTTED_POPPY));
            blocks.add(new BlockPlacement(start.offset(roofGardenX + 1, height + 3, roofGardenZ + 1), Blocks.POTTED_DANDELION));
        }
        
        SteveMod.LOGGER.info("Built modern house with {} blocks including furniture and decorations", blocks.size());
        
        return blocks;
    }
    
    private List<BlockPlacement> buildCastle(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block stoneMaterial = Blocks.STONE_BRICKS;
        Block wallMaterial = Blocks.COBBLESTONE;
        Block accentMaterial = getMaterial(2); // Use third material for accent
        Block windowMaterial = Blocks.GLASS_PANE;
        
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    boolean isCorner = (x <= 2 || x >= width - 3) && (z <= 2 || z >= depth - 3);
                    
                    if (y == 0) {
                        // Solid stone floor
                        blocks.add(new BlockPlacement(start.offset(x, y, z), stoneMaterial));
                    } else if (isEdge && !isCorner) {
                        if (x == width / 2 && z == 0 && y <= 3) {
                            if (y >= 1 && y <= 3 && x >= width / 2 - 1 && x <= width / 2 + 1) {
                                blocks.add(new BlockPlacement(start.offset(x, y, 0), Blocks.AIR));
                            }
                        } else if (y % 4 == 2 && !isCorner) {
                            // Arrow slit windows
                            blocks.add(new BlockPlacement(start.offset(x, y, z), windowMaterial));
                        } else {
                            // Thick stone walls
                            blocks.add(new BlockPlacement(start.offset(x, y, z), wallMaterial));
                        }
                    }
                }
            }
        }
        
        int towerHeight = height + 6; // Much taller towers
        int towerSize = 3;
        int[][] corners = {{0, 0}, {width - towerSize, 0}, {0, depth - towerSize}, {width - towerSize, depth - towerSize}};
        
        for (int[] corner : corners) {
            for (int y = 0; y <= towerHeight; y++) {
                for (int dx = 0; dx < towerSize; dx++) {
                    for (int dz = 0; dz < towerSize; dz++) {
                        boolean isTowerEdge = (dx == 0 || dx == towerSize - 1 || dz == 0 || dz == towerSize - 1);
                        
                        if (y == 0 || isTowerEdge) {
                            // Solid base and hollow center
                            blocks.add(new BlockPlacement(start.offset(corner[0] + dx, y, corner[1] + dz), stoneMaterial));
                        }
                        
                        // Windows on towers
                        if (y % 5 == 3 && isTowerEdge && (dx == towerSize / 2 || dz == towerSize / 2)) {
                            blocks.add(new BlockPlacement(start.offset(corner[0] + dx, y, corner[1] + dz), windowMaterial));
                        }
                    }
                }
            }
            for (int dx = 0; dx < towerSize; dx++) {
                for (int dz = 0; dz < towerSize; dz++) {
                    if (dx % 2 == 0 || dz % 2 == 0) {
                        blocks.add(new BlockPlacement(start.offset(corner[0] + dx, towerHeight + 1, corner[1] + dz), stoneMaterial));
                    }
                }
            }
        }
        for (int x = 0; x < width; x += 2) {
            blocks.add(new BlockPlacement(start.offset(x, height + 1, 0), stoneMaterial));
            blocks.add(new BlockPlacement(start.offset(x, height + 2, 0), stoneMaterial));
            blocks.add(new BlockPlacement(start.offset(x, height + 1, depth - 1), stoneMaterial));
            blocks.add(new BlockPlacement(start.offset(x, height + 2, depth - 1), stoneMaterial));
        }
        for (int z = 0; z < depth; z += 2) {
            blocks.add(new BlockPlacement(start.offset(0, height + 1, z), stoneMaterial));
            blocks.add(new BlockPlacement(start.offset(0, height + 2, z), stoneMaterial));
            blocks.add(new BlockPlacement(start.offset(width - 1, height + 1, z), stoneMaterial));
            blocks.add(new BlockPlacement(start.offset(width - 1, height + 2, z), stoneMaterial));
        }
        
        return blocks;
    }
    
    private List<BlockPlacement> buildModernHouse(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block wallMaterial = Blocks.QUARTZ_BLOCK;
        Block floorMaterial = Blocks.SMOOTH_STONE;
        Block glassMaterial = Blocks.GLASS;
        Block roofMaterial = Blocks.DARK_OAK_PLANKS;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), floorMaterial));
            }
        }
        
        // Modern design with lots of glass
        for (int y = 1; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Front - mostly glass
                if (x % 2 == 0 || y > 1) {
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), glassMaterial));
                } else {
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), wallMaterial));
                }
                
                blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), wallMaterial));
            }
            
            for (int z = 1; z < depth - 1; z++) {
                // Side walls with some glass
                if (z % 3 == 1 && y == 2) {
                    blocks.add(new BlockPlacement(start.offset(0, y, z), glassMaterial));
                    blocks.add(new BlockPlacement(start.offset(width - 1, y, z), glassMaterial));
                } else {
                    blocks.add(new BlockPlacement(start.offset(0, y, z), wallMaterial));
                    blocks.add(new BlockPlacement(start.offset(width - 1, y, z), wallMaterial));
                }
            }
        }
        
        // Flat modern roof
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, height, z), roofMaterial));
            }
        }
        
        return blocks;
    }
    
    private List<BlockPlacement> buildBarn(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block woodMaterial = Blocks.OAK_PLANKS;
        Block logMaterial = Blocks.OAK_LOG;
        Block roofMaterial = Blocks.SPRUCE_PLANKS;
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), woodMaterial));
            }
        }
        
        for (int y = 1; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean isSupport = (x == 0 || x == width - 1 || x == width / 2);
                Block material = isSupport ? logMaterial : woodMaterial;
                
                // Large door opening in front
                if (x >= width / 3 && x <= 2 * width / 3 && y <= 2) {
                    continue; // Skip for large opening
                }
                
                blocks.add(new BlockPlacement(start.offset(x, y, 0), material));
                blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), material));
            }
            
            for (int z = 1; z < depth - 1; z++) {
                blocks.add(new BlockPlacement(start.offset(0, y, z), logMaterial));
                blocks.add(new BlockPlacement(start.offset(width - 1, y, z), logMaterial));
            }
        }
        
        // Tall peaked roof
        int roofPeakHeight = height + width / 2;
        for (int x = 0; x < width; x++) {
            int distFromCenter = Math.abs(x - width / 2);
            int roofY = roofPeakHeight - distFromCenter;
            
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, roofY, z), roofMaterial));
            }
        }
        
        return blocks;
    }
    
    private List<BlockPlacement> buildAdvancedTower(BlockPos start, int width, int height) {
        List<BlockPlacement> blocks = new ArrayList<>();
        Block wallMaterial = Blocks.STONE_BRICKS;
        Block accentMaterial = Blocks.CHISELED_STONE_BRICKS;
        Block windowMaterial = Blocks.GLASS_PANE;
        Block roofMaterial = Blocks.DARK_OAK_STAIRS;
        
        // Main tower body
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == width - 1);
                    boolean isCorner = (x == 0 || x == width - 1) && (z == 0 || z == width - 1);
                    
                    if (y == 0) {
                        blocks.add(new BlockPlacement(start.offset(x, y, z), wallMaterial));
                    } else if (isEdge) {
                        // Windows every few levels
                        if (y % 3 == 2 && !isCorner && (x == width / 2 || z == width / 2)) {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), windowMaterial));
                        } else if (isCorner) {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), accentMaterial));
                        } else {
                            blocks.add(new BlockPlacement(start.offset(x, y, z), wallMaterial));
                        }
                    }
                }
            }
        }
        
        for (int i = 0; i < width / 2 + 1; i++) {
            for (int x = i; x < width - i; x++) {
                for (int z = i; z < width - i; z++) {
                    if (x == i || x == width - 1 - i || z == i || z == width - 1 - i) {
                        blocks.add(new BlockPlacement(start.offset(x, height + i, z), roofMaterial));
                    }
                }
            }
        }
        
        return blocks;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        ResourceLocation resourceLocation = new ResourceLocation(blockName);
        Block block = BuiltInRegistries.BLOCK.get(resourceLocation);
        return block != null ? block : Blocks.AIR;
    }
    
    /**
     * Find the actual ground level from a starting position
     * Scans downward to find solid ground, or upward if underground
     */
    private BlockPos findGroundLevel(BlockPos startPos) {
        int maxScanDown = 20; // Scan up to 20 blocks down
        int maxScanUp = 10;   // Scan up to 10 blocks up if we're underground
        
        // First, try scanning downward to find ground
        for (int i = 0; i < maxScanDown; i++) {
            BlockPos checkPos = startPos.below(i);
            BlockPos belowPos = checkPos.below();
            
            if (steve.level().getBlockState(checkPos).isAir() && 
                isSolidGround(belowPos)) {
                return checkPos; // This is ground level
            }
        }
        
        // Scan upward to find the surface
        for (int i = 1; i < maxScanUp; i++) {
            BlockPos checkPos = startPos.above(i);
            BlockPos belowPos = checkPos.below();
            
            if (steve.level().getBlockState(checkPos).isAir() && 
                isSolidGround(belowPos)) {
                return checkPos;
            }
        }
        
        // but make sure there's something solid below
        BlockPos fallbackPos = startPos;
        while (!isSolidGround(fallbackPos.below()) && fallbackPos.getY() > -64) {
            fallbackPos = fallbackPos.below();
        }
        
        return fallbackPos;
    }
    
    /**
     * Check if a position has solid ground suitable for building
     */
    private boolean isSolidGround(BlockPos pos) {
        var blockState = steve.level().getBlockState(pos);
        var block = blockState.getBlock();
        
        // Not solid if it's air or liquid
        if (blockState.isAir() || block == Blocks.WATER || block == Blocks.LAVA) {
            return false;
        }
        
        return blockState.isSolid();
    }
    
    /**
     * Find a suitable building site with flat, clear ground
     * Searches for an area that is:
     * - Relatively flat (max 2 block height difference)
     * - Clear of obstructions (trees, rocks, etc.)
     * - Has enough vertical space for the structure
     */
    private BlockPos findSuitableBuildingSite(BlockPos startPos, int width, int height, int depth) {
        int maxSearchRadius = 10;
        int searchStep = 3; // Small steps to stay nearby
        
        if (isAreaSuitable(startPos, width, height, depth)) {
            return startPos;
        }        // Search in expanding circles
        for (int radius = searchStep; radius < maxSearchRadius; radius += searchStep) {
            for (int angle = 0; angle < 360; angle += 45) { // Check every 45 degrees
                double radians = Math.toRadians(angle);
                int offsetX = (int) (Math.cos(radians) * radius);
                int offsetZ = (int) (Math.sin(radians) * radius);
                
                BlockPos testPos = new BlockPos(
                    startPos.getX() + offsetX,
                    startPos.getY(),
                    startPos.getZ() + offsetZ
                );
                
                BlockPos groundPos = findGroundLevel(testPos);
                if (groundPos != null && isAreaSuitable(groundPos, width, height, depth)) {
                    SteveMod.LOGGER.info("Found suitable flat ground at {} ({}m away)", groundPos, radius);
                    return groundPos;
                }
            }
        }
        
        SteveMod.LOGGER.warn("Could not find suitable flat ground within {}m", maxSearchRadius);
        return null;
    }
    
    /**
     * Check if an area is suitable for building
     * - Must be relatively flat (max 2 block height variation)
     * - Must be clear of obstructions above ground
     * - Must have solid ground below
     */
    private boolean isAreaSuitable(BlockPos startPos, int width, int height, int depth) {
        // Sample key points in the build area to check terrain
        int samples = 0;
        int maxSamples = 9; // Check 9 points (corners + center + midpoints)
        int unsuitable = 0;
        
        BlockPos[] checkPoints = {
            startPos,                                    // Front-left corner
            startPos.offset(width - 1, 0, 0),           // Front-right corner
            startPos.offset(0, 0, depth - 1),           // Back-left corner
            startPos.offset(width - 1, 0, depth - 1),   // Back-right corner
            startPos.offset(width / 2, 0, depth / 2),   // Center
            startPos.offset(width / 2, 0, 0),           // Front-center
            startPos.offset(width / 2, 0, depth - 1),   // Back-center
            startPos.offset(0, 0, depth / 2),           // Left-center
            startPos.offset(width - 1, 0, depth / 2)    // Right-center
        };
        
        int minY = startPos.getY();
        int maxY = startPos.getY();
        
        for (BlockPos checkPos : checkPoints) {
            samples++;
            
            if (!isSolidGround(checkPos.below())) {
                unsuitable++;
                continue;
            }
            
            BlockPos actualGround = findGroundLevel(checkPos);
            if (actualGround != null) {
                minY = Math.min(minY, actualGround.getY());
                maxY = Math.max(maxY, actualGround.getY());
            }
            
            for (int y = 1; y <= Math.min(height, 3); y++) {
                BlockPos abovePos = checkPos.above(y);
                var blockState = steve.level().getBlockState(abovePos);
                
                if (!blockState.isAir()) {
                    Block block = blockState.getBlock();
                    if (block != Blocks.GRASS && block != Blocks.TALL_GRASS && 
                        block != Blocks.FERN && block != Blocks.DEAD_BUSH &&
                        block != Blocks.DANDELION && block != Blocks.POPPY) {
                        unsuitable++;
                        break;
                    }
                }
            }
        }
        
        int heightVariation = maxY - minY;
        if (heightVariation > 2) {
            SteveMod.LOGGER.debug("Area at {} too uneven ({}m height difference)", startPos, heightVariation);
            return false;
        }
        
        // Area is suitable if less than 30% of samples are problematic
        boolean suitable = unsuitable < (maxSamples * 0.3);
        
        if (!suitable) {
            SteveMod.LOGGER.debug("Area at {} has too many obstructions ({}/{})", startPos, unsuitable, samples);
        }
        
        return suitable;
    }
    
    /**
     * Try to load structure from NBT template file
     * Returns null if no template found (falls back to procedural generation)
     */
    private List<BlockPlacement> tryLoadFromTemplate(String structureName, BlockPos startPos) {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        
        var template = StructureTemplateLoader.loadFromNBT(serverLevel, structureName);
        if (template == null) {
            return null;
        }
        
        List<BlockPlacement> blocks = new ArrayList<>();
        for (var templateBlock : template.blocks) {
            BlockPos worldPos = startPos.offset(templateBlock.relativePos);
            Block block = templateBlock.blockState.getBlock();
            blocks.add(new BlockPlacement(worldPos, block));
        }
        
        return blocks;
    }
    
    /**
     * Find the nearest player to build in front of
     */
    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = steve.level().players();
        
        if (players.isEmpty()) {
            return null;
        }
        
        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            
            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        
        return nearest;
    }
    
}
