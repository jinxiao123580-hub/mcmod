package com.dsh.maidbrain;

import com.dsh.maidbrain.memory.RelationStore;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自主行动：守护祝福（好感度驱动，概率触发）。
 * 她会在主人遇险时按概率主动施加对应的药水效果：
 * 触发概率 = 基础概率 + (好感 - 解锁线) × 0.4%，好感越高越可靠；
 * 判定失败不消耗冷却（下一秒可再判定），真出手才进入冷却。
 * 40+ 濒死再生 / 50+ 防火、缓落 / 61+ 危险时力量、（她自己濒死自愈） / 71+ 饥饿时饱和 / 81+ 幸运守护。
 * 好感 40 以下（生分/冷淡）她不会出手——闹脾气的一部分。
 * 每类祝福独立冷却，全部走服务端主线程 tick（每秒一次，开销可忽略）。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MaidBlessingHandler {
    private static final double RANGE = 24.0D;
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();
    private static int tickCounter = 0;

    private MaidBlessingHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter % 20 != 0) { // 每秒一次
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                EntityMaid maid = level.getEntitiesOfClass(EntityMaid.class,
                                player.getBoundingBox().inflate(RANGE)).stream()
                        .filter(m -> m.isAlive() && m.isOwnedBy(player))
                        .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)))
                        .orElse(null);
                if (maid == null) {
                    continue;
                }
                int aff = RelationStore.score(player.getUUID().toString());
                blessMaster(maid, player, aff);
                selfCare(maid, aff);
                dailyGift(maid, player, aff, level);
            }
        }
    }

    private static void blessMaster(EntityMaid maid, ServerPlayer p, int aff) {
        if (aff < 40) {
            return; // 生分/冷淡时她闹脾气，不出手
        }
        // 濒死：再生 II 15s（好感 61+ 追加伤害吸收护盾）——基础 35%
        if (p.getHealth() <= p.getMaxHealth() * 0.30 && tryFire(p.getUUID(), "regen", 90_000, 0.35F, aff, 40)) {
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 15 * 20, 1, false, true));
            if (aff >= 61) {
                p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 60 * 20, 0, false, true));
            }
            say(maid, "主人撑住！我为你祈愿了再生之力！");
        }
        // 着火/岩浆：防火 30s——基础 30%
        if (aff >= 50 && p.isOnFire() && tryFire(p.getUUID(), "fire", 120_000, 0.30F, aff, 50)) {
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 30 * 20, 0, false, true));
            say(maid, "小心火烛！防火的祝福给你。");
        }
        // 高空坠落：缓降 10s——基础 85%（落地前必须尽快，保持高概率）
        if (aff >= 50 && p.fallDistance > 3.5F && tryFire(p.getUUID(), "feather", 30_000, 0.85F, aff, 50)) {
            p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 10 * 20, 0, false, true));
            say(maid, "主人小心落地！");
        }
        // 敌怪逼近：力量 30s——基础 25%
        if (aff >= 61 && tryFire(p.getUUID(), "strength", 120_000, 0.25F, aff, 61)) {
            List<Monster> hostiles = p.serverLevel()
                    .getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(12.0D));
            if (!hostiles.isEmpty()) {
                p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 30 * 20, 0, false, true));
                say(maid, "有敌袭！力量祝福给你，别硬撑。");
            }
        }
        // 饥肠辘辘：71+ 无中生有变点心，否则给饱和
        if (p.getFoodData().getFoodLevel() <= 6 && tryFire(p.getUUID(), "food", 180_000, 0.60F, aff, 40)) {
            if (aff >= 71) {
                p.getInventory().add(new ItemStack(Items.BREAD, 3));
                say(maid, "点心…变！快趁热吃。");
            } else {
                p.addEffect(new MobEffectInstance(MobEffects.SATURATION, 20, 0, false, true));
                say(maid, "饿着肚子可不行……");
            }
        }
        // 夜路没火把：无中生有给火把
        if (aff >= 50 && p.serverLevel().isNight() && playerTorchCount(p) == 0
                && tryFire(p.getUUID(), "torch", 600_000, 0.50F, aff, 50)) {
            p.getInventory().add(new ItemStack(Items.TORCH, 8));
            say(maid, "火把…拿着，夜里别走丢了。");
        }
        // 危急召唤：敌怪 3+ 且主人半血以下 → 召一只铁傀儡护卫（10 分钟冷却，附近已有则不召）
        if (aff >= 61 && tryFire(p.getUUID(), "golem", 600_000, 0.35F, aff, 61)) {
            List<Monster> hostiles2 = p.serverLevel()
                    .getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(10.0D));
            if (hostiles2.size() >= 3 && p.getHealth() <= p.getMaxHealth() * 0.5F
                    && p.serverLevel().getEntitiesOfClass(IronGolem.class,
                            p.getBoundingBox().inflate(32.0D)).isEmpty()) {
                BlockPos pos = p.blockPosition().offset(2, 1, 0);
                IronGolem golem = EntityType.IRON_GOLEM.spawn(p.serverLevel(), pos, MobSpawnType.EVENT);
                if (golem != null) {
                    golem.setPersistenceRequired();
                    say(maid, "大家伙，去保护主人！");
                }
            }
        }
        // 威慑：71+ 时给周围敌怪上虚弱（她护短的另一面）
        if (aff >= 71 && tryFire(p.getUUID(), "scare", 90_000, 0.30F, aff, 71)) {
            List<Monster> near = p.serverLevel()
                    .getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(8.0D));
            if (!near.isEmpty()) {
                for (Monster m : near) {
                    m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 20, 0, false, true));
                }
                say(maid, "别想碰主人一根手指！");
            }
        }
        // 挚爱守护：白天出行时给予幸运 5 分钟——基础 20%
        if (aff >= 81 && p.serverLevel().isDay() && tryFire(p.getUUID(), "luck", 600_000, 0.20F, aff, 81)) {
            p.addEffect(new MobEffectInstance(MobEffects.LUCK, 300 * 20, 0, false, true));
            say(maid, "今天的运气也由我来守护！");
        }
    }

    private static void selfCare(EntityMaid maid, int aff) {
        if (aff < 40) {
            return;
        }
        if (maid.getHealth() <= maid.getMaxHealth() * 0.35 && tryFire(maid.getUUID(), "self", 120_000, 0.50F, aff, 40)) {
            maid.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 15 * 20, 1, false, true));
            say(maid, "我没事……稍微治疗一下就好。");
        }
    }

    /**
     * 概率 + 冷却判定：冷却中直接 false；
     * 否则掷骰（概率随好感小幅提升，封顶 90%），中了才写入冷却。
     */
    private static boolean tryFire(UUID id, String type, long cooldownMs, float baseChance, int aff, int minAff) {
        String key = id + ":" + type;
        long now = System.currentTimeMillis();
        Long last = COOLDOWNS.get(key);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        float chance = Math.min(0.90F, baseChance + Math.max(0, aff - minAff) * 0.004F);
        if (maid_random().nextFloat() >= chance) {
            return false;
        }
        COOLDOWNS.put(key, now);
        return true;
    }

    private static java.util.Random RAND;

    private static synchronized java.util.Random maid_random() {
        if (RAND == null) {
            RAND = new java.util.Random();
        }
        return RAND;
    }

    /** 从女仆背包里挑符合条件的物品递给主人（背包装不下则退回） */
    private static int playerTorchCount(ServerPlayer p) {
        int n = 0;
        for (ItemStack st : p.getInventory().items) {
            if (st.is(Items.TORCH)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** 每日礼物：每个游戏日一次，无中生有随机送（全注册表，含所有模组），好感只影响数量 */
    private static void dailyGift(EntityMaid maid, ServerPlayer p, int aff, ServerLevel level) {
        long day = level.getDayTime() / 24000L;
        String id = maid.getStringUUID();
        if (com.dsh.maidbrain.memory.GiftStore.isGiven(id, day)) {
            return;
        }
        Item item = randomGiftItem();
        if (item == null) {
            return;
        }
        int count = 1 + maid_random().nextInt(1 + aff / 20);
        ItemStack stack = new ItemStack(item, count);
        if (!p.getInventory().add(stack)) {
            p.drop(stack, false);
        }
        com.dsh.maidbrain.memory.GiftStore.mark(id, day);
        say(maid, "主人，今日份的礼物：「" + stack.getHoverName().getString() + "」x" + count + "，请收下！");
    }

    /** 礼物池：全部注册物品，剔除技术性/无用物品 */
    private static volatile List<Item> giftPool;

    private static Item randomGiftItem() {
        if (giftPool == null) {
            synchronized (MaidBlessingHandler.class) {
                if (giftPool == null) {
                    List<Item> pool = new ArrayList<>();
                    for (Item item : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValues()) {
                        if (item == Items.AIR) {
                            continue;
                        }
                        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
                        String path = key == null ? "" : key.getPath();
                        if (path.contains("command_block") || path.contains("structure_block")
                                || path.equals("barrier") || path.equals("bedrock")
                                || path.equals("jigsaw") || path.equals("debug_stick")
                                || path.equals("light") || path.endsWith("_spawn_egg")) {
                            continue;
                        }
                        pool.add(item);
                    }
                    giftPool = pool;
                }
            }
        }
        List<Item> pool = giftPool;
        return pool.isEmpty() ? Items.BREAD : pool.get(maid_random().nextInt(pool.size()));
    }

    private static void say(EntityMaid maid, String line) {
        try {
            maid.getChatBubbleManager().addTextChatBubble(line);
        } catch (Exception ignored) {
        }
        MaidBrainMod.LOGGER.info("blessing from {}: {}", maid.getUUID(), line);
    }
}
