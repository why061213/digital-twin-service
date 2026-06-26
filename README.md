# 巨神数字孪生后端

这是基于 Spring Boot 的后端服务，负责向前端大屏主动推送实时模拟数据和业务线路飞线事件。

## 1. 在 IntelliJ IDEA 2023 中打开

1. 打开 IntelliJ IDEA 2023。
2. 选择 `Open`。
3. 选择当前项目目录：`E:\wendang\jushen-digital-twin`。
4. IntelliJ 识别到 `pom.xml` 后，选择以 Maven 项目导入。
5. 等待右下角 Maven 依赖下载完成。

## 2. 运行后端

在 IntelliJ 左侧找到启动类：

`src/main/java/com/jushen/digitaltwin/DigitalTwinBackendApplication.java`

点击类左侧绿色运行按钮，或右键选择 `Run DigitalTwinBackendApplication`。

启动成功后，服务地址是：

```text
http://localhost:8080
```

健康检查地址：

```text
http://localhost:8080/api/health
```

## 3. WebSocket 连接方式

后端 WebSocket 地址：

```text
ws://localhost:8080/ws/realtime?token=jushen-screen-token
```

前端示例：

```js
const token = 'jushen-screen-token'
const socket = new WebSocket(`ws://localhost:8080/ws/realtime?token=${token}`)

socket.onmessage = (event) => {
  const message = JSON.parse(event.data)

  switch (message.type) {
    case 'kpi':
      console.log('KPI', message.data)
      break
    case 'inventory':
      console.log('库存', message.data)
      break
    case 'vehicle':
      console.log('车辆', message.data)
      break
    case 'energy':
      console.log('能耗', message.data)
      break
    case 'city_raise':
      console.log('城市升起/飞线出现', message.data)
      break
    case 'city_fall':
      console.log('城市回落/飞线移除', message.data.routeId)
      break
  }
}
```

## 4. 当前推送内容

每 3 秒推送一次：

- `kpi`：产值、订单完成率、设备开动率、良品率、活跃订单、在线车辆
- `inventory`：原料、半成品、成品、预警数量、周转率
- `vehicle`：车辆总数、运行、空闲、充电、故障、平均速度
- `energy`：电、水、气、碳排、负载率

每 5 到 10 秒随机生成一条业务线路：

- `city_raise`：城市升起，同时携带起点、终点、经纬度和飞线业务值
- `city_fall`：5 到 10 秒后自动回落，使用同一个 `routeId`

## 5. 修改 token

打开：

`src/main/resources/application.yml`

修改：

```yaml
dashboard:
  websocket:
    token: jushen-screen-token
```

前端连接时带上同一个 token 即可。

## 6. 开发阶段跨域

当前 WebSocket 允许任意前端来源连接，但必须带正确 token。

如果项目上线，建议把：

```yaml
allowed-origin-patterns:
  - "*"
```

改成你的正式前端地址，例如：

```yaml
allowed-origin-patterns:
  - "https://your-screen-domain.com"
```
"# digital-twin-service" 
