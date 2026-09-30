# Maid Brain — 女仆 AI 大脑附属（Touhou Little Maid addon）

给「车万女仆」(Touhou Little Maid 1.5.x / Forge 1.20.1) 接入 LLM 大脑，并为此提供 **11 个自定义 AI 工具 + 3 组自动注入的提示词上下文 + 多层自主行为**。零 Mixin，全部走 TLM 官方 `ILittleMaid` 扩展点。

## 功能

### 11 个 AI 工具（女仆在对话中可调用）
| 工具 | 作用 |
| --- | --- |
| `scout_report` | 生物雷达：敌/友/玩家按名分组，带距离 |
| `scan_world` | 扫描世界：结构/群系 `/locate`、方块/矿石（调色板预筛，省负载） |
| `run_command` | 指令执行器（时间/天气/召唤/传送/给物品，危险指令拦截） |
| `query_recipe` | 实时配方查询：合成/用途/查找/**怪物掉落**/**村民交易**，配方带工作方块类型 |
| `search_pack_guide` | **整合包内部检索**：任务书全文 + 全模组物品/方块/实体中英文索引 |
| `maid_memory` | 长期记忆（存/读/忘，跨会话） |
| `do_work` | 切工作（复用 TLM 22 种内置工作）+ goto/status/stop |
| `maid_tasks` | 待办清单（增/查/完成/删/清理） |
| `maid_waypoint` | 地标：save/list/goto/tp/tp_player/remove |
| `maid_inventory` | 物流：她的背包查看、存/取箱子、递交物品给主人 |
| `maid_relation` | 好感度汇报/互动（好感影响脾气与是否干活） |

### 3 组自动注入提示词上下文
每次 AI 请求自动携带：**长期记忆 / 待办清单 / 好感度**。

### 自主行为层（好感度 + 概率驱动）
- **主动搭话**：受伤/入夜/雷雨/敌怪时主动开口（每女仆冷却）
- **守护祝福**：濒死回血、防火、缓降、敌袭力量、幸运等 buff（好感≥40 才出手，档位越高越多，概率触发）
- **每日赠物**：每游戏日随机无中生有送一件全注册表物品
- **危急召唤**：怪物包围时召铁傀儡护卫、威慑敌怪
- **常加载跟随**：她所在的区块强制加载，人不在也继续干活
- **被打掉好感**：主人打她会掉好感并当场反应

### 聊天/指令
- **聊天栏直连**：普通聊天栏对最近女仆说话
- `/maidchat <消息>`：**全图**任意维度与女仆对话
- `/maidload [on|off]`：常加载开关

## 数据
`config/maid_brain/`：`memory.json`（记忆）、`tasks.json`（待办）、`waypoints.json`（地标）、`relation.json`（好感）、`loaded.json`（常加载名单）、`gifts.json`（每日礼物记录）、`modindex.json`（物品索引，mods 变动自动重建）。全都跨会话保留、可手改。

## 构建
依赖两个放在 `libs/` 的构件（已在仓库内，clone 即用）：

| 文件 | 说明 |
| --- | --- |
| `touhoulittlemaid-1.20.1-all.jar` | 车万女仆的 dev/userdev 构件（`-all` 后缀） |
| `maid_useful_task-1.4.0.jar` | 女仆实用任务 |

车万女仆要用 **dev 构件**（带 `-all`），不是混淆过的玩家版 jar。然后：

```bash
./gradlew build      # 产物在 build/libs/
```

## 部署到任意整合包
见同仓库根目录的 [`deploy.ps1`](../deploy.ps1) 与 [`DEPLOY.md`](../DEPLOY.md)：一条命令检查 TLM → 编译 → 装入指定 mods 目录（支持换机、换包、`-JavaHome` 覆盖）。

## 运行依赖
- Minecraft 1.20.1
- Forge 47+
- Touhou Little Maid 1.5.2+
- 可选：大脑站点（DeepSeek 等 OpenAI 兼容），在游戏内「AI 记忆簿」配置

## 代码结构
- `com.dsh.maidbrain/tool/` 全部自定义工具
- `com.dsh.maidbrain/memory/` 持久化存储 + 提示词上下文
- `ChatBarBridge` 聊天栏桥 · `MaidCommands` 指令 · `MaidBlessingHandler` 自主祝福/赠礼 ·
  `ProactiveChatHandler` 主动搭话 · `MaidChunkLoader` 常加载 · `RelationEvents` 好感事件

## 许可
MIT