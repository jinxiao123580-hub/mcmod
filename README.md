# maid-ai · 女仆 AI 大脑（Maid Brain）

给「车万女仆」(Touhou Little Maid) 接入 LLM 大脑的整合开发仓库：11 个自定义 AI 工具、自动注入提示词上下文、多层自主行为（主动搭话 / 守护祝福 / 每日赠礼 / 常加载 / 好感度），并带一键部署脚本，可在任意 1.20.1 Forge 整合包中重复部署。

## 目录
- `maid-brain/` — 模组本体（Forge 1.20.1 + TLM 1.5.x），详见其 [`README.md`](maid-brain/README.md)
- `deploy.ps1` — 一键部署脚本：检查 TLM → 编译 → 装入指定 mods 目录（换机/换包/`-JavaHome`）
- `DEPLOY.md` — 跨整合包部署手册（前提、步骤、能力速览、排障、更新）
- `BRAIN-PLAYBOOK.md` — 开发/运维手册
- `watcher.ps1` + `start-shim.ps1` — 备用本地 shim（OpenAI 兼容，可替换大脑来源）用的哨兵与启动脚本
- `shim.js` — 本地 OpenAI 兼容 shim（Player2 备用大脑通道）

## 快速开始
```powershell
# 部署到某整合包
.\deploy.ps1 -ModsDir '<目标包>\mods'

# 其他电脑接续
git clone https://github.com/jinxiao123580-hub/mcmod.git
cd mcmod
.\deploy.ps1 -ModsDir '<目标包>\mods'      # JDK 路径不同加 -JavaHome '<路径>'
```

## 说明
- 大脑本身在游戏内「AI 记忆簿」配置（DeepSeek 等 OpenAI 兼容站点）；`libs/` 已含编译所需的 TLM / 有用任务构件，clone 即用。
- 数据位于各实例 `config/maid_brain/`，跨会话保留，可手改。
- 许可：MIT。