# Figma 房间页面节点映射

> 文件：`AI Native 履约争端审理系统 — Light Cognitive Field`
>
> 本表只记录已通过 `get_design_context` 与 `get_screenshot` 双重检查的节点。
>
> 2026-07-04 校准：Docker 当前运行的 Vue 页面是视觉母版；Figma 只用于沉淀设计系统、
> 页面节点和后续映射，不再另起一套视觉方向。此前 Figwright 从零起稿的总览页降级为
> 草稿/归档，不作为验收源。

## 设计基线

- 产品字体：以代码基线 `Inter / PingFang SC / Microsoft YaHei` 为准；Figma 使用
  文件中实际可用的同类中文字体并在节点验收时回读。
- 色彩：暖白、天蓝、珊瑚橙、嫩芽绿、柔和紫。
- 数字人状态：`IDLE / LISTENING / THINKING / SPEAKING / COMPLETED / HANDOFF / ERROR`。
- 人机边界：AI 只提供非最终建议，平台审核员拥有最终落槌权。

## 页面与节点

| 路由/用途 | 角色/状态 | 视口 | Figma 页面 | 节点 ID | Vue 映射 | 上下文 | 截图 |
|---|---|---:|---|---|---|---|---|
| `/disputes` | USER，默认多状态 | 1440×1024 | `01 Frontend Source / Overview` / `7:94` | 结构化母版初稿，待精修验收 | `DisputeOverviewView` | 已回读（非最终） | 已回读（非最终，参考 Docker 截图） |
| `/disputes` | USER，移动端 | 390×844 | 待创建 | 待验收 | `DisputeOverviewView` | 待验收 | 待验收 |
| `/disputes/:caseId/intake` | USER/MERCHANT | 1440×1024 | 待按 Docker 前端母版创建 | 待验收 | `IntakeRoomView` | 待回读 | 截图待补 |
| `/disputes/:caseId/evidence` | USER/MERCHANT | 1440×1024 | `02 Frontend Source / Evidence Room` / `8:120` | 结构化母版初稿，待精修验收 | `EvidenceRoomView` | 已回读（非最终） | 已回读（非最终），前端参考图：`docker-evidence-room-viewport-2026-07-04.png` |
| `/disputes/:caseId/hearing` | USER/MERCHANT | 1440×1024 | `03 Frontend Source / Hearing Court` / `8:121` | 结构化母版初稿，待精修验收 | `HearingCourtView` | 已回读（非最终），并完成前端 DOM 回读 | 已回读（非最终） |
| `/disputes/:caseId/hearing` | PLATFORM_REVIEWER，只读 | 1440×1024 | 待创建 | 待验收 | `HearingCourtView` | 待验收 | 待验收 |
| `/reviews/:reviewId` | PLATFORM_REVIEWER | 1440×1024 | 待创建 | 待验收 | `ReviewWorkbenchView` | 待验收 | 待验收 |
| `/disputes/:caseId/outcome` | USER/MERCHANT | 1440×1024 | 待创建 | 待验收 | `OutcomeView` | 待验收 | 待验收 |
| 全局信箱 | USER/MERCHANT/REVIEWER | 420×760 抽屉 | 待创建 | 待验收 | `SummonsMailbox` | 待验收 | 待验收 |

## 验收规则

每行只有在以下证据齐备后才从“待验收”改为实际值：

1. 精确节点已调用 `get_design_context`；
2. 精确节点已调用 `get_screenshot`；
3. 截图无裁切、重叠、占位文本和暗黑/传统后台风格；
4. 字体、角色权限、AI 非最终提示与服务端倒计时语义通过回读；
5. Vue 页面实现后在相同视口完成浏览器截图比对。
