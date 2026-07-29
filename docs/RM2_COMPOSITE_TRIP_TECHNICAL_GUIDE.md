# RM2 多订单复合行程技术指南

> 更新时间：2026-07-29  
> 适用后端：dashboard-v2 / 8e2e5c4  
> 总体交接文档：../../TECHNICAL_DOCUMENTATION.md

## 1. 目标

当同一车辆在有效时间内存在多张开放订单时，系统输出一个稳定的车辆 Trip、一条包含全部有效节点的规划路线，以及一组可审计的业务里程碑。多订单不会继续与旧单订单路线拼接。

核心要求：

- 每张原订单内部严格先取后送。
- 可以先送已载订单，再取另一张订单。
- 同址卸货和装货必须先卸后装。
- 已完成订单整链退出开放 Trip。
- 真实车与模拟车使用同一套轨迹和状态判断。
- 后端节点顺序、路线途经点、前端面板和地图钉来自同一 Trip 快照。

## 2. 关键类

| 类 | 职责 |
|---|---|
| VehicleOrderChainStore | 日库差分、车辆审计事件、正式/推断状态并存 |
| VehicleOrderEligibilityService | 聚合车辆订单、轨迹推进、生成 Trip 决策 |
| VehicleTripRuntimeService | Trip 身份、成员集合、阶段、当前 Leg、持久化 |
| VehicleTripTopologyService | Stop、Node、合法顺序、Leg 与计划版本 |
| TownRoadRenderService | 复合路线组装、途经点去重、元数据注入 |
| TownRoadMiddleLayer | 调用路线规划、规范化、生成 RM2 路线 |
| RoutePlanningService | 百度优先、高德回退、进程内 TTL 缓存 |
| Rm2GroupQueryService | 原子快照、分组/路线/位置查询 |
| RoutePushService | 真实和模拟位置、历史样本、路线进度 |
| RouteDtoConverter | 输出 RenderRouteDTO 与完整 tripStops |

## 3. 数据流

~~~text
外部订单快照
  → 展开到车辆实例
  → 坐标补全
  → VehicleOrderChainStore.ingest
  → 清理已完成订单链
  → VehicleTripRuntimeService.reconcile
  → VehicleTripTopologyService.build
  → 轨迹证据推进到站/停留/离站
  → 动态插单与未完成后缀重排
  → TownRoadRenderService 生成单条复合路线
  → 百度/高德按全部物理节点规划
  → RenderRouteDTO.meta.tripStops
  → RM2 原子快照
~~~

## 4. 标识与集合

- orderInstanceId：订单 + 线路 + 车辆实例的稳定键。
- tripId：车辆当前复合行程身份。
- runtimeLineId：后端路线运行身份。
- visualKey：前端场景车辆身份，不能直接用变化的 lineId 替代。
- pendingPickupOrderIds：尚未装货的订单。
- onboardOrderIds：已经装车、等待卸货的订单。
- completedOrderIds：已完成订单；reconcile 后不再属于开放 Trip。
- plannedStopIds：未完成 Stop 的执行顺序。
- currentLegId：当前车辆所在 Leg。
- planVersion：节点顺序变化次数，不随普通 GPS 位移变化。

## 5. Stop、Node 与 Leg

每张确认订单生成一个 PICKUP 和一个 DELIVERY Stop。Stop 表示业务动作，不允许因坐标相同而丢失。

Node 表示物理地点。当前节点融合半径为 0.3 km；该半径只用于地点聚合，不能复用到到站判断。

Leg 表示两个执行节点之间的路线。首段可以从 CURRENT_POSITION 开始。已经规划且包含完整坐标的相同 segmentKey Leg 可复用，避免无意义重复请求。

到离站参数：

| 参数 | 默认值 |
|---|---:|
| trip-arrival-radius-km | 0.5 km |
| trip-departure-radius-km | 0.8 km |
| trip-minimum-dwell-ms | 60000 ms |

## 6. 排序算法

初始计划使用带约束的最近邻：

1. 从有效车辆位置开始；缺少车辆位置时使用已访问点/待执行点生成稳定回退锚点。
2. 当前合法集合只包含 PICKUP，以及已经完成 PICKUP 的订单对应 DELIVERY。
3. 选择直线距离最近的合法 Stop。
4. 距离相同时 DELIVERY 优先于 PICKUP。
5. 如果旧 currentLeg 强制指向同址 PICKUP，而该地点存在合法 DELIVERY，则拒绝旧强制目标。
6. 更新当前位置，继续到全部合法 Stop 排完。

动态重排只修改未完成后缀。候选顺序生成后调用真实道路规划做风险校验；供应商失败、道路距离异常或收益过小都保留稳定旧计划。

风险阈值：

- 冷却：120 秒。
- 最小收益：0.5 km。
- 最小收益比例：2%。
- 最大道路/直线比例：2.5。
- 最大额外道路距离：50 km。

## 7. 路线规划与缓存

RoutePlanningService 的键包含起点、终点和有序途经点，坐标精度参与键生成。缓存仅在 JVM 内存中，默认 TTL 24 小时，不是磁盘路线缓存。

供应商顺序：

1. 百度。
2. 高德。
3. 两者都失败：返回 unavailable，调用方不得把缺失段当成成功路线。

途经点处理：

- 丢弃非法中国境内坐标。
- 连续重复坐标去重。
- 最多 10 个途经点。
- 供应商返回后把精确业务锚点按前向最近位置重新插入。
- 渲染路线可以简化到 240 点，但匹配、偏航和进度必须使用 matchingCoordinates。

## 8. 持久化和事件语义

### 8.1 目录

~~~text
runtime-data/
  vehicle-order-chain/
    records/YYYY-MM-DD.json
    vehicles/*.json
    trips/{plate}.json
  vehicle-position-history/*.json
~~~

Trip 每次 reconcile、状态推进、插单或重排后写入临时文件，再原子替换正式文件。

### 8.2 状态事件

- 在途-2：轨迹推断车辆已离开装货点。
- 在途-1：正式系统确认在途。
- 已完成-2：满足目的地停留/离站证据后的推断完成。
- 已完成-1：正式系统确认完成。

后到的正式事件追加到审计链，不覆盖已有推断事件。业务读取时正式确认优先，但两条证据都可追溯。

## 9. RenderRouteDTO 契约

复合路线 meta 必须包含：

- tripId、visualKey、currentLegId、planVersion。
- targetStopId、targetOrderInstanceId、targetAction。
- tripStatusText、tripPhase、tripDecision、positionQuality。
- pendingOrderCount、onboardOrderCount、completedOrderCount。
- tripStops：每个 Stop 包含 stopId、orderInstanceId、action、全局 sequence、locationName、coordinates、visitState、currentTarget、markerColor。

sequence 必须是 Trip 全局序号 1..N，不能按 PICKUP/DELIVERY 分别编号。

## 10. 启动恢复

VehicleOrderChainStore 启动时恢复最近日库；VehicleTripRuntimeService 在首次看到某车辆时读取 trips/{plate}.json，并用最新订单链重新 reconcile。

注意：RM2 发布快照和路线供应商缓存是内存态。如果进程重启时外部订单源不可用，Trip 文件仍存在，但 groups 接口可能暂时为空。运维恢复方法：

1. 检查今天和昨天的 records 文件。
2. 检查外部订单源连接。
3. 必要时将 records 文件中 orders.*.record 作为数组 POST 到 /api/road/town/provinces/raw。
4. 获取新的 snapshotVersion，再查询 groups 和 group routes。
5. 不要手工删除 trips 文件来“修路线”；先核对 plannedStopIds、currentLegId 和位置历史。

## 11. 诊断接口

| 接口 | 用途 |
|---|---|
| GET /api/public/vehicle-order-chain/trips | 查看当前 Trip、集合、拓扑和计划 |
| GET /api/public/vehicle-order-chain/transit-metrics | 查看推断/确认状态统计 |
| GET /api/road/groups/structure?scope=rm2 | 获取当前 RM2 版本和链结构 |
| GET /api/road/groups?scope=rm2&snapshotVersion=... | 获取稳定分组 |
| GET /api/road/groups/{groupId}/routes?scope=rm2&snapshotVersion=... | 核对路线、tripStops 和位置 |
| POST /api/road/town/provinces/raw | 用原始订单数组重新走正式处理管线 |

除 public 接口外，REST 请求需要 Authorization: Bearer 会话密钥。会话由 POST /api/auth/session 签发。

## 12. 常见故障

### 展示分组不变量

- `businessLineId` 是不可拆原子单元：同订单、同业务线路的多辆车必须位于同一展示组。
- 不同业务订单若起终点同向或反向均在 0.75km 内，则在地图上会完全重合，必须拆到不同展示组。
- 其余 15km 内的相近业务节点使用软约束尽量分散；容量与原子性优先于软约束。

### 首轮只有起点到最终终点

检查传给 RoutePlanningService 的 waypointCount。复合 Trip 必须在第一次规划前完成 topology.build，不能等第二次刷新才补 stop。

### 起点到车辆位置出现直线

检查首个 Leg 是否从 CURRENT_POSITION 到 targetStopId 调用了道路规划；供应商失败时必须输出明确失败原因，不能无提示降级成业务路线。

### 面板四节点顺序和路线不一致

检查 CompositeStopView.sequence 是否为全局序号，以及前端是否只按 sequence 排序。

### 同址节点出现两个钉或回环

业务层保留两个 Stop；规划请求去除连续重复坐标；前端地图按物理坐标合并钉，三处职责不可混用。

### 车辆已到下一节点但状态滞后

检查位置历史是否持续写入、样本时间戳是否单调、是否达到两样本与 60 秒停留条件，以及旧 currentLegId 是否仍存在于新拓扑。

### 路线突然缩短或缺段

检查 snapshotVersion、planVersion、routeSignature、provider 和 waypoints。缓存未命中不应直接删除某段。

## 13. 测试

~~~powershell
mvn test
~~~

核心覆盖：

- VehicleTripTopologyServiceTest：先取后送、同址先卸后装、节点融合。
- VehicleTripRuntimeServiceTest：状态单调、动态插单/重排、全局 sequence、重启恢复。
- VehicleOrderChainStoreTest：去重、完成链清理、正式/推断事件并存。
- VehicleOrderEligibilityServiceTest：真实/模拟轨迹推进。
- TownRoadRenderServicePipelineCutTest：复合 Trip 接入正式 RM2 管线。
- RoutePlanningServiceTest：多途经点、精确锚点与路线简化。
