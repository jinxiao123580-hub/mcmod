package com.dsh.maidbrain;

import com.dsh.maidbrain.memory.LoadedStore;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆常加载：名单（loaded.json）里的女仆所在区块被强制加载。
 * 复用原版 forceload 机制（ServerLevel#setChunkForced），随女仆移动自动换区块，
 * forceload 票据保存在存档里，跨重启仍有效。
 * 每 1 秒检查一次，开销可忽略。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidChunkLoader {
    private record ChunkKey(String dim, int x, int z) {
    }

    /** uuid → 我们最后一次为其强载的区块（用于移动后释放旧区块） */
    private static final Map<UUID, ChunkKey> LAST = new HashMap<>();
    private static int tickCounter = 0;

    private MaidChunkLoader() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % 20 != 0) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
                if (e instanceof EntityMaid maid && maid.isAlive()
                        && LoadedStore.contains(maid.getStringUUID())) {
                    track(maid);
                }
            }
        }
    }

    /** /maidload on 或例行检查：确保该女仆当前区块被强载 */
    public static void track(EntityMaid maid) {
        ServerLevel level = (ServerLevel) maid.level();
        ChunkKey now = new ChunkKey(level.dimension().location().toString(),
                maid.chunkPosition().x, maid.chunkPosition().z);
        ChunkKey old = LAST.get(maid.getUUID());
        if (now.equals(old)) {
            return;
        }
        if (old != null) {
            ServerLevel oldLevel = levelOf(old.dim());
            if (oldLevel != null) {
                oldLevel.setChunkForced(old.x, old.z, false);
            }
        }
        level.setChunkForced(now.x, now.z, true);
        LAST.put(maid.getUUID(), now);
        MaidBrainMod.LOGGER.info("chunk-load {} @ [{},{}] ({})", maid.getUUID(), now.x, now.z, now.dim);
    }

    /** /maidload off：释放该女仆的强载区块 */
    public static void release(EntityMaid maid) {
        ChunkKey old = LAST.remove(maid.getUUID());
        if (old != null) {
            ServerLevel oldLevel = levelOf(old.dim());
            if (oldLevel != null) {
                oldLevel.setChunkForced(old.x, old.z, false);
            }
        }
    }

    private static ServerLevel levelOf(String dim) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        var rl = net.minecraft.resources.ResourceLocation.tryParse(dim);
        if (rl == null) {
            return null;
        }
        return server.getLevel(net.minecraft.resources.ResourceKey
                .create(net.minecraft.core.registries.Registries.DIMENSION, rl));
    }
}
