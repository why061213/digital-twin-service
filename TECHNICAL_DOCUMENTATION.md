# 巨神数字孪生大屏技术文档

> **历史基线文档（2026-06）**：本文件描述早期纯模拟架构，固定 Token、项目路径和道路模块说明已不适用于当前 dashboard-v2。当前系统总览见仓库上级的 TECHNICAL_DOCUMENTATION.md；RM2 多订单实现与排障见 docs/RM2_COMPOSITE_TRIP_TECHNICAL_GUIDE.md。

## 1. 项目概述

本项目由 Spring Boot 后端和 React + Three.js 前端组成，用于构建物流数字孪生大屏。

后端负责生成模拟业务数据，并通过 WebSocket 主动推送给前端。前端负责展示 KPI、库存、车辆调度、车流能耗、三维仓库、中国城市三维地图、业务线路飞线和镜头联动。

当前核心能力：

- 后端每 3 秒推送 KPI、库存、车辆、车流能耗模拟数据。
- 后端每 5 到 10 秒生成一条随机城市货运线路。
- 前端收到 `city_raise` 后，抬升起点和目的地城市，并生成对应飞线。
- 前端收到 `city_fall` 后，下落对应城市，并移除对应飞线。
- 佛山作为总部节点，永久高亮和抬升。
- 右侧车辆调度面板保留最近 8 条线路记录，先进先出。
- 镜头自动平滑框选佛山和所有活跃货运节点。

## 2. 项目目录

后端项目：

```text
E:\wendang\jushen-digital-twin
```

前端项目：

```text
E:\jushen\data-showing-web\jishen-digital-twin
```

后端主要文件：

```text
src/main/java/com/jushen/digitaltwin/DigitalTwinBackendApplication.java
src/main/java/com/jushen/digitaltwin/config/WebSocketConfig.java
src/main/java/com/jushen/digitaltwin/websocket/RealtimeWebSocketHandler.java
src/main/java/com/jushen/digitaltwin/websocket/TokenHandshakeInterceptor.java
src/main/java/com/jushen/digitaltwin/service/RealtimePushService.java
src/main/java/com/jushen/digitaltwin/service/SimulationDataFactory.java
src/main/resources/application.yml
```

前端主要文件：

```text
src/pages/Dashboard/DashboardPage.tsx
src/pages/Dashboard/hooks/useDashboardRealtime.ts
src/pages/Dashboard/modules/ChinaMap3D.tsx
src/pages/Dashboard/modules/VehicleSchedule.tsx
src/pages/Dashboard/modules/InventoryStats.tsx
src/pages/Dashboard/modules/TrafficMonitor.tsx
src/pages/Dashboard/modules/Warehouse3D.tsx
```

## 3. 技术栈

后端：

- Java 17+
- Spring Boot
- Spring WebSocket
- Spring Scheduling
- Maven

前端：

- React
- TypeScript
- Vite
- Three.js
- ECharts
- Tailwind CSS

## 4. 后端设计

### 4.1 启动入口

后端启动类：

```text
DigitalTwinBackendApplication.java
```

该类启用 Spring Boot 和定时任务：

```java
@EnableScheduling
@SpringBootApplication
```

### 4.2 WebSocket 地址

前端连接地址：

```text
ws://localhost:8080/ws/realtime?token=jushen-screen-token
```

### 4.3 Token 校验

后端通过 `TokenHandshakeInterceptor` 校验固定 token。

配置位置：

```text
src/main/resources/application.yml
```

当前 token：

```yaml
dashboard:
  websocket:
    token: jushen-screen-token
```

如果 token 不正确，WebSocket 握手会返回未授权。

### 4.4 WebSocket 会话管理

`RealtimeWebSocketHandler` 负责：

- 保存当前在线 WebSocket 会话。
- 在连接建立后加入会话集合。
- 在连接关闭或异常时移除会话。
- 将后端生成的消息序列化为 JSON 并广播给所有在线前端。

### 4.5 定时推送服务

`RealtimePushService` 负责两类定时任务。

实时数据推送：

```text
每 3 秒推送一次
```

消息类型：

```text
kpi
inventory
vehicle
traffic_energy
```

业务线路推送：

```text
每 5 到 10 秒随机生成一条城市对线路
```

生成后立即推送：

```text
city_raise
```

线路到期后推送：

```text
city_fall
```

当前策略：

- 支持随机城市对，不固定从佛山出发。
- 佛山只是总部常亮节点。
- 最后一条线路在无新订单时可以保持展示。
- 多条线路短时间堆积时可并发存在。

### 4.6 模拟数据工厂

`SimulationDataFactory` 负责生成所有模拟数据：

- KPI 数据
- 库存数据
- 车辆数据
- 车流能耗数据
- 城市线路数据
- 城市经纬度

后续如需接入真实数据，优先替换该类的数据来源，尽量保持 WebSocket 消息协议不变。

## 5. WebSocket 消息协议

### 5.1 KPI 数据

```json
{
  "type": "kpi",
  "revenue": 128.5,
  "orderCount": 387,
  "completionRate": 94.2,
  "punctualityRate": 97.8
}
```

字段说明：

- `revenue`：今日产值，单位万元。
- `orderCount`：运输单量。
- `completionRate`：完成率。
- `punctualityRate`：准点率。

### 5.2 库存数据

```json
{
  "type": "inventory",
  "total": 12980,
  "todayIn": 345,
  "todayOut": 278,
  "categoryPie": [
    { "name": "铝锭", "value": 3200 },
    { "name": "铜材", "value": 2800 }
  ],
  "hourlyTrend": [
    { "time": "10:00", "in": 65, "out": 52 }
  ]
}
```

### 5.3 车辆数据

```json
{
  "type": "vehicle",
  "inUse": 24,
  "idle": 8,
  "queueList": [
    {
      "plate": "粤A·HQ7832",
      "cargo": "铝锭",
      "estimated": "14:20",
      "status": "装载中"
    }
  ]
}
```

说明：当前右侧车辆调度面板主要使用 `city_raise` 线路消息驱动，不依赖该静态队列字段。

### 5.4 车流能耗数据

```json
{
  "type": "traffic_energy",
  "trafficData": {
    "times": ["10:00", "10:10", "10:20"],
    "in": [42, 78, 115],
    "out": [35, 67, 98]
  },
  "energyData": {
    "times": ["10:00", "10:10", "10:20"],
    "electricity": [210, 280, 340],
    "water": [5.2, 6.1, 7.8]
  }
}
```

### 5.5 城市升起和飞线生成

```json
{
  "type": "city_raise",
  "from": "北京市",
  "to": "上海市",
  "fromCoords": [116.4074, 39.9042],
  "toCoords": [121.4737, 31.2304],
  "lineId": "uuid"
}
```

前端行为：

- 抬升 `from` 城市。
- 抬升 `to` 城市。
- 在右侧车辆调度面板追加一条线路记录。
- 根据 `fromCoords` 和 `toCoords` 生成三维飞线。
- 镜头平滑移动，使佛山和所有活跃货运节点均在视野内。

### 5.6 城市下降和飞线移除

```json
{
  "type": "city_fall",
  "from": "北京市",
  "to": "上海市",
  "lineId": "uuid"
}
```

前端行为：

- 按 `lineId` 移除对应飞线。
- 下落线路两端城市。
- 如果某城市仍被其他活跃线路使用，则不会提前下落。
- 佛山不会下落。
- 右侧车辆调度记录不删除，仅当记录超过 8 条时按先进先出删除最早记录。

## 6. 前端设计

### 6.1 DashboardPage

`DashboardPage.tsx` 是大屏主页面，负责：

- 管理仓库视图和地图视图切换。
- 持有 `ChinaMap3D` 的组件引用。
- 接收 WebSocket Hook 派发的事件。
- 将线路数据传给 `VehicleSchedule`。
- 调用地图组件的城市抬升、城市下降、飞线生成、飞线移除方法。

### 6.2 WebSocket Hook

`useDashboardRealtime.ts` 负责：

- 建立 WebSocket 连接。
- 自动携带 token。
- 解析后端 JSON 消息。
- 根据 `message.type` 分发不同事件。
- 维护城市活跃计数，避免多条线路共用同一城市时提前下降。

关键事件：

```ts
onCityRaise(cityName)
onCityFall(cityName)
onRouteRaise(order)
onRouteFall(lineId)
```

### 6.3 右侧车辆调度面板

`VehicleSchedule.tsx` 当前展示最近 8 条线路记录。

列结构：

```text
车牌号 / 货物 / 起点——目的地 / 状态
```

规则：

- “起点——目的地”列文字过长时在所在格子内横向滚动。
- 线路下落后不删除历史记录。
- 超过 8 条时删除最早记录。

## 7. 三维地图和飞线

### 7.1 城市地图加载

`ChinaMap3D.tsx` 从阿里云行政区 GeoJSON 数据加载全国城市边界。

加载逻辑：

- 先加载全国省级数据。
- 直辖市直接作为城市要素。
- 其他省份继续加载地级市数据。
- 使用 `d3-geo` 将经纬度投影到 Three.js 世界坐标。

### 7.2 城市抬升与下落

城市对象使用 `THREE.ExtrudeGeometry` 挤出为三维区域块。

城市状态：

```text
0：地面状态
1：抬升状态
```

佛山规则：

- 初始即抬升。
- 金色高亮。
- 不响应下降逻辑。

其他业务城市：

- 收到 `city_raise` 后变为青色并抬升。
- 收到 `city_fall` 且没有其他活跃线路占用时恢复默认颜色并下降。

### 7.3 飞线生命周期

飞线由 `lineId` 管理：

```ts
addFlyLine(lineId, fromCoords, toCoords)
removeFlyLine(lineId)
```

飞线生成时序：

1. WebSocket 收到 `city_raise`。
2. 两端城市先开始抬升。
3. 等城市抬升动画完成后，再生成飞线和小球。

当前飞线由多层对象组成：

- 暗色底线。
- 低饱和蓝紫色主流动管线。
- 淡蓝紫外层辉光。
- 冷白小球。
- 小球拖尾粒子。

小球动画：

- 首次生成时跟随线条生长从起点移动到终点。
- 线条完整后，小球从起点重新开始循环运动。
- 小球不会在终点隐藏。

### 7.4 镜头控制

镜头控制目标：

```text
始终让佛山 + 所有活跃货运节点处于视野内
```

实现逻辑：

- 使用佛山坐标和当前所有活跃线路的起终点坐标生成点集合。
- 计算点集合的 `Box3` 包围盒。
- 根据包围盒宽度、深度和相机 FOV 计算所需高度。
- 使用缓动函数平滑插值相机位置和 OrbitControls target。

初始化逻辑：

- 切换到地图视图时，相机初始落在佛山上方。
- 后续有订单时，再平滑扩展视野到佛山和所有活跃节点。

朝向规则：

- 相机 `up` 方向设置为世界 Z 正向。
- 屏幕上方对应地图北方，避免南北颠倒。

## 8. 运行方式

### 8.1 后端运行

使用 IntelliJ IDEA 2023 打开：

```text
E:\wendang\jushen-digital-twin
```

运行：

```text
DigitalTwinBackendApplication.java
```

健康检查：

```text
http://localhost:8080/api/health
```

### 8.2 前端运行

进入前端项目：

```text
E:\jushen\data-showing-web\jishen-digital-twin
```

开发运行：

```bash
npm.cmd run dev
```

构建检查：

```bash
npm.cmd run build
```

前端默认地址：

```text
http://localhost:5173
```

### 8.3 前端环境变量

`.env`：

```text
VITE_API_BASE_URL=http://localhost:8080/api
VITE_WS_URL=ws://localhost:8080/ws
```

实际连接地址由前端拼接为：

```text
ws://localhost:8080/ws/realtime?token=jushen-screen-token
```

## 9. 安全与跨域

当前安全策略较简单：

- 使用固定 token 校验 WebSocket 连接。
- 开发阶段允许任意来源连接 WebSocket。

上线建议：

- 将 token 改为后端可配置的正式密钥。
- 前端从登录态或配置接口获取 token。
- 限制 `allowed-origin-patterns` 为正式大屏域名。
- 如接入真实业务数据，应增加鉴权、审计和异常限流。

## 10. 后续扩展建议

### 10.1 接入真实数据

优先替换：

```text
SimulationDataFactory
```

建议保留当前 WebSocket 协议，避免前端大面积改动。

### 10.2 完善业务线路

可扩展字段：

```json
{
  "lineId": "uuid",
  "orderNo": "SO202606260001",
  "cargo": "铝锭",
  "weight": 32.5,
  "vehiclePlate": "粤A·HQ7832",
  "status": "运输中"
}
```

### 10.3 增强右侧调度面板

可增加：

- 当前活跃线路数量。
- 历史线路状态。
- 点击某条记录后镜头聚焦对应线路。
- 按货物类型或城市筛选。

### 10.4 优化三维性能

当前地图使用城市级 GeoJSON 直接挤出，后续可考虑：

- 缓存 GeoJSON。
- 简化边界点数量。
- 合并静态材质。
- 对飞线对象做对象池复用。
- 将大图表或 3D 模块按需加载。

## 11. 当前已知注意事项

- 前端构建会提示包体较大，这是 Three.js、ECharts、ECharts GL 等三维和图表依赖带来的常见提示，不影响运行。
- 当前后端数据为模拟数据，不能代表真实业务。
- 当前 WebSocket token 为固定值，仅适合开发和演示阶段。
- 如果本地 PowerShell 禁止执行 `npm.ps1`，可使用 `npm.cmd run build` 或 `npm.cmd run dev`。
