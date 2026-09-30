package com.dsh.maidbrain;

import com.dsh.maidbrain.memory.RelationStore;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 关系事件层：主人打女仆会掉好感（-8），并当场给出一句反应。
 * 与 RelationTool 的 praise/scold/gift 一起构成好感度的增减入口。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RelationEvents {
    private RelationEvents() {
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) {
            return;
        }
        var source = event.getSource();
        var attacker = source.getEntity();
        if (attacker == null || !attacker.getUUID().equals(maid.getOwnerUUID())) {
            return;
        }
        String key = maid.getOwnerUUID() == null ? "unknown" : maid.getOwnerUUID().toString();
        int score = RelationStore.add(key, -8);
        try {
            maid.getChatBubbleManager().addTextChatBubble("呜……主人为什么打我？");
        } catch (Exception ignored) {
        }
        MaidBrainMod.LOGGER.info("relation: owner hit maid, affection → {}", score);
    }
}
