package com.dsh.maidbrain;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动搭话（DSH 追加能力）。
 * 通过服务器 tick 事件观察状况，在「主人就在附近」时让女仆主动开口：
 * 自己受伤 / 主人重伤 / 入夜 / 打雷下雨 / 附近刷怪。
 * 复用 TLM 的气泡接口 ChatBubbleManager.addTextChatBubble，每只女仆 60 秒冷却，避免刷屏。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProactiveChatHandler {
    private static final long COOLDOWN_MS = 60_000L;
    private static final double NEAR = 24.0D;
    private static final Map<UUID, Long> LAST_SPOKEN = new ConcurrentHashMap<>();
    private static int tickCounter = 0;

    private ProactiveChatHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % 40 != 0) { // 每 2 秒检查一次，开销可忽略
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                List<EntityMaid> maids = level.getEntitiesOfClass(EntityMaid.class,
                        player.getBoundingBox().inflate(NEAR));
                for (EntityMaid maid : maids) {
                    if (!player.getUUID().equals(maid.getOwnerUUID()) || !maid.isAlive()) {
                        continue;
                    }
                    String line = pickLine(maid, player, level);
                    if (line != null) {
                        speak(maid, line);
                    }
                }
            }
        }
    }

    private static String pickLine(EntityMaid maid, ServerPlayer player, ServerLevel level) {
        if (maid.getHealth() <= maid.getMaxHealth() * 0.35F) {
            return "主人……我受了些伤，稍等我缓一缓。";
        }
        if (player.getHealth() <= player.getMaxHealth() * 0.30F) {
            return "主人！你伤得不轻，快退后，让我挡在前面。";
        }
        long dayTime = level.getDayTime() % 24000L;
        List<Monster> hostiles = level.getEntitiesOfClass(Monster.class, maid.getBoundingBox().inflate(12.0D));
        if (!hostiles.isEmpty()) {
            return "附近有敌人出没，主人小心些。";
        }
        if (dayTime >= 13000L && dayTime < 13200L) {
            return "天黑了，主人。需要我去点几支火把吗？";
        }
        if (level.isThundering()) {
            return "这雷雨天可真吵……主人，别站在空地上。";
        }
        if (level.isRaining()) {
            return "下雨了呢，主人。记得别淋太久。";
        }
        return null;
    }

    private static void speak(EntityMaid maid, String line) {
        long now = System.currentTimeMillis();
        Long last = LAST_SPOKEN.get(maid.getUUID());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        LAST_SPOKEN.put(maid.getUUID(), now);
        try {
            maid.getChatBubbleManager().addTextChatBubble(line);
            MaidBrainMod.LOGGER.info("proactive line from {}: {}", maid.getUUID(), line);
        } catch (Exception e) {
            MaidBrainMod.LOGGER.warn("proactive bubble failed: {}", e.toString());
        }
    }
}
