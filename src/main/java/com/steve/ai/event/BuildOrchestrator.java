package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.action.CollaborativeBuildManager;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Random;

@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BuildOrchestrator {
    private static int tickCounter = 0;
    private static final int CHECK_INTERVAL = 100; // ticks (~5 seconds)
    private static final int DESIRED_TEAM_SIZE = 4;
    private static final Random RANDOM = new Random();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter % CHECK_INTERVAL != 0) return;

        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ServerLevel level = server.overworld();
        if (level == null) return;

        SteveManager manager = SteveMod.getSteveManager();

        // Iterate active collaborative builds and ensure enough workers
        for (CollaborativeBuildManager.CollaborativeBuild build : CollaborativeBuildManager.getActiveBuilds()) {
            int participating = build.participatingSteves.size();

            if (participating >= DESIRED_TEAM_SIZE) continue;

            String structType = build.structureId.split("_")[0];

            // spawn missing workers up to desired size
            for (int i = participating; i < DESIRED_TEAM_SIZE; i++) {
                String name = "Builder_" + structType + "_" + i;
                if (manager.getSteve(name) != null) continue; // already present

                double offsetX = (RANDOM.nextDouble() - 0.5) * 4.0;
                double offsetZ = (RANDOM.nextDouble() - 0.5) * 4.0;
                Vec3 spawnPos = new Vec3(build.startPos.getX() + 0.5 + offsetX, build.startPos.getY() + 1.0, build.startPos.getZ() + 0.5 + offsetZ);

                SteveEntity steve = manager.spawnSteve(level, spawnPos, name);
                if (steve != null) {
                    SteveMod.LOGGER.info("Spawned {} to assist collaborative build {} ({} workers now)", name, build.structureId, build.participatingSteves.size());
                    // Give the new Steve a direct build command so it will join the collaborative build
                    steve.getActionExecutor().processNaturalLanguageCommand("build " + structType);
                } else {
                    SteveMod.LOGGER.warn("Failed to spawn {} to assist build {}", name, build.structureId);
                }

                // Safety: break if we've reached desired team size
                if (build.participatingSteves.size() >= DESIRED_TEAM_SIZE) break;
            }
        }
    }
}
