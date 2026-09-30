package com.dsh.maidbrain.memory;

import com.dsh.maidbrain.MaidBrainMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 模组内容索引：把 mods 里每个 jar 的语言文件（zh_cn / en_us）全部抽出，
 * 形成「中文名/英文名 → modid:name」的检索表。女仆因此认识整合包里所有物品、方块、实体、流体。
 * 首次建库较慢（后台线程）；结果缓存到 config/maid_brain/modindex.json，
 * mods 目录内容不变（指纹相同）则直接读缓存。
 */
@Mod.EventBusSubscriber(modid = MaidBrainMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ModContentIndex {
    public static final class Entry {
        public String id;
        public String zh;
        public String en;
        public String mod;
        public String jar;

        public Entry() {
        }

        public Entry(String id, String zh, String en, String mod, String jar) {
            this.id = clean(id);
            this.zh = clean(zh);
            this.en = clean(en);
            this.mod = clean(mod);
            this.jar = clean(jar);
        }

        /** 清洗孤立代理对等非法字符：个别模组语言文件含残缺的 \\uDxxx 转义，会让 UTF-8 写盘炸掉 */
        private static String clean(String s) {
            return s == null ? null : s.replaceAll("[\\uD800-\\uDFFF]", "?");
        }
    }

    private static final class Cache {
        public long fingerprint;
        public List<Entry> entries = new ArrayList<>();
    }

    private static final Pattern LANG_RE = Pattern.compile("assets/([^/]+)/lang/(zh_cn|en_us)\\.json");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path CACHE_FILE = FMLPaths.CONFIGDIR.get().resolve("maid_brain").resolve("modindex.json");
    private static volatile boolean ready = false;
    private static volatile List<Entry> entries = List.of();
    private static volatile long lastBuildMs = 0;

    private ModContentIndex() {
    }

    /** 服务器启动后在后台建库，不阻塞游戏 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Thread worker = new Thread(() -> {
            try {
                rebuildIfNeeded();
            } catch (Exception e) {
                MaidBrainMod.LOGGER.warn("mod index build failed: {}", e.toString());
            }
        }, "maid-brain-modindex");
        worker.setDaemon(true);
        worker.start();
    }

    /** 检索：中文名 / 英文名 / 注册 id 任一包含关键词即命中 */
    public static List<String> search(String query, int limit) {
        ensureReady();
        String q = query.trim();
        String lower = q.toLowerCase();
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            boolean hit = (e.zh != null && e.zh.contains(q))
                    || (e.en != null && e.en.toLowerCase().contains(lower))
                    || (e.id != null && e.id.toLowerCase().contains(lower));
            if (hit) {
                StringBuilder sb = new StringBuilder();
                sb.append(e.zh == null ? e.id : e.zh);
                if (e.en != null && !e.en.isBlank() && !e.en.equals(e.zh)) {
                    sb.append(" (").append(e.en).append(")");
                }
                sb.append(" = ").append(e.id).append("  [").append(e.jar).append("]");
                out.add(sb.toString());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    public static int size() {
        ensureReady();
        return entries.size();
    }

    private static synchronized void ensureReady() {
        if (!ready) {
            try {
                rebuildIfNeeded();
            } catch (Exception e) {
                MaidBrainMod.LOGGER.warn("mod index lazy build failed: {}", e.toString());
            }
        }
    }

    private static void rebuildIfNeeded() throws IOException {
        long now = System.currentTimeMillis();
        // 防抖：一分钟内不重复扫描
        if (now - lastBuildMs < 60_000L && ready) {
            return;
        }
        Path modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
        long fingerprint = fingerprint(modsDir);
        if (ready && loadFingerprint() == fingerprint) {
            return;
        }
        long t0 = System.currentTimeMillis();
        Cache cache = new Cache();
        cache.fingerprint = fingerprint;
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> jars = Files.list(modsDir)) {
                jars.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .forEach(jar -> scanJar(jar, cache.entries));
            }
        }
        entries = cache.entries;
        ready = true;
        lastBuildMs = System.currentTimeMillis();
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            Files.writeString(CACHE_FILE, GSON.toJson(cache), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MaidBrainMod.LOGGER.warn("modindex.json 写入失败: {}", e.toString());
        }
        MaidBrainMod.LOGGER.info("mod index built: {} entries from mods, {} ms (cache {})",
                cache.entries.size(), System.currentTimeMillis() - t0, CACHE_FILE);
    }

    private static void scanJar(Path jar, List<Entry> out) {
        String jarName = jar.getFileName().toString();
        // 每个模组 id 的 zh / en 两张表
        Map<String, Map<String, String>> zh = new HashMap<>();
        Map<String, Map<String, String>> en = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry ze = it.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                Matcher m = LANG_RE.matcher(ze.getName());
                if (!m.matches()) {
                    continue;
                }
                String modid = m.group(1);
                boolean isZh = "zh_cn".equals(m.group(2));
                try {
                    String raw = new String(zip.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, String> parsed = GSON.fromJson(raw,
                            new TypeToken<LinkedHashMap<String, String>>() {
                            }.getType());
                    if (parsed == null) {
                        continue;
                    }
                    (isZh ? zh : en).computeIfAbsent(modid, k -> new HashMap<>()).putAll(parsed);
                } catch (Exception ignored) {
                    // 单个语言文件损坏不影响整体
                }
            }
        } catch (Exception e) {
            MaidBrainMod.LOGGER.debug("skip jar {}: {}", jarName, e.toString());
            return;
        }
        // 只收注册类条目：物品/方块/实体/流体
        for (Map.Entry<String, Map<String, String>> modEntry : zh.entrySet()) {
            String modid = modEntry.getKey();
            Map<String, String> zhMap = modEntry.getValue();
            Map<String, String> enMap = en.getOrDefault(modid, Map.of());
            for (Map.Entry<String, String> kv : zhMap.entrySet()) {
                String key = kv.getKey();
                String path = registryPath(modid, key);
                if (path == null) {
                    continue;
                }
                String zhVal = trim(kv.getValue());
                String enVal = trim(enMap.get(key));
                if (zhVal == null && enVal == null) {
                    continue;
                }
                out.add(new Entry(modid + ":" + path, zhVal, enVal, modid, jarName));
            }
        }
    }

    /** "item.modid.name" → "name"；只接受 item./block./entity./fluid. 前缀 */
    private static String registryPath(String modid, String key) {
        for (String prefix : new String[]{"item.", "block.", "entity.", "fluid."}) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                if (rest.startsWith(modid + ".")) {
                    return rest.substring(modid.length() + 1);
                }
                return rest; // 少数模组不带 modid 段
            }
        }
        return null;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** mods 目录指纹：文件名+大小列表的散列，内容变了才重建索引 */
    private static long fingerprint(Path modsDir) throws IOException {
        long h = 1;
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> files = Files.list(modsDir)) {
                List<Path> sorted = new ArrayList<>(files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).toList());
                sorted.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
                for (Path p : sorted) {
                    try {
                        h = 31 * h + (p.getFileName().toString().hashCode() ^ Files.size(p));
                    } catch (IOException ignored) {
                        // 读不到大小的跳过
                    }
                }
            }
        }
        return h;
    }

    private static long loadFingerprint() {
        try {
            if (Files.exists(CACHE_FILE)) {
                Cache cache = GSON.fromJson(Files.readString(CACHE_FILE, StandardCharsets.UTF_8), Cache.class);
                if (cache != null) {
                    // 缓存命中：直接载入条目，跳过扫描
                    if (cache.entries != null && !cache.entries.isEmpty()) {
                        entries = cache.entries;
                        ready = true;
                    }
                    return cache.fingerprint;
                }
            }
        } catch (Exception e) {
            MaidBrainMod.LOGGER.warn("modindex.json 读取失败: {}", e.toString());
        }
        return Long.MIN_VALUE;
    }
}
