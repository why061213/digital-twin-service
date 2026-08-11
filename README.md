# 聚申数字孪生后端

Spring Boot 后端，为数字孪生大屏提供鉴权、订单同步、路线规划、RM1/RM2 分组、车辆位置、仓储管理、REST API 和 WebSocket 推送。

## 文档

- 本文件：运行、配置、接口入口和发布。
- `TECHNICAL_DOCUMENTATION.md`：后端架构、数据管线、领域模型、接口和排障。
- 跨前后端协议见工作区根目录 `TECHNICAL_DOCUMENTATION.md`。

原有阶段记录、专项指南和案例 Markdown 已合并到技术文档，不再单独维护。

## 环境要求

- JDK 17
- Maven 3.9 或更高版本
- 可选：外部订单、车辆位置、百度地图和高德地图凭据

## 本地运行

```powershell
mvn spring-boot:run
```

默认地址：

- 服务：http://localhost:8080
- 健康检查：http://localhost:8080/api/health
- WebSocket：ws://localhost:8080/ws/realtime

## 私有配置

公开默认值在 `src/main/resources/application.yml`。真实值写入被忽略的：

```text
src/main/resources/application-private.yml
```

常见私有项：

```yaml
dashboard:
  access:
    allowed-mac-addresses: []
    device-token: ""
  route:
    auth-enabled: false
    auth-url: ""
    auth-id: ""
    auth-secret: ""
    external-position-url: ""
    external-position-token: ""
  coord-db:
    amap-key: ""
    amap-secret: ""
  route-plan:
    baidu-ak: ""
    amap-key: ""
```

不得把真实值写入 README、技术文档、测试 fixture 或公开 YAML。

## 常用接口

- `POST /api/auth/session`
- `GET /api/bootstrap/status`
- `GET /api/road/groups?scope=rm1`
- `GET /api/road/groups/structure?scope=rm2`
- `GET /api/road/groups/{groupId}/routes?scope=rm2`
- `GET /api/warehouse/snapshot`
- `GET /api/public/vehicle-order-chain/trips`
- `GET /api/public/vehicle-order-chain/transit-metrics`

完整接口表见技术文档。

## 测试与打包

```powershell
mvn test
mvn -DskipTests package
```

可执行 JAR：

```text
target/digital-twin-service-0.0.1-SNAPSHOT.jar
```

运行：

```powershell
java -jar target\digital-twin-service-0.0.1-SNAPSHOT.jar
```

## 运行数据

`runtime-data/` 保存订单差分、Trip、车辆位置历史和 KPI 缓存。它不属于发布包源码，也不能随意清空。排障或迁移前应停止服务并备份。

## 发布前检查

1. `mvn test` 全部通过。
2. `mvn -DskipTests package` 成功。
3. 公开配置中的密钥字段为空。
4. `application-private.yml`、`runtime-data/` 和 `target/` 未被 Git 跟踪。
5. 健康检查、鉴权、RM1/RM2 快照、仓储快照和 WebSocket 完成冒烟。
