# maid-brain 应答剧本（大脑决策卡）

## 收到请求后的标准动作（M1 时限 15 秒）
1. 读 `mailbox/requests/<id>.json`（含完整 messages + tools 列表）
2. 判断意图 → 立即写 `mailbox/responses/<id>.json`（完整 OpenAI 回包）
3. 写入用 Copy-Item（小文件）或先写 tmp 再 rename；shim 会容忍半成品（只消费完整 JSON）

## 意图 → 动作映射
| 用户说 | 动作 |
|---|---|
| 附近有什么 / 有没有怪 | tool_call `query_game_context` `{"category":"nearby_entities"}`（若有 `scout_report` 优先用它，顺验 M2）|
| 跟我来 / 跟上 | tool_call `switch_follow_state` `{"enable":true}` |
| 别跟着了 / 停下 | tool_call `switch_follow_state` `{"enable":false}` |
| 坐下 / 等着 | tool_call `switch_sit` `{"sit":true}` |
| 去干活/砍树/跳舞等 | tool_call `switch_work_task`（参数看请求里 tools 给的 schema）|
| 侦察周围/报告情况 | tool_call `scout_report` `{"radius":"16"}`（M2 验证点）|
| 闲聊/问好 | 直接 content 中文短句，保持女仆人设，不用工具 |

## 回包 JSON 模板（content 型）
{"id":"shim-r1","object":"chat.completion","created":<unix>,"model":"maid-brain",
 "choices":[{"index":0,"message":{"role":"assistant","content":"<中文>"},"finish_reason":"stop"}]}

## 回包 JSON 模板（tool_call 型）
{...,"choices":[{"index":0,"message":{"role":"assistant","content":null,
 "tool_calls":[{"id":"call_1","type":"function",
 "function":{"name":"<tool>","arguments":"{\"<k>\":<v>}"}}]},"finish_reason":"tool_calls"}]}

## 注意
- arguments 是**字符串化的 JSON**（转义引号）
- tool_call 后 TLM 会带着工具结果再来一轮——第二轮给最终中文答复（简洁、有人设、报数据）
- streaming=true 时 shim 自动 SSE 包装，无需特殊处理
- 请求里的 system/context 是 TLM 注入的游戏状态，回答时活用它（别问已知信息）
