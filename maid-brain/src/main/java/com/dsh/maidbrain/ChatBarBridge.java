package com.dsh.maidbrain;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

/**
 * 聊天栏直连（DSH 追加能力）。
 * TLM 1.5.3 原生只有两种输入：AI 对话窗口的输入框、语音快捷键；
 * 普通聊天栏的消息不会被转给 LLM。这里监听 ServerChatEvent，
 * 把玩家在聊天栏说的话转交给最近的、属于该玩家的女仆的 AI 聊天管线，
 * 从而让"在聊天栏直接对女仆说话"成立。
 * 依赖公开接口：MaidAIChatManager#chat(String, ChatClientInfo, ServerPlayer)。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChatBarBridge {
    /** 与 TLM 配置 MaidCanChatDistance 默认值保持一致 */
    private static final double SEARCH_RANGE = 12.0;

    private ChatBarBridge() {
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String text = event.getRawText();
        if (text == null || text.isBlank()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        List<EntityMaid> nearby = level.getEntitiesOfClass(EntityMaid.class,
                player.getBoundingBox().inflate(SEARCH_RANGE));
        nearby.stream()
                .filter(maid -> maid.isAlive() && maid.isOwnedBy(player))
                .min(Comparator.comparingDouble(maid -> maid.distanceToSqr(player)))
                .ifPresent(maid -> {
                    try {
                        maid.getAiChatManager().chat(text, ChatClientInfo.fromMaid(maid), player);
                        // 与主人交谈：更新交谈时间，且每 5 分钟首次交谈小幅涨好感
                        String key = player.getUUID().toString();
                        long last = com.dsh.maidbrain.memory.RelationStore.of(key).lastTalk;
                        com.dsh.maidbrain.memory.RelationStore.touchTalk(key);
                        if (System.currentTimeMillis() - last > 300_000L) {
                            com.dsh.maidbrain.memory.RelationStore.add(key, 1);
                        }
                        MaidBrainMod.LOGGER.info("[chat-bar] delivered to maid {}: {}", maid.getUUID(), text);
                    } catch (Exception e) {
                        MaidBrainMod.LOGGER.error("[chat-bar] delivery failed: {}", e.toString());
                    }
                });
    }
}
