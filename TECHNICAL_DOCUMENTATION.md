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
