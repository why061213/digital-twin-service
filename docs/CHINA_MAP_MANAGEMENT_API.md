# ChinaMap 仓库维护接口

本文档描述城市/仓库、中心图表结构和图表数据的六个接口。所有 JSON 使用 UTF-8，基础路径为 `/api/warehouse/china-map`。

## 1. 接口总览

| 方向 | 城市管理 | 图表管理 | 数据调整 |
|---|---|---|---|
| 我方主动 GET 上游 | `GET /cities/sync` | `GET /charts/sync` | `GET /data/sync` |
| 外部 POST 我方 | `POST /cities` | `POST /charts` | `POST /data` |

三个 GET 是同步触发接口：服务端分别读取 `warehouse.yml` 的 `warehouses.external-sync.cities-url/charts-url/data-url`，向该地址发起 GET，再调用与 POST 完全相同的校验和应用逻辑。地址留空时返回 HTTP 200：

```json
{"resource":"cities","configured":false,"applied":0,"message":"external sync URL is not configured"}
```

请求校验失败返回 HTTP 400，格式为 `{"error":"invalid_request","message":"..."}`。修改只作用于当前进程的运行态并立即通过 WebSocket 广播；长期维护系统应把上游作为配置事实源，服务重启后再调用三个 GET 同步接口。

## 2. 城市/仓库管理

```http
POST /api/warehouse/china-map/cities
Content-Type: application/json; charset=UTF-8
```

```json
{
  "operation": "ADD",
  "cities": [
    {"cityId": "440600", "cityName": "佛山市", "warehouseName": "佛山一号仓"}
  ]
}
```

- `operation` 必填，接受 `ADD/DELETE/REPLACE`，同时兼容 `添加/删减/覆盖`。
- `cities` 必填且非空。`cityId` 为预留稳定标识；当前地图仍以 `cityName` 匹配。
- `ADD` 会按城市名更新或新增；`DELETE` 不要求 `warehouseName`，并清除该城市的图表；`REPLACE` 先清空全部城市，再按本次列表重建。

PowerShell 示例：

```powershell
$body = @{ operation = 'ADD'; cities = @(@{ cityId='440600'; cityName='佛山市'; warehouseName='佛山一号仓' }) } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/warehouse/china-map/cities' -ContentType 'application/json; charset=utf-8' -Body $body
```

## 3. 中心图表结构管理

```http
POST /api/warehouse/china-map/charts
```

```json
[
  {
    "cityName": "佛山市",
    "operation": "ADD",
    "charts": [
      {
        "position": 1,
        "chartType": "bar",
        "chartData": {"id":"throughput", "title":"今日吞吐量", "rows":[]}
      }
    ]
  }
]
```

- 外层必须是数组，允许一次维护多个城市。
- `position` 范围 1–8。添加时可省略，系统从第一个空位顺位放置；删除时必填；覆盖时可省略并从 1 开始顺位放置。
- 非删除操作的 `chartType`、`chartData` 必填。`chartData` 为开放对象，可包含 `id/title/rows/option/columns/height`。
- `REPLACE` 仅覆盖目标城市的八个中心槽位，不影响其他城市和侧面板。

## 4. 图表数据调整

```http
POST /api/warehouse/china-map/data
```

```json
[
  {
    "sidePanel": true,
    "cityId": "440600",
    "cityName": "佛山市",
    "position": 1,
    "chartData": {"id":"inventory", "title":"实时库存", "chartType":"table", "rows":[]}
  }
]
```

- `sidePanel/position/chartData` 必填；`cityName` 与已通过城市管理登记的 `cityId` 至少提供一个。两者同时提供时以 `cityName` 为准。
- `sidePanel=true` 时位置 1–4 依次为左上、左下、右上、右下；`false` 时位置为中心槽位 1–8。
- 调整采用浅合并：只更新 `chartData` 中出现的字段，不会清空同槽位其他字段。
- 侧面板更新广播 `warehouse_focus`；中心更新广播 `warehouse_chart_update`，同时进入后续 `/api/warehouse/snapshot` 的 `displayData.charts`。

## 5. 上游 GET 返回约定

`cities-url` 返回第 2 节的单个对象；`charts-url` 返回第 3 节数组；`data-url` 返回第 4 节数组。配置示例：

```yaml
warehouses:
  external-sync:
    cities-url: "https://upstream.example.com/china-map/cities"
    charts-url: "https://upstream.example.com/china-map/charts"
    data-url: "https://upstream.example.com/china-map/data"
```

配置后调用：

```powershell
Invoke-RestMethod 'http://localhost:8080/api/warehouse/china-map/cities/sync'
Invoke-RestMethod 'http://localhost:8080/api/warehouse/china-map/charts/sync'
Invoke-RestMethod 'http://localhost:8080/api/warehouse/china-map/data/sync'
```
