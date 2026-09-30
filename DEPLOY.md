# 女仆 AI 大脑 · 跨整合包部署手册（v0.21.0）

把「车万女仆 + LLM 大脑 + 11 个自定义工具」部署到**任何**整合包。  
一条命令：`D:\DSH\maid-ai\deploy.ps1 -ModsDir '<目标包实例的 mods 目录>'`

## 一、前提条件

| 项 | 要求 | 说明 |
|---|---|---|
| Minecraft | 1.20.1 Forge | 模组按 1.20.1 官方映射编译 |
| 车万女仆 TLM | **1.5.3**（1.5.x 应该都行） | 部署脚本会检查 mods 里有没有 TLM jar |
| JDK 17 | `D:\DSH\tools\jdk17\...`（可用 `-JavaHome` 覆盖） | 编译用 |
| 构建依赖 | `maid-brain\libs\touhoulittlemaid-1.20.1-all.jar` | 目标包的 TLM 版本若不同，把它的 jar 拷进 libs 并同步改 build.gradle 的 `fg.deobf` 坐标后重编 |

**绝不装第二个 TLM**：maid_brain 是 TLM 的扩展（走官方 `ILittleMaid` 扩展点），不是替代品。

## 二、部署步骤

```powershell
# 例：部署到另一个整合包
D:\DSH\maid-ai\deploy.ps1 -ModsDir 'E:\MC\.minecraft\versions\某整合包\mods'

# 已有编译好的 jar，跳过编译
D:\DSH\maid-ai\deploy.ps1 -ModsDir '...' -Jar 'D:\DSH\maid-ai\maid-brain\build\libs\maid_brain-forge-0.18.0.jar'
```

脚本会：① 检查 TLM 存在 → ② 编译最新版 → ③ 清掉**所有**旧 maid_brain jar、装入新的 → ④ 打印游戏内配置清单。

## 三、游戏内配置（每个包一次）

1. 进世界，日志确认这两行：
   ```
   Registered 11 maid_brain tools: scout_report / run_command / search_pack_guide / query_recipe / scan_world / maid_memory / do_work / maid_tasks / maid_waypoint / maid_inventory / maid_relation
   Registered maid_memory + maid_tasks + maid_relation prompt contexts (maid_brain)
   ```
2. **大脑**：手持「AI 记忆簿」对驯服的女仆使用 → 站点管理 → 新建 OpenAI 类型 LLM 站点（DeepSeek 的 URL/Key，模型如 `deepseek-v4-flash`）→ 设为聊天站点。
3. （可选）**语音**：同界面建 TTS 站点（OpenAI 类型，硅基流动 `FunAudioLLM/CosyVoice2-0.5B` 免费）。
4. 验收：聊天栏直接对她说话；`/maidload on`；`/maidchat 你好吗`。

## 四、能力速览（0.18.0）

**工具**：生物雷达 `scout_report` · 世界扫描 `scan_world` · 指令执行 `run_command` · 配方查询 `query_recipe` · **整合包资料 `search_pack_guide`（任务书 + 全模组物品中英文索引）** · 长期记忆 `maid_memory` · 干活 `do_work`（22 种工作）· 待办 `maid_tasks` · 地标 `maid_waypoint` · 物流 `maid_inventory` · 好感 `maid_relation`

**事件层**：聊天栏直连 · 主动搭话（受伤/入夜/雷雨/敌怪）· 被打掉好感 · 常加载跟随 · `/maidchat` 全图对话 · **自主祝福（好感+概率驱动：濒死再生/防火/缓降/敌袭力量/幸运/变点心/递火把/危急召唤铁傀儡/威慑敌怪）** · **每日礼物（每游戏日随机无中生有送一件全注册表物品，含所有模组，好感只影响数量 1~6）**

**提示词注入**（每条请求自动携带）：长期记忆 · 待办清单 · 好感度

**数据文件**（每个实例独立，`config\maid_brain\`）：`memory.json` `tasks.json` `waypoints.json` `relation.json` `loaded.json` `modindex.json`（物品索引，mods 变动自动重建，游戏启动后台构建）

## 五、排障

| 症状 | 处理 |
|---|---|
| 日志没有 "Registered 11 maid_brain tools" | jar 没装上/游戏没重启；确认 mods 里只有一个 maid_brain |
| 编译报找不到 TLM 类 | 目标包 TLM 版本不同 → 更新 `libs\` 里的 TLM jar 与 build.gradle 坐标 |
| LLM 400 报 schema/tool_call 错 | 0.9.0+ 已修复，确认装的是新版 |
| 女仆不认识某模组物品 | `search_pack_guide` 用物品中文名再试；看日志 mod index built 条数 |
| 物品索引还没建好 | 首次启动后台构建需几秒到几十秒（看包大小），稍后再问 |
| 手改 json 中文乱码 | **别用 PowerShell 5.1 编辑文本**，用记事本/VSCode（UTF-8） |

## 六、更新流程

代码在 `D:\DSH\maid-ai\maid-brain`（改完提版本号 build.gradle），对每个包重跑一遍 deploy.ps1 即可。数据文件不受更新影响。
