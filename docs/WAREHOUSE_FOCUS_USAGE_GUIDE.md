# 仓库聚焦数据面板使用文档

## 1. 启动前检查

后端：

1. 用 IntelliJ 打开 `E:/wendang/jushen-digital-twin`。
2. 确认项目使用 JDK 17。
3. 重新加载 Maven。
4. 启动 `DigitalTwinBackendApplication`。

前端：

1. 打开 `E:/jushen/data-showing-web/jishen-digital-twin`。
2. 确认 `.env` 中后端地址指向本地后端。
3. 启动前端开发服务。

WebSocket token 默认：

```text
jushen-screen-token
```

## 2. 页面内验证流程

1. 打开大屏页面。
2. 点击底部 `数字孪生地图`。
3. 前端会请求：

```http
POST http://localhost:8080/api/warehouse/snapshot/push
```

4. 仓库城市升起。
5. 前端会为每个仓库城市请求：

```http
GET http://localhost:8080/api/warehouse/focus/{cityName}
```

6. 镜头开始巡航：

```text
佛山初始视角 -> 全国仓库俯瞰 -> 逐个仓库聚焦 -> 回到俯瞰 -> 循环
```

7. 聚焦到某个城市时，只显示该城市标签和该城市的数据面板。

## 3. 获取某城市聚焦面板

请求：

```http
GET http://localhost:8080/api/warehouse/focus/佛山市
```

返回示例：

```json
{
  "type": "warehouse_focus",
  "cityName": "佛山市",
  "panels": [
    {
      "id": "inventory-table",
      "title": "实时库存明细",
      "chartType": "table",
      "rows": [
        { "metric": "总库存", "value": 5280, "unit": "吨" }
      ]
    }
  ]
}
```

这个接口只返回数据，不主动推送 WebSocket。

## 4. 主动推送模拟聚焦面板

请求：

```http
POST http://localhost:8080/api/warehouse/focus/佛山市/push
```

效果：

- 后端按 `warehouse.yml` 生成模拟面板。
- 后端推送 `warehouse_focus`。
- 前端收到后更新佛山聚焦面板。

## 5. 外部程序推送面板数据

样例文件：

`docs/warehouse-focus-sample-panels.json`

请求：

```bash
curl -X POST "http://localhost:8080/api/warehouse/focus/佛山市/panels" ^
  -H "Content-Type: application/json; charset=utf-8" ^
  --data-binary "@docs/warehouse-focus-sample-panels.json"
```

JSON 样例：

```json
[
  {
    "id": "inventory-table",
    "rows": [
      { "metric": "总库存", "value": 5280, "unit": "吨" },
      { "metric": "今日入库", "value": 318, "unit": "吨" },
      { "metric": "今日出库", "value": 276, "unit": "吨" }
    ]
  },
  {
    "id": "throughput-bar",
    "rows": [
      { "name": "08:00", "value": 126 },
      { "name": "10:00", "value": 188 }
    ]
  }
]
```

校验规则：

- `id` 必须存在于 `warehouse.yml` 的 `focus-panels.panels` 中。
- 每个 `rows` 行必须包含该面板的 `required-columns`。
- 校验通过后，后端会自动补齐 `title / chartType / columns / option`。

## 6. 上传 CSV 表格

样例文件：

`docs/warehouse-focus-upload-inventory.csv`

内容：

```csv
metric,value,unit
总库存,5280,吨
今日入库,318,吨
今日出库,276,吨
```

请求：

```bash
curl -X POST "http://localhost:8080/api/warehouse/focus/佛山市/upload?panelId=inventory-table" ^
  -F "file=@docs/warehouse-focus-upload-inventory.csv;type=text/csv"
```

注意：

- CSV 必须是 UTF-8。
- 第一行必须是表头。
- 表头必须覆盖配置中的 `required-columns`。
- 当前版本先支持 CSV；如果后续要上传 Excel，可以在后端 `uploadFocusTable` 中替换解析器。

## 7. 修改面板配置

配置文件：

`src/main/resources/warehouse.yml`

新增一个折线图例子：

```yaml
- id: order-line
  title: 订单趋势
  chart-type: line
  height: 132
  required-columns: [name, value]
  columns:
    - key: name
      label: 时间
    - key: value
      label: 订单数
```

外部推送时：

```json
[
  {
    "id": "order-line",
    "rows": [
      { "name": "08:00", "value": 24 },
      { "name": "10:00", "value": 36 },
      { "name": "12:00", "value": 42 }
    ]
  }
]
```

## 8. 常见问题

### 面板不显示

检查：

- 当前是否在 `数字孪生地图`。
- 是否已经请求仓库快照。
- WebSocket 是否连接成功。
- `warehouse_focus` 的 `cityName` 是否能匹配地图城市名。

### 上传失败

检查：

- `panelId` 是否存在于 `warehouse.yml`。
- CSV 表头是否包含所有 `required-columns`。
- 文件是否为 UTF-8。

### 图表为空

检查：

- `rows` 中是否包含 `name` 和 `value`。
- `chart-type` 是否是 `bar / line / pie / ring`。
- 浏览器控制台是否有 ECharts 初始化错误。

## 9. 推荐测试顺序

1. 先打开数字孪生地图，确认仓库城市升起。
2. 调用 `GET /api/warehouse/focus/佛山市` 看返回数据。
3. 调用 `POST /api/warehouse/focus/佛山市/push` 看前端是否更新。
4. 用 `warehouse-focus-sample-panels.json` 测外部推送。
5. 用 `warehouse-focus-upload-inventory.csv` 测 CSV 上传。
