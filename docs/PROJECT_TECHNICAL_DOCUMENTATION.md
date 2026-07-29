# 聚神数字孪生项目技术文档

> **历史基线文档（2026-07-02）**：本文保留用于追溯早期设计，当前鉴权、RM2 原子快照、复合订单 Trip 和车辆轨迹逻辑请以仓库上级 TECHNICAL_DOCUMENTATION.md 与本目录 RM2_COMPOSITE_TRIP_TECHNICAL_GUIDE.md 为准。

> 更新时间：2026-07-02  
> 后端目录：`E:\wendang\jushen-digital-twin`  
> 前端目录：`E:\jushen\data-showing-web\jishen-digital-twin`

## 1. 项目概述

本项目是一个物流/仓储数字孪生大屏，前端以 React + Three.js + ECharts 构建三维可视化界面，后端以 Spring Boot 提供 REST API、WebSocket 主动推送、模拟数据生成、仓库聚焦面板数据、道路车辆调度与位置模拟能力。

当前核心页面是 `DashboardPage`，主要包含三个视图：

| 视图 | 前端模块 | 当前职责 |
| --- | --- | --- |
| 仓库视图 | `Warehouse3D` | 预留视图，目前内容较少 |
| 数字孪生地图 | `ChinaMap3D` | 中国三维地图、仓库城市升起、城市标签、聚焦巡游、仓库数据面板 |
| 道路级地图 | `RoadMap3D` | 道路路线、车辆点位、路线进度、运输 group 轮播、车辆位置预测与校准 |

系统整体通信方式：

- 初始化和主动操作使用 REST API。
- 实时数据使用 WebSocket `/ws/realtime?token=...` 主动推送。
- 前端不轮询大屏数据，只对道路车辆位置做可控间隔的位置确认。

## 2. 技术栈

### 2.1 前端

- React 19
- TypeScript
- Vite
- Three.js
- `@react-three/fiber` / `@react-three/drei`
- ECharts
- D3 Geo
- Tailwind CSS

主要目录：

```text
src/pages/Dashboard/
  DashboardPage.tsx
  hooks/useDashboardRealtime.ts
  modules/ChinaMap3D/
  modules/RoadMap3D/
  modules/Warehouse3D.tsx
src/config/labelLayout.ts
```

### 2.2 后端

- Java 17+
- Spring Boot
- Spring Web
- Spring WebSocket
- Spring Scheduler
- Jackson

主要目录：

```text
src/main/java/com/jushen/digitaltwin/
  config/
  model/
  service/
  web/
  websocket/
src/main/resources/
  application.yml
  simulation.yml
  warehouse.yml
  camera.yml
```

## 3. 环境配置

### 3.1 前端 `.env`

文件：`E:\jushen\data-showing-web\jishen-digital-twin\.env`

| 配置项 | 说明 | 当前值 |
| --- | --- | --- |
| `VITE_API_BASE_URL` | 后端 REST API 根地址 | `http://localhost:8080/api` |
| `VITE_WS_URL` | WebSocket 根地址 | `ws://localhost:8080/ws` |
| `VITE_WS_TOKEN` | WebSocket 固定 token | `jushen-screen-token` |
| `VITE_TRUCK_SIMULATION_PROFILE` | 车辆模拟模式，`test` / `real` | `test` |
| `VITE_TRUCK_POSITION_QUERY_INTERVAL_TEST_MS` | 测试模式常规位置确认间隔 | `60000` |
| `VITE_TRUCK_POSITION_QUERY_INTERVAL_REAL_MS` | 真实模式常规位置确认间隔 | `1800000` |
| `VITE_TRUCK_SLOW_POSITION_QUERY_INTERVAL_TEST_MS` | 测试模式低速位置确认间隔 | `15000` |
| `VITE_TRUCK_SLOW_POSITION_QUERY_INTERVAL_REAL_MS` | 真实模式低速位置确认间隔 | `300000` |
| `VITE_TRUCK_LOW_SPEED_THRESHOLD_KMH` | 低速阈值，低于该值更频繁确认 | `50` |
| `VITE_TRUCK_POSITION_RENDER_TICK_MS` | 前端预测渲染 tick | `500` |
| `VITE_ROAD_GROUP_DISPLAY_MS` | 道路 group 单组展示时长 | `10000` |
| `VITE_MAP_VIEW_TRANSITION_MS` | 数字孪生/道路地图主题切换渐变时长 | `800` |
| `VITE_ROAD_GROUP_TRANSITION_MS` | 道路 group 内部切换渐变时长 | `420` |

### 3.2 后端 `application.yml`

文件：`src/main/resources/application.yml`

| 配置项 | 说明 |
| --- | --- |
| `server.port` | 后端端口，默认 `8080` |
| `dashboard.websocket.token` | WebSocket token |
| `dashboard.websocket.allowed-origin-patterns` | WebSocket 允许来源 |
| `dashboard.route.simulation-profile` | 车辆模拟速度 profile |
| `dashboard.route.external-position-url` | 外部车辆位置服务 URL，支持 `{lineId}` |
| `dashboard.route.passive-position-push-enabled` | 是否启用后端被动推送车辆位置 |
| `dashboard.route.group-size` | 每个道路地图 group 包含路线数，当前默认 5 |
| `dashboard.route.truck-position-push-rate-ms` | 被动推送周期 |
| `dashboard.route.low-speed-threshold-kmh` | 低速阈值 |
| `dashboard.route.test.*` | 测试模式车辆速度和请求间隔 |
| `dashboard.route.real.*` | 真实模式车辆速度和请求间隔 |

### 3.3 仓库面板配置 `warehouse.yml`

文件：`src/main/resources/warehouse.yml`

关键结构：

```yaml
warehouses:
  warehouses:
    - city: 佛山市
      label: 佛山一号仓

  focus-panels:
    count: 8
    style:
      width: 180
      max-height: 240
      padding: 10
      title-font-size: 12
      body-font-size: 10
      chart-text-font-size: 10
      placement: right
      theme: cyan-dark
    panels:
      - id: inventory-table
        title: 实时库存明细
        chart-type: table
        height: 118
        required-columns: [metric, value, unit]
```

说明：

- `count` 控制聚焦城市最多显示几个面板。
- `style` 控制面板外框默认样式。
- `panels` 定义面板结构，后端会按该结构校验上传数据。
- 前端内部图表已经支持自适应外框尺寸，饼图/环图/柱状图/折线图会根据实际卡片区域重算图表尺寸。

## 4. 后端模块说明

### 4.1 WebSocket

#### `WebSocketConfig`

注册 WebSocket 端点：

```text
/ws/realtime
```

#### `TokenHandshakeInterceptor`

职责：

- 从 query 中读取 `token`。
- 与 `dashboard.websocket.token` 比较。
- token 不匹配则拒绝连接。

连接格式：

```text
ws://localhost:8080/ws/realtime?token=jushen-screen-token
```

#### `RealtimeWebSocketHandler`

职责：

- 保存在线 session。
- `broadcast(Object message)` 向所有在线前端广播消息。
- 处理前端心跳：

前端发送：

```json
{ "type": "ping" }
```

后端返回：

```json
{
  "type": "pong",
  "serverTime": 1782990000000
}
```

### 4.2 仓库数据模块

#### `WarehouseController`

基础路径：

```text
/api/warehouse
```

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/snapshot` | 获取仓库快照，不广播 |
| `POST` | `/snapshot/push` | 获取并广播仓库快照 |
| `GET` | `/focus/{cityName}` | 获取某城市聚焦面板 |
| `POST` | `/focus/{cityName}/push` | 获取并广播某城市聚焦面板 |
| `POST` | `/focus/{cityName}/panels` | 外部程序推送面板数据 |
| `POST` | `/focus/{cityName}/upload?panelId=xxx` | 上传 CSV 表格生成面板 |

#### 仓库快照消息

REST 返回和 WebSocket 广播结构一致：

```json
{
  "type": "warehouse_update",
  "cityName": "佛山市",
  "action": "rise",
  "displayData": {
    "label": "佛山一号仓",
    "inventory": 5492
  }
}
```

前端处理：

- `action !== 'fall'` 时调用 `riseCity(cityName)`。
- 同时调用 `updateCityData(cityName, displayData)` 更新标签数据。

#### 仓库聚焦面板消息

```json
{
  "type": "warehouse_focus",
  "cityName": "佛山市",
  "style": {
    "width": 180,
    "maxHeight": 240,
    "padding": 10,
    "titleFontSize": 12,
    "bodyFontSize": 10,
    "chartTextFontSize": 10,
    "placement": "right",
    "theme": "cyan-dark"
  },
  "panels": [
    {
      "id": "category-ring",
      "title": "品类占比",
      "chartType": "ring",
      "height": 110,
      "columns": [
        { "key": "name", "label": "品类" },
        { "key": "value", "label": "吨" }
      ],
      "rows": [
        { "name": "铝锭", "value": 720 }
      ],
      "option": {}
    }
  ]
}
```

#### `WarehousePushService`

关键方法：

| 方法 | 说明 |
| --- | --- |
| `getWarehouseSnapshot()` | 获取所有仓库快照 |
| `pushWarehouseSnapshot()` | 广播所有仓库快照 |
| `getWarehouseFocus(String cityName)` | 获取城市聚焦面板 |
| `pushWarehouseFocus(String cityName)` | 广播城市聚焦面板 |
| `pushExternalFocusPanels(String cityName, List<Map<String,Object>> panels)` | 外部程序推送面板 |
| `uploadFocusTable(String cityName, String panelId, MultipartFile file)` | 上传 CSV 生成面板 |
| `validateAndNormalizePanels(...)` | 校验外部面板结构 |
| `panelFromConfig(...)` | 按配置生成面板对象 |
| `chartOption(...)` | 为 bar/line/pie/ring 生成 ECharts option |

### 4.3 道路车辆模块

#### `RoadController`

基础路径：

```text
/api/road
```

接口：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/dispatch` | 后端模拟发起一条随机车辆调度 |
| `GET` | `/groups` | 获取当前运输中路线分组 |
| `GET` | `/groups/{groupId}/routes` | 获取某个 group 内的路线 |
| `GET` | `/routes/{lineId}/position` | 查询车辆实时位置 |
| `GET` | `/path` | 按起终点生成模拟路径 |
| `POST` | `/path` | 按多航点生成模拟路径 |

#### 路线调度消息 `road_path`

```json
{
  "type": "road_path",
  "lineId": "uuid",
  "groupId": "group-1",
  "from": "佛山市",
  "to": "上海市",
  "coordinates": [[113.12, 23.02], [114.2, 24.1], [121.47, 31.23]],
  "created": true,
  "routeLengthKm": 1320.5,
  "speedKmh": 110,
  "travelDurationMs": 43200000
}
```

#### 车辆位置消息 `truck_position`

```json
{
  "type": "truck_position",
  "lineId": "uuid",
  "position": [114.2, 24.1],
  "velocity": [0.00001, 0.00002],
  "speedKmh": 82,
  "progress": 0.42,
  "status": "running"
}
```

完成时：

```json
{
  "type": "truck_position",
  "lineId": "uuid",
  "status": "finished"
}
```

#### `RoutePushService`

关键字段：

| 字段 | 说明 |
| --- | --- |
| `activeRoutes` | 当前运输中的路线，`lineId -> ScheduledRoute` |
| `lastPositionSamples` | 上一次位置样本，用于计算速度 |
| `passivePositionPushEnabled` | 是否启用后端被动位置推送 |
| `externalPositionUrl` | 外部车辆位置服务 URL |
| `groupSize` | 每组路线数量 |

关键方法：

| 方法 | 说明 |
| --- | --- |
| `dispatchRandomRoute()` | 创建随机路线并广播 `road_path` |
| `listRouteGroups()` | 返回当前运输中路线分组 |
| `listRoutesByGroup(String groupId)` | 返回某 group 中的路线 |
| `getPosition(String lineId)` | 查询车辆位置；不存在则返回 `finished` |
| `pushPassiveTruckPositions()` | 定时被动推送位置，受配置开关控制 |
| `fetchExternalVehiclePosition(String lineId)` | 请求外部位置服务 |
| `calculateSpeedKmh(...)` | 外部未给速度时，按连续位置计算平均速度 |
| `coordinateAtProgress(...)` | 按路径进度插值坐标 |
| `cleanupExpiredRoutes(...)` | 清理已到达路线 |

内部数据结构：

```java
private record ScheduledRoute(
    String lineId,
    String from,
    String to,
    List<double[]> coordinates,
    long startTime,
    double routeLengthKm,
    double speedKmh,
    long travelDurationMs
) {}
```

```java
private record ProviderPosition(
    double[] position,
    double speedKmh
) {}
```

```java
private record PositionSample(
    double[] position,
    long time
) {}
```

## 5. WebSocket 消息总览

| `type` | 方向 | 说明 |
| --- | --- | --- |
| `ping` | 前端 -> 后端 | 心跳 |
| `pong` | 后端 -> 前端 | 心跳响应 |
| `city_raise` | 后端 -> 前端 | 城市升起、飞线起点终点 |
| `city_fall` | 后端 -> 前端 | 城市下降 |
| `warehouse_update` | 后端 -> 前端 | 仓库城市数据更新 |
| `warehouse_focus` | 后端 -> 前端 | 聚焦城市面板 |
| `road_path` | 后端 -> 前端 | 新道路路线 |
| `truck_position` | 后端 -> 前端 | 车辆位置 |
| `camera_control` | 后端 -> 前端 | 预留镜头控制 |

## 6. 前端主页面 `DashboardPage`

文件：

```text
src/pages/Dashboard/DashboardPage.tsx
```

职责：

- 管理三种视图切换。
- 连接 WebSocket 并分发消息。
- 调用仓库和道路 REST API。
- 管理道路路线 group 循环播放。
- 管理车辆位置预测、主动校准和完成确认。
- 管理地图视图渐入渐出。

### 6.1 核心状态

```ts
type ViewMode = 'warehouse' | 'chinaMap' | 'roadMap';
```

道路路线：

```ts
type ActiveRoute = RouteOrder & {
  startedAt: number;
  fallbackDuration: number;
  coordinates: LonLat[];
  calibratedAt: number;
  calibratedDistance: number;
  pathSpeed: number;
  pathLength: number;
  routeLengthKm: number;
  speedKmh: number | null;
  nextCalibrationAt: number;
  arrivalCheckRequested: boolean;
};
```

道路 group 链表：

```ts
type RoadGroupNode = {
  groupId: string;
  next: RoadGroupNode | null;
};

type RoadGroupRing = {
  head: RoadGroupNode | null;
  tail: RoadGroupNode | null;
  current: RoadGroupNode | null;
  nodes: Map<string, RoadGroupNode>;
};
```

### 6.2 关键函数

| 函数 | 说明 |
| --- | --- |
| `requestViewChange(nextView)` | 视图切换入口 |
| `requestWarehouseSnapshot(prepareRunId)` | 请求仓库快照并准备 ChinaMap3D |
| `requestRoadMapSnapshot(prepareRunId)` | 请求道路 group 并准备 RoadMap3D |
| `fetchRoadGroups()` | 获取路线 group 列表 |
| `loadRoadGroup(groupId)` | 加载某 group 路线并渲染 |
| `advanceRoadGroup()` | 循环链表切换到下一 group |
| `removeRoadGroupFromRing(groupId)` | 从循环链表删除空 group |
| `createActiveRoute(message)` | 将 `road_path` 转为前端活动路线 |
| `renderTruckPosition(route, now)` | 按预测位置渲染车辆 |
| `requestTruckPosition(lineId)` | 主动向后端确认车辆位置 |
| `handleTruckPosition(message, forceCalibration)` | 处理位置推送/查询结果 |
| `finishRoute(lineId)` | 完成并移除路线 |

### 6.3 道路 group 播放逻辑

- 后端按 `group-size` 将当前运输中路线分组。
- 前端将 groupId 放入循环链表。
- 每 `VITE_ROAD_GROUP_DISPLAY_MS` 自动播放下一组。
- 如果轮到某 group 时后端返回路线为空，则删除该 group。
- 如果当前 group 内路线全部完成，也删除该 group。
- 新路线到来时只刷新队列，不强制打断当前 group。
- group 切换时使用 `VITE_ROAD_GROUP_TRANSITION_MS` 做短淡出/淡入。

### 6.4 车辆位置预测与校准

流程：

1. 加载路线后，前端基于路线坐标、后端速度、最近校准点预测车辆位置。
2. 每 `VITE_TRUCK_POSITION_RENDER_TICK_MS` 更新车辆点。
3. 到达 `nextCalibrationAt` 后主动请求 `/api/road/routes/{lineId}/position`。
4. 如果速度低于 `VITE_TRUCK_LOW_SPEED_THRESHOLD_KMH`，下一次确认间隔缩短。
5. 如果预测车辆已到终点，不等下一轮常规轮询，立即主动确认位置。
6. 若后端返回 `finished`，立即完成路线。
7. 若后端仍返回 `running`，以前端收到的准确位置重新校准，继续预测。

## 7. 前端 WebSocket Hook

文件：

```text
src/pages/Dashboard/hooks/useDashboardRealtime.ts
```

职责：

- 生成 WebSocket URL。
- 自动带 token。
- 心跳 ping/pong。
- 断线重连。
- 解析后端消息并调用页面回调。
- 对 `city_raise` / `city_fall` 做最小生命周期保护，避免飞线/城市动画太短。

关键配置：

```ts
const HEARTBEAT_INTERVAL_MS = 20_000;
const HEARTBEAT_STALE_MS = 55_000;
const RECONNECT_BASE_DELAY_MS = 1_000;
const RECONNECT_MAX_DELAY_MS = 10_000;
```

## 8. ChinaMap3D 模块

目录：

```text
src/pages/Dashboard/modules/ChinaMap3D/
```

### 8.1 对外 Handle

```ts
export type ChinaMap3DHandle = {
  riseCity(cityName: string): void;
  fallCity(cityName: string): void;
  flyToCity(cityName: string): void;
  addFlyLine(lineId, fromCoords, toCoords): void;
  removeFlyLine(lineId: string): void;
  updateCityData(cityName, data): void;
  focusOnCities(cityNames, mode): void;
  isReady(): boolean;
  startWarehouseTour(): void;
  showCityPanels(cityName, panels, style?): void;
  cacheCityPanels(cityName, panels, style?): void;
  showCachedCityPanels(cityName): boolean;
  clearCityPanels(cityName): void;
};
```

### 8.2 主要 hooks

| Hook | 说明 |
| --- | --- |
| `useMapScene` | 初始化 Three 场景、中国地图 mesh、透明背景、首帧 ready |
| `useChinaMapRefs` | 保存 Three 对象、城市状态、标签、面板、动画帧等 ref |
| `useCityControls` | 城市升降、城市数据更新 |
| `useCameraControls` | 镜头平滑聚焦、俯瞰、视角限制 |
| `useWarehouseTour` | 仓库巡游流程：佛山 -> 俯瞰 -> 顺序聚焦仓库 -> 回到俯瞰 |
| `useWarehouseLabels` | 仓库标签定位、可见性控制 |
| `useCityPanels` | 聚焦城市数据面板渲染和 ECharts 自适应 |
| `useFlyLines` | 城市飞线和小球动画 |
| `useMapHover` | 城市 hover |

### 8.3 仓库巡游流程

当前流程：

1. 进入数字孪生地图。
2. 后台挂载 ChinaMap3D。
3. 请求 `/api/warehouse/snapshot/push`，所有仓库城市升起。
4. 并行预取城市面板 `/api/warehouse/focus/{city}`。
5. 地图首帧 ready + 数据 ready 后渐入。
6. 启动 `startWarehouseTour()`。
7. 先聚焦佛山。
8. 拉升到仓库城市全局俯瞰。
9. 顺序聚焦每一个仓库城市。
10. 循环回到俯瞰。

### 8.4 面板布局与自适应

文件：

```text
src/pages/Dashboard/modules/ChinaMap3D/hooks/useCityPanels.ts
```

能力：

- 根据 `PanelStyle` 控制外框尺寸。
- 按实际外框计算内部 chart 宽高。
- 表格按行数和字号计算最小高度。
- ECharts 饼图/环图根据实际 chart 尺寸调整：
  - `radius`
  - `center`
  - `label.fontSize`
  - `label.width`
  - `labelLine.length`
  - `minShowLabelAngle`
- bar/line 根据 chart 尺寸调整：
  - `grid`
  - `barWidth`
  - `symbolSize`

## 9. RoadMap3D 模块

目录：

```text
src/pages/Dashboard/modules/RoadMap3D/
```

### 9.1 对外 Handle

```ts
export type RoadMap3DHandle = {
  setRoadPath(coords): void;
  addRoadPath(id, coords, info?): void;
  removeRoadPath(id): void;
  clearRoads(): void;
  updateTruckPosition(lineId, position, info?): void;
  refreshAllPositions(): void;
};
```

### 9.2 数据结构

```ts
export interface RoadState {
  group: THREE.Group;
  grayTube: THREE.Mesh;
  selectionTube: THREE.Mesh;
  greenTube: THREE.Mesh;
  truck: THREE.Mesh;
  truckGlow: THREE.Mesh;
  selectionRing: THREE.Mesh;
  dragControls: DragControls | null;
  samples: THREE.Vector3[];
  cumulativeLengths: number[];
  totalLength: number;
  tubularSegments: number;
  radialSegments: number;
  progressRef: { current: number };
  currentCoords: [number, number];
  labelAnchor: THREE.Vector3;
  info: RoadObjectInfo;
  isSelected: boolean;
}
```

```ts
export type RoadObjectInfo = {
  plate?: string;
  cargo?: string;
  from?: string;
  to?: string;
  status?: string;
  speedKmh?: number | null;
  routeLengthKm?: number;
};
```

### 9.3 主要 hooks

| Hook | 说明 |
| --- | --- |
| `useRoadMapScene` | 初始化道路级三维地图、透明背景、首帧 ready |
| `useRoadMapRefs` | 保存 scene/camera/renderer/controls/roadsMap |
| `useRoadControls` | 添加路线、清除路线、更新车辆位置、绿色进度条、镜头平滑聚焦 |
| `useRoadSelection` | 鼠标 hover/选中路线、浮动信息牌 |

### 9.4 车辆路线表现

- 灰色管线：完整路线。
- 绿色管线：已行驶路线，按车辆在路线上的投影距离计算。
- 橙色点：车辆。
- 光晕和选中 ring：用于 hover/选中视觉反馈。
- 信息牌：显示车牌、货物、坐标、速度、目的地等。

### 9.5 道路镜头控制

新增/切换路线时：

- 不再瞬移到某条路线。
- 收集当前所有运行路线的起点、中点、终点、车辆点。
- 计算包围盒。
- 使用 `easeInOutCubic` 平滑移动相机。
- 防抖触发，批量添加多条路线时只移动一次镜头。

## 10. 视图切换与异步加载

数字孪生地图和道路级地图都采用后台挂载机制：

1. 用户点击目标视图。
2. 目标地图组件先以 `opacity: 0` 挂载。
3. 地图 Three.js 首帧 render 后触发 `onVisualReady`。
4. 数据请求完成后设置 data ready。
5. visual ready + data ready 同时满足时开始渐入。
6. 旧视图在新视图淡入约 45% 后释放，避免两个面板长时间重叠。

相关配置：

```env
VITE_MAP_VIEW_TRANSITION_MS=800
VITE_ROAD_GROUP_TRANSITION_MS=420
```

## 11. 旧文件说明

当前前端目录中存在：

```text
ChinaMap3D-used.tsx
RoadMap3D-used.tsx
```

这些更像历史备份文件。构建仍会扫描它们，因此曾经修过少量 TS 报错。后续如果确认不再使用，建议移出 `src` 或重命名到不参与构建的备份目录。

## 12. 运行命令

### 12.1 后端

在后端目录：

```bash
mvn spring-boot:run
```

或：

```bash
mvn test
```

### 12.2 前端

在前端目录：

```bash
npm run dev
```

构建：

```bash
npm run build
```

当前构建会出现 chunk 体积警告，不影响运行。后续可通过动态导入拆分 Three/ECharts 相关模块。

## 13. 后续建议

1. 将 `ChinaMap3D-used.tsx`、`RoadMap3D-used.tsx` 移出源码构建范围。
2. 将道路地图 group 播放状态持久化到后端，便于多屏一致。
3. 外部车辆位置服务正式接入后，明确返回结构：

```json
{
  "position": [113.12, 23.02],
  "speedKmh": 82,
  "status": "running"
}
```

4. 仓库聚焦面板后续可支持更多图表类型，但必须先在 `warehouse.yml` 声明结构。
5. 后续如果加入数据库，应优先持久化：
   - 仓库基础配置
   - 面板配置
   - 运输订单
   - 车辆实时位置快照
   - group 分配状态
