# 聚申数字孪生后端

Spring Boot 3.3.5 / Java 17 后端，为数字孪生大屏提供订单同步、RM1/RM2 路线、车辆位置、仓储数据、REST API 与 WebSocket 推送。

## 文档

- 系统总览与完整交接：../TECHNICAL_DOCUMENTATION.md
- RM2 多订单复合行程：docs/RM2_COMPOSITE_TRIP_TECHNICAL_GUIDE.md
- ChinaMap 仓库六接口：docs/CHINA_MAP_MANAGEMENT_API.md
- 仓库聚焦协议：docs/WAREHOUSE_FOCUS_TECHNICAL_DOCUMENTATION.md
- 早期阶段文档：TECHNICAL_DOCUMENTATION.md、docs/PROJECT_TECHNICAL_DOCUMENTATION.md（仅供追溯）

## 本地运行

~~~powershell
mvn spring-boot:run
~~~

默认地址：

- 服务：http://localhost:8080
- 健康检查：http://localhost:8080/api/health
- WebSocket：ws://localhost:8080/ws/realtime

真实密钥和外部接口配置放在 src/main/resources/application-private.yml，不要提交到仓库。

## 鉴权

本机或白名单设备通过以下接口取得会话：

~~~http
POST /api/auth/session
~~~

后续 REST 请求携带：

~~~http
Authorization: Bearer <accessToken>
~~~

WebSocket 使用同一会话密钥。会话校验和刷新接口分别是 GET /api/auth/session、POST /api/auth/session/refresh。

## RM2 常用接口

- GET /api/road/groups/structure?scope=rm2
- GET /api/road/groups?scope=rm2&snapshotVersion={version}
- GET /api/road/groups/{groupId}/routes?scope=rm2&snapshotVersion={version}
- GET /api/public/vehicle-order-chain/trips
- GET /api/public/vehicle-order-chain/transit-metrics
- POST /api/road/town/provinces/raw

## 测试与打包

~~~powershell
mvn test
mvn -DskipTests package
~~~

可执行包生成在 target/digital-twin-service-0.0.1-SNAPSHOT.jar。

## 运行数据

~~~text
runtime-data/
  daily-order-statistics.json
  vehicle-order-chain/
    records/
    vehicles/
    trips/
  vehicle-position-history/
~~~

运行数据属于业务缓存和审计证据。排障前先核对文件更新时间，不要直接删除 Trip 或位置历史。
