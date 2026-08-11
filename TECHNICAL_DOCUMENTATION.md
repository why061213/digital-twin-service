# 聚申数字孪生后端技术文档

> 更新日期：2026-08-11
>
> 适用分支：`main`

## 1. 模块结构

```text
com.jushen.digitaltwin/
  bootstrap/       Dashboard 验证、会话和启动状态
  baidu/           百度/高德路线规划统一入口
  externalorder/   外部订单同步与查询
  grouping/        RM1 路线分组策略
  routeanalysis/   共享路段和偏航分析
  service/         路线推送、位置缓存、RM2 查询、仓储
  townroad/        正式订单管线、坐标库、Trip 和运行存储
  web/             REST Controller 与异常处理
  websocket/       握手、会话和广播
```

Controller 只做参数转换和响应；业务规则放 service/townroad；领域纯算法尽量无 Spring 依赖并由单元测试覆盖。

## 2. 启动与调度

`DigitalTwinBackendApplication` 启用 Spring Scheduling。启动时配置按以下顺序导入：

1. `simulation.yml`
2. `temp.yml`
3. `warehouse.yml`
4. `camera.yml`
5. 可选 `application-private.yml`

主要后台任务：

- 外部订单首次同步及固定间隔更新。
- 车辆位置批量刷新。
- 仓储和 KPI 推送。
- 运行缓存保存、清理和恢复。

调度任务必须避免重入，外部调用必须有连接、请求超时和限速。

## 3. 正式订单与 RM2 管线

主入口是 `TownRoadRenderService`。处理阶段：

```text
外部订单
 -> 字段归一化与入口去重
 -> VehicleOrderChain 日库差分
 -> 地址/坐标解析
 -> 车辆位置预热
 -> 资格判定
 -> Trip 拓扑与运行状态
 -> 路线规划/动态修正
 -> 稳定分组与快照指纹
 -> REST 快照 + WebSocket 变更通知
```

### VehicleOrderChainStore

按日期保存原始差分、车辆索引和 Trip。启动时只加载配置范围内的今昨记录。写入要求：

- 同一订单重复拉取为 `unchanged`，不重复触发下游。
- 字段变化为 `updated`，保留旧记录用于审计。
- 完成订单仍可参与历史和 KPI，但不重新进入活动路线。
- 运行目录只由服务管理，测试使用临时目录。

### 资格判定

`VehicleOrderEligibilityService` 结合订单状态、真实车辆、位置和前序订单决定是否进入地图。不能仅凭单个状态字符串判断“运输中”。装卸状态需要结合距离、轨迹分位和上一单上下文。

### Trip 模型

关键身份：

| 字段 | 含义 |
|---|---|
| `orderInstanceId` | 一次订单实例 |
| `businessLineId` | 业务线路原子单元，可含多车 |
| `tripId` | 一辆车的复合行程 |
| `lineId` | 对前端暴露的路线实例 |

`VehicleTripTopologyService` 维护装货/卸货节点，`VehicleTripRuntimeService` 维护到站、驻留、离站和里程碑。到站半径、离站半径和驻留时间分别配置，避免 GPS 抖动。

### 动态重规划

重规划只能替换尚未完成的路线段，必须保留：

- 已走完的路线基线。
- 所有未完成业务节点。
- 同址但不同业务动作的 Stop 信息。
- 原有订单和 Trip 身份。

固定车辆案例已固化为 `YueE55170SnapshotReplayTest`，不再维护案例 Markdown。

## 4. RM1 与 grouping

`grouping/` 使用策略模式。核心输入是 `RouteInfo` 及其 order/path/province 扩展，输出 `RouteGroupingResult` 和 `GroupSummary`。

常用策略：

- sequential：顺序分组。
- by-order：按订单。
- by-path / by-route：按路线关系。
- province-path：按省域和方向路径。
- business-priority：业务优先组合策略。

分组字段职责必须明确：订单键、路线键、业务线路键和车辆键不能互相替代。新策略先增加纯单元测试，再接入 Controller 查询参数。

## 5. 路线与坐标

### 坐标库

`LocalCoordDb` 读取 `src/main/resources/coord-db/`。路径按省、市、区县组织，JSON 必须保持 UTF-8 和合法格式。缺失坐标通过 `AmapGeocodeClient` 补全，真实 Key/Secret 只放私有配置。

### 路线规划

`RoutePlanningService` 是统一入口：

1. 命中本地/内存缓存则直接返回。
2. 调用百度规划。
3. 百度失败时调用高德回退。
4. 两者不可用时返回受约束的本地几何兜底。

所有供应商调用共用全局最小间隔并设置超时。路线缓存键必须包含起终点和影响规划结果的选项。

### 边界修复与分析

`ChinaBoundaryConstraint` 防止兜底路线穿越明显非陆地区域。`RouteAnalysisService` 使用米制重采样分析共享路段和偏航，不依赖前端渲染采样数。

## 6. 车辆位置

`VehiclePositionCacheService` 是位置读取边界，`RoutePushService` 编排主动刷新、模拟和推送。

规则：

- 批量接口优先，避免逐车穿透供应商。
- 样本携带服务器时间和供应商时间，旧样本不得覆盖新样本。
- 超过可信速度的跳点丢弃或降级。
- 死推仅用于短时平滑，不写回为真实供应商位置。
- RM1/RM2 通过 `scope` 隔离订阅和广播。

位置接口：

- `GET /api/road/routes/{lineId}/position`
- `POST /api/road/vehicles/positions/query`
- `POST /api/road/routes/query-position`

## 7. REST API

### 鉴权与基础

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/bootstrap/status` | 启动和验证状态 |
| POST | `/api/auth/session` | 签发会话 |
| GET | `/api/auth/session` | 校验会话 |
| POST | `/api/auth/session/refresh` | 刷新会话 |

### 路线

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/road/dispatch` | 模拟派发 |
| POST | `/api/road/dispatch/bulk` | 模拟批量派发 |
| GET | `/api/road/groups` | 分组列表 |
| GET | `/api/road/groups/structure` | RM2 结构 |
| GET | `/api/road/groups/{groupId}/routes` | 组路线 |
| GET | `/api/road/rm2/groups` | RM2 组别名 |
| GET | `/api/road/rm2/groups/structure` | RM2 结构别名 |
| GET | `/api/road/rm2/groups/{groupId}/routes` | RM2 路线别名 |

查询 RM2 时携带 `snapshotVersion`，版本变化应重新拉取结构，避免跨快照混用组 ID。

### TownRoad 和外部订单

| 前缀 | 主要用途 |
|---|---|
| `/api/road/town` | 省域订单、同 OD、方向路径和最新快照 |
| `/api/road/external-orders` | 主动同步、订单查询、分组和沿途插单 |
| `/api/public/vehicle-order-chain` | Trip 与运输指标公开诊断 |
| `/api/dashboard/daily-kpis` | 每日 KPI |

### 仓储

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/warehouse/snapshot` | 当前快照 |
| POST | `/api/warehouse/snapshot/push` | 生成并广播 |
| GET | `/api/warehouse/focus/{cityName}` | 城市面板 |
| POST | `/api/warehouse/focus/{cityName}/push` | 模拟推送 |
| POST | `/api/warehouse/focus/{cityName}/panels` | 外部 JSON 面板 |
| POST | `/api/warehouse/focus/{cityName}/upload` | CSV 上传 |

ChinaMap 维护接口分三组：

- 城市：`GET /china-map/cities/sync`、`POST /china-map/cities`
- 图表结构：`GET /china-map/charts/sync`、`POST /china-map/charts`
- 图表数据：`GET /china-map/data/sync`、`POST /china-map/data`

完整路径均以 `/api/warehouse` 开头。GET `/sync` 从 `warehouse.yml` 配置的外部 URL 拉取；POST 直接应用请求体。批量修改不是数据库事务，调用方应在失败后回读并按幂等键重试。

## 8. WebSocket

端点：`/ws/realtime`。

`TokenHandshakeInterceptor` 校验会话，`RealtimeWebSocketHandler` 管理连接、scope 订阅、心跳和广播。

服务端消息：

- `route_snapshot_changed`
- `road_path`
- `truck_position`
- `vehicle_positions`
- `city_raise` / `city_fall`
- `warehouse_update`
- `warehouse_focus`
- `camera_control`
- `daily_kpis`
- `pong`

客户端消息：`ping`、`vehicle_position_subscription`。广播方法不得在业务线程中因单个慢连接阻塞全部会话。

## 9. 鉴权

`DashboardAccessService` 通过回环信任、局域网 MAC 白名单或设备令牌批准客户端。`DashboardAccessTokenService` 签发有 TTL、刷新阈值和宽限期的内存会话。

REST 读取：

- `Authorization: Bearer <access-token>`
- `X-Dashboard-Access-Key: <access-token>`

固定 WebSocket 口令仅为关闭默认的兼容模式。生产环境不应启用公开固定口令。

## 10. 仓储配置

`warehouse.yml` 定义：

- 仓库城市和标签。
- 聚焦面板数量、布局、主题和字号。
- 每个面板的 `id`、`chart-type`、高度、列和 `required-columns`。
- cities/charts/data 三个外部同步 URL。

外部 JSON 和 CSV 必须先按 required columns 校验，再转换为统一 `warehouse_focus` 消息。面板顺序使用 position，重复位置或未知城市应拒绝。

## 11. 配置参考

主要前缀：

| 前缀 | 作用 |
|---|---|
| `dashboard.access` | 白名单、设备令牌和会话 |
| `dashboard.websocket` | WebSocket 和外部订单同步 |
| `dashboard.route` | 分组、位置刷新、外部位置和模拟速度 |
| `dashboard.coord-db` | 高德地理编码 |
| `dashboard.route-plan` | 百度/高德规划、缓存、限速和超时 |
| `dashboard.route-analysis` | 共享路段与偏航阈值 |
| `dashboard.daily-statistics` | KPI 缓存 |

配置类使用 Spring `@ConfigurationProperties` 或 `@Value` 绑定。新增字段必须给出安全默认值、更新公开 YAML 和私有模板说明，并增加绑定或行为测试。

## 12. 测试与排障

命令：

```powershell
mvn test
mvn -DskipTests package
```

重点测试族：

- `VehicleOrderChainStoreTest`
- `VehicleOrderEligibilityServiceTest`
- `VehicleTripRuntimeServiceTest`
- `VehicleTripTopologyServiceTest`
- `TownRoadRenderService*Test`
- `RoutePlanningServiceTest`
- `Rm2StableGroupsTest`
- `DashboardAccess*Test`
- `WarehousePushServiceManagementTest`

排障顺序：

1. 健康检查和启动日志。
2. 鉴权会话及请求状态码。
3. 外部订单同步数量和差分结果。
4. `/api/public/vehicle-order-chain/trips` 的 Trip/Stop 状态。
5. 坐标解析和路线供应商回退日志。
6. RM2 snapshotVersion 与组数量。
7. WebSocket 订阅 scope 和前端网络面板。

不要用删除 `runtime-data` 作为第一排障手段。需要清理时先停止服务、备份目录并记录原因。

## 13. 维护规则

- 不再新建阶段文档、专项接口文档或单车辆案例文档。
- 使用方式写 README；开发设计和协议只更新本文件。
- 单一案例转为 fixture 和自动化测试。
- Controller 注解、配置字段或 WebSocket type 变化时更新接口章节。
- 任何真实凭据泄露后必须立即吊销，删除当前文件不足以解决历史泄露。

## 14. 分层、对象边界与调用路径

新增代码前先确定所属层：

| 层 | 可以做 | 不应做 |
|---|---|---|
| Controller | 参数绑定、基础格式校验、调用 service、选择状态码 | 分组、路线规划、Trip 推进、外部 API 拼装 |
| Service | 用例编排、事务/锁边界、缓存、广播 | 依赖前端组件语义 |
| Domain/算法 | 资格、拓扑、投影、分组、偏航等纯计算 | 读取 Spring 环境或直接发 WebSocket |
| Client/Provider | 外部请求、鉴权、超时、响应适配 | 决定业务展示资格 |
| Store | 保存、恢复、差分、原子写入 | 产生界面 DTO |
| Assembler/Query | 将稳定领域快照组装为外部 DTO | 修改领域运行状态 |

典型 REST 调用：

```text
RoadController
  -> RoutePushService / Rm2GroupQueryService
  -> stable in-memory snapshot or cache
  -> Map/DTO JSON
```

典型 RM2 更新：

```text
scheduled/manual sync
  -> TownRoadExternalOrderClient.fetchOrders
  -> TownRoadRenderService.fetchProcessAndBroadcast
  -> VehicleOrderChainStore diff/recover
  -> TownRoadMiddleLayer normalize/resolve/plan
  -> eligibility + topology + runtime
  -> stable groups + snapshotVersion
  -> RoutePushService dispatch/cache
  -> RealtimeWebSocketHandler.broadcast(route_snapshot_changed)
```

`TownRoadRenderService` 是编排入口，不应继续塞入可独立测试的算法。新的判断规则优先建立小型 domain service，并由编排层注入调用。

## 15. RM2 正式订单实现细节

### 15.1 数据进入与差分

`TownRoadExternalOrderClient` 只负责外部协议；字段别名、空值和接口分页在 client/adapter 边界消化。进入 `VehicleOrderChainStore` 后使用稳定的订单实例、车辆和 Trip 身份。日库差分用于识别新增、仍存在、已消失或状态变化的记录，不能只用当天列表覆盖运行态，否则会丢失驻留、已完成节点和路线版本。

持久化恢复时必须满足：

- JSON 无法解析时记录具体文件和原因，不以空库静默覆盖坏文件。
- 写文件使用临时文件加原子替换，避免进程中断留下半个 JSON。
- 恢复后重新校验订单、车辆、Trip 的引用关系。
- 日期切换保留业务要求的今昨范围，清理前先与活动 Trip 解耦。

### 15.2 坐标解析

`TownRoadCoordinateResolver` 当前顺序：

1. 使用订单已有合法坐标。
2. 查询 `LocalCoordDb`。
3. 调用高德地理编码，成功后写回本地坐标库。
4. 旧的区县兜底当前已禁用。
5. 仍无坐标的订单标记为不可渲染，不生成伪造位置。

地址规范化须保留原始地址用于诊断。直辖市存在省/市层级特殊处理，新增行政区规则要覆盖普通省、市辖区和直辖市测试。经纬度内部统一 `[lng, lat]`；调用供应商前再按其参数顺序转换。

### 15.3 资格判定与 Trip

`VehicleOrderEligibilityService` 决定订单是否进入展示快照。新增资格条件时必须返回可诊断 reason code，并在测试中覆盖允许、拒绝、边界时间和缺字段。不要在 assembler 中通过“字段不全就不输出”偷偷增加第二套资格规则。

`VehicleTripTopologyService` 生成装货和卸货 Stop；`VehicleTripRuntimeService` 维护当前目标、到站、驻留、离站和已访问状态。动态重规划时：

- 保留已访问节点和完成里程基线。
- 保留所有未完成订单对应的 Stop。
- 当前车辆位置可作为新规划起点，但不能替换业务 Stop。
- `planVersion/currentLegId/targetStopId` 同步更新。
- 到站半径、离站半径和最短驻留时间分开配置，形成迟滞区。

位置历史刷新回调会推进 Trip milestone；装货车辆离站回调会触发正式订单重新处理。这两个回调在 `TownRoadRenderService` 构造阶段绑定到 `RoutePushService`，修改事件时要防止同步递归和重复刷新。

### 15.4 稳定分组和快照

RM2 默认每组最多三个业务订单，常量 `RM2_GROUP_SIZE = 3`。同一订单多车辆只占一个业务名额，并保持为原子单元。分组和结构的输出必须来自同一稳定快照：

- `snapshotVersion` 在结构、组列表和组详情中一致。
- `groupId` 在同一业务集合未变化时保持稳定。
- `vehicleLineIdsByOrderLineId` 明确业务线路到车辆路线映射。
- `renderProvinceKeys` 只包含合法六位行政区键。
- 版本不一致时组详情返回 `mismatch:true` 和空路线，由前端重拉，不混合两个版本。

快照指纹只有在可观察内容变化时更新。定时任务重复计算出相同结果不得持续广播 `route_snapshot_changed`。

## 16. 路线规划、回退与缓存

统一入口为 `RoutePlanningService.plan(origin, destination, waypoints)`：

```text
校验开关和坐标
 -> 规范化途经点
 -> 以起点/终点/途经点生成 cache key
 -> 命中新鲜缓存则返回
 -> 百度规划
 -> 失败时高德规划
 -> 补回精确起终点和每个途经点
 -> 成功结果入缓存
```

配置：

| 键 | 默认值 | 作用 |
|---|---:|---|
| `dashboard.route-plan.enabled` | `true` | 总开关 |
| `dashboard.route-plan.cache-ttl-ms` | `86400000` | 成功路线缓存时间 |
| `dashboard.route-plan.min-request-interval-ms` | `400` | 供应商请求最小间隔 |

只缓存成功结果，失败保留供应商前缀原因。途经点参与 cache key；基线路线和带途经点路线不得互相命中。供应商返回路径可能做渲染简化，但用于匹配/偏航的完整路径和所有业务途经点不可丢失。

新增路线供应商时不要在调用方写第三个 fallback 分支。定义统一结果（成功、坐标、距离、时长、错误），在 `RoutePlanningService` 集中决定顺序、限速、缓存和日志，并补“前序失败后回退”“途经点保留”“全失败”测试。

## 17. RM1 分组扩展

所有 RM1 策略实现 `AdvancedGroupingStrategy`：

```java
@Component
public final class ExampleGroupingStrategy implements AdvancedGroupingStrategy {
    @Override public String name() { return "example"; }
    @Override public String description() { return "..."; }

    @Override
    public List<GroupSummary> group(
            List<? extends RouteInfo> routes,
            GroupingContext context
    ) {
        // 返回顺序稳定、ID 稳定的组
    }
}
```

`RouteGroupingRegistry` 在 Spring 启动时自动收集 Bean，无需手改注册表。无策略名或未知策略回退到 `business-priority`，若不存在则 `sequential`，再否则取首个 Bean。`RoutePushService` 仍有 `dashboard.route.default-group-strategy` 配置，实际使用时通过 `resolveGroupStrategy` 和注册表解析；新增名称必须在 API、配置和前端策略类型中完全一致。

策略实现要求：

- 不修改传入路线对象。
- 相同输入和 context 产生相同组顺序和 `groupId`。
- 每个活动路线只出现于符合业务规则的组中。
- `GroupSummary.routes` 与摘要数量一致，供组详情反查。
- 使用 `context.getGroupSize()`，不另写魔法数字。
- 缺少可选的订单/省域/路径能力时明确降级，不强制转换错误接口。

新增策略至少测试空列表、少于一组、刚好一组、跨组边界、输入乱序、重复业务键和缺少扩展字段。再通过 `/api/road/groups?scope=rm1&strategy=example` 与组详情使用同一参数回读。

## 18. 车辆位置实现细节

位置供应商的原始数据先转为 `PositionSnapshot` 并写入 `VehiclePositionCacheService`。批量刷新优先，禁止 REST 按每辆车同步穿透供应商。`getCachedOrSimulatedPosition` 输出的关键字段：

```text
type, lineId, scope, groupId, snapshotVersion,
position, velocity, speedKmh, progress, status,
source, stale, fetchedAt, vehicleId, plate,
speedQuality, sequence
```

还会附加供应商详情、模拟方向、路线纠偏和 Trip runtime 元数据。缓存未命中时只有已知活动路线才产生模拟位置；路线不存在返回 `status=finished`。

批量查询：

```http
POST /api/road/vehicles/positions/query
Content-Type: application/json

{"lineIds":["line-a","line-b","line-a"]}
```

服务端去重并返回：

```json
{
  "serverTime": "2026-08-11T00:00:00Z",
  "snapshotVersion": "...",
  "cacheAgeMs": 0,
  "positions": [],
  "missingLineIds": [],
  "staleLineIds": []
}
```

新增供应商字段时先放入 `PositionSnapshot` 或显式 provider-details，再决定是否暴露。时间戳、坐标系、速度单位、方向角定义必须写清；不得把供应商空值自动变成 0 造成车辆跳到原点。

## 19. REST 精确契约

### 19.1 RoadController

| 方法 | 路径 | 参数/请求体 | 返回重点 |
|---|---|---|---|
| POST | `/api/road/dispatch` | 无 | 随机模拟路线结果 |
| POST | `/api/road/dispatch/bulk` | query `vehicleCount`，默认 24 | 批量派发结果 |
| GET | `/api/road/groups` | `strategy?`、`scope=rm1`、`snapshotVersion?` | scope、策略/版本、groups |
| GET | `/api/road/groups/structure` | `scope=rm1` | RM2 节点结构；RM1 返回空结构 |
| GET | `/api/road/groups/{groupId}/routes` | `strategy?`、`scope=rm1`、`snapshotVersion?` | 组路线快照 |
| GET | `/api/road/routes/{lineId}/position` | path `lineId` | 单路线当前位置 |
| POST | `/api/road/vehicles/positions/query` | `{lineIds:string[]}` | 批量缓存/模拟位置 |
| GET | `/api/road/path` | `fromLng/fromLat/toLng/toLat/points=20` | 模拟直线路径 |
| POST | `/api/road/path` | `{points:[[lng,lat],...],totalPoints?:200}` | 多航点模拟路径 |
| POST | `/api/road/routes/query-position` | `plate`、`carId`、`query` 三选一 | 手查结果并广播临时位置 |

多航点至少两个坐标。手查成功广播的 `lineId` 为 `manual-{vehicleKey}`，仅用于临时展示，不进入正式分组。

### 19.2 RM2 查询一致性

推荐始终使用通用 `/api/road/groups...` 加 `scope=rm2`；`/api/road/rm2/...` 是别名。读取流程：

1. 请求 structure，保存 `snapshotVersion`。
2. 携带版本请求 groups。
3. 携带相同版本请求每个 group routes。
4. 任一响应 `mismatch:true`，回到步骤 1。

不要在 Controller 层临时补字段使三个接口结构漂移；统一修改 `Rm2GroupQueryService`/响应 assembler 和对应测试。

### 19.3 鉴权响应

`POST /api/auth/session` 验证设备/IP/令牌后返回：

```json
{
  "accessToken": "<runtime-token>",
  "tokenType": "Bearer",
  "issuedAt": "...",
  "refreshAfter": "...",
  "expiresAt": "...",
  "verificationMethod": "...",
  "deviceIdentity": "..."
}
```

无权签发为 `403 device_not_authorized`；刷新失效为 `401 access_key_expired`；校验失效为 `401 access_key_invalid`。真实响应 token 只能用于本次运行，不写入文档、测试 fixture 或日志。

## 20. 仓储增删改查手册

仓储维护当前作用于进程内的 `warehouseConfigs`、`centerCharts`、`sidePanels` 和 `cityNameById`。重启后以 `warehouse.yml` 与数据源重新初始化；若要求永久 CRUD，需要另接数据库/配置中心，不能误以为当前接口已落盘。

### 20.1 城市新增、覆盖和删除

请求 DTO：

```json
{
  "operation": "ADD",
  "cities": [
    {"cityId":"310000","cityName":"上海市","warehouseName":"上海中心仓"}
  ]
}
```

字段也兼容接口定义中的中文别名。动作允许：`ADD/添加`、`DELETE/REMOVE/删减/删除`、`REPLACE/OVERWRITE/覆盖`。

- `ADD`：按 `cityName` upsert，先移除同名配置再添加；`warehouseName` 必填。
- `DELETE`：按 `cityName` 删除城市、中心图表、侧面板和 ID 映射，并广播 `warehouse_update action=fall`；不要求 warehouseName。
- `REPLACE`：先清空城市和 ID 映射，再添加请求列表；不再存在的旧城市广播 fall。图表缓存只有在逐城市 DELETE 时清理，修改该语义需补回归测试。

响应统一含 `resource`、`operation`、`applied`、`data`。城市写入会广播 `warehouse_update` 快照。

### 20.2 中心图表新增、覆盖和删除

请求体是数组，每个城市一项：

```json
[
  {
    "cityName": "上海市",
    "operation": "ADD",
    "charts": [
      {
        "position": 1,
        "chartType": "bar",
        "chartData": {"title":"库存","xAxis":["A"],"series":[10]}
      }
    ]
  }
]
```

城市必须已存在。中心位置限定 1–8；非删除时 `chartType` 和 `chartData` 必填。位置省略时从 1 开始寻找空槽。相同槽位 upsert；`REPLACE` 先清空该城市所有中心图表；`DELETE` 只需 position。服务会补 `position`、`chartType`，并为缺失的 `id/title` 生成默认值。每个城市处理后广播 `warehouse_chart_update`。

### 20.3 调整图表数据

```json
[
  {
    "sidePanel": false,
    "cityName": "上海市",
    "position": 1,
    "chartData": {"series":[12],"updatedAt":"..."}
  }
]
```

`sidePanel` 必填；中心图表位置 1–8，侧面板位置 1–4。城市可用 `cityName`，或使用之前登记且可解析的 `cityId`。调整采用浅合并：现有 Map 与 `chartData` 合并，嵌套对象不会递归合并。中心图表广播 `warehouse_chart_update`，侧面板广播完整 `warehouse_focus`。

### 20.4 聚焦面板和 CSV

`POST /api/warehouse/focus/{cityName}/panels` 接收面板数组，先按 `warehouse.yml` 的结构、列和 requiredColumns 校验，再广播统一 `warehouse_focus`。CSV 上传使用 multipart，至少包含 `panelId` 和 `file`；表头不满足 requiredColumns 时拒绝，不应部分写入。

仓储功能变更最低回归是 `WarehousePushServiceManagementTest`：覆盖 ADD、DELETE、REPLACE、槽位边界、未知城市、浅合并和广播内容。

## 21. 新增接口、字段和消息的标准做法

### 21.1 新增查询接口

1. 定义不可变请求/响应 DTO；外部响应不要直接暴露持久化实体。
2. Controller 只做绑定并委托 Service。
3. Service 从同一稳定快照计算响应，避免列表和详情各自重新同步外部数据。
4. 在鉴权配置中确认是公开还是受保护；默认受保护。
5. 写 `MockMvc`/Controller 测试和 Service 边界测试。
6. 与前端 service 共同确定空数组、缺字段和版本冲突语义。

### 21.2 增加响应字段

从领域事实产生处开始改，再修改 assembler。对位置或路线响应要检查 REST 和 WebSocket 是否共享组装函数；若不是，分别补齐并加一致性断言。可空字段应省略还是返回 null 必须固定，数值写明单位，坐标写明 GCJ02/WGS84。

### 21.3 新增 WebSocket type

消息至少包含 `type`；高频或可乱序消息增加 `sequence`/时间戳，快照消息增加 `snapshotVersion`，视图相关消息增加 `scope`。统一经 `RealtimeWebSocketHandler.broadcast`，不要从 Controller 直接遍历 session。广播失败不能中断业务事务；但应记录可定位的消息类型和连接信息，禁止记录 token。

### 21.4 删除契约

先发布能忽略旧字段/旧消息的前端，再移除后端。URL 删除前全仓搜索 Controller 注解、前端 services、代理配置、测试和文档。配置键删除时同步删除 `@Value`、YAML 默认值、私有模板和部署环境说明。

## 22. 错误处理、并发和缓存

- 参数错误统一交给全局异常处理器转换为 4xx，不把 Java 堆栈返回前端。
- 外部供应商错误保留 provider、HTTP 状态、业务错误码和耗时；账号、签名、完整 URL 查询串必须脱敏。
- 定时同步、手动 sync 和位置回调可能并发进入处理管线，使用已有锁/单线程编排，避免同时替换快照。
- 对外返回集合使用副本或不可变结构，不能让 Controller 调用者修改缓存。
- cache key 必须包含所有影响结果的输入；配置或算法版本会改变结果时考虑加入版本或主动失效。
- 广播发生在新快照完整建立之后，不能先通知再逐项填充。
- `CopyOnWriteArrayList` 适合读多写少的仓库配置；若维护频率显著提高，应重新评估，不盲目扩用。

## 23. 测试选择与精准回归

| 修改范围 | 首选测试 |
|---|---|
| RM1 分组 | 策略单测、`RoutePushServiceFormalOrderTest` |
| RM2 分组/版本 | `Rm2StableGroupsTest`、`Rm2RouteResponseAssemblerTest` |
| Trip 拓扑/运行态 | `VehicleTripTopologyServiceTest`、`VehicleTripRuntimeServiceTest` |
| 订单恢复/差分 | `VehicleOrderChainStoreTest`、快照回放测试 |
| 坐标/路线回退 | `TownRoadMiddleLayer*Test`、`RoutePlanningServiceTest` |
| 位置/纠偏 | `PositionSnapshotTest`、RouteProgress/Correction/Deviation 测试 |
| 仓储维护 | `WarehousePushServiceManagementTest` |
| 鉴权 | `DashboardAccess*Test`、Controller 测试 |
| WebSocket | 握手鉴权、消息组装和 scope 测试 |

完整验证：

```powershell
mvn test
mvn -DskipTests package
```

接口人工验收要覆盖有效请求、空数据、非法参数、未知 ID、鉴权缺失、快照版本冲突和外部供应商失败。任何正式订单案例应固化为匿名 fixture 和自动测试，不再新建案例 Markdown。

## 24. 上线前后端交接清单

1. 列出本次增加、修改、废弃的 REST 路径、参数、字段和 WebSocket type。
2. 给出每个新接口的可运行请求样例，但令牌、账号、私有 URL 使用占位符。
3. 标记数据事实来源、缓存时间、落盘目录、重启后是否保留。
4. 标记坐标系、时间格式、时区、速度/距离单位和枚举全集。
5. 说明空数据、外部失败、版本冲突和重复请求的行为。
6. 提供后端测试类、前端适配测试和一次真实联调结果。
7. 检查 `application-private.yml`、运行缓存、日志、测试抓包未进入 Git。
8. 部署后验证 health、session、三类快照、WebSocket 握手、RM1/RM2 位置更新和仓储回读。
