# ChinaMap 仓库维护接口

本文档描述城市/仓库、中心图表结构和图表数据的六个接口。所有 JSON 使用 UTF-8，基础路径为 `/api/warehouse/china-map`。

## 0. 鉴权与通用调用约定

所有接口都受大屏访问鉴权保护。先申请会话，再把响应中的 `accessToken` 放入 Bearer 请求头：

```powershell
$session = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/auth/session' `
  -ContentType 'application/json; charset=utf-8' `
  -Body '{}'

$headers = @{ Authorization = "Bearer $($session.accessToken)" }
```

注意字段名是 `accessToken`，不是 `token`。缺少或使用错误的 Bearer Token 时返回 HTTP 401，错误码为 `dashboard_access_key_required`。

`POST /charts` 和 `POST /data` 的最外层固定为数组，即使只有一条记录也必须发送 `[{...}]`。PowerShell 处理单元素数组时，建议使用 `ConvertTo-Json -InputObject $body`，不要使用 `$body | ConvertTo-Json`，因为管道可能将单元素数组展开成对象并导致 HTTP 400。

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
$payload = @{ operation = 'ADD'; cities = @(@{ cityId='440600'; cityName='佛山市'; warehouseName='佛山一号仓' }) }
$body = ConvertTo-Json -InputObject $payload -Depth 5
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/warehouse/china-map/cities' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body $body
```

补充行为：

- `ADD` 是按 `cityName` 执行 upsert；重复添加同名城市会更新仓库名，不会产生两个城市。
- `DELETE` 删除不存在的城市仍返回成功，且 `applied` 仍按请求项计数；`applied` 表示处理项数，不等于实际变化项数。
- 删除城市会同时清除该城市的中心图表、侧面板和 `cityId -> cityName` 运行态映射。
- `REPLACE` 是全局城市覆盖，不是单城市覆盖，调用前必须携带希望继续保留的全部城市。

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
- `ADD` 指定已占用的位置时会直接覆盖该槽位，并不是冲突报错。
- 自动顺位会查找当前第一个空槽；八个槽位全部占满后继续省略 `position` 会返回 HTTP 400。
- 删除不存在的槽位仍返回成功，且计入 `applied`。

PowerShell 单元素数组示例：

```powershell
$payload = @(
  @{
    cityName = '佛山市'
    operation = 'ADD'
    charts = @(
      @{ chartType='bar'; chartData=@{ id='throughput'; title='今日吞吐量'; rows=@() } }
    )
  }
)
$body = ConvertTo-Json -InputObject $payload -Depth 10
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/warehouse/china-map/charts' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body $body
```

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
- `cityName` 与 `cityId` 同时提供时始终以 `cityName` 为准；即使 `cityId` 有效，错误的 `cityName` 仍会导致 HTTP 400。
- `cityId` 只在城市管理接口登记后于当前进程有效，城市删除或全局城市覆盖会清除对应映射。
- 空的 `chartData: {}` 当前是合法输入，会创建或保留槽位并补入 `position`；如果调用方不希望生成空面板，应在上游自行阻止。

完整调用示例：

```powershell
$payload = @(
  @{
    sidePanel = $false
    cityId = '440600'
    position = 1
    chartData = @{ title='实时吞吐量'; unit='吨'; rows=@(@{ name='当前'; value=456 }) }
  },
  @{
    sidePanel = $true
    cityName = '佛山市'
    position = 1
    chartData = @{ id='vehicle'; title='车辆状态'; chartType='bar'; rows=@() }
  }
)
$body = ConvertTo-Json -InputObject $payload -Depth 10
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/warehouse/china-map/data' `
  -Headers $headers `
  -ContentType 'application/json; charset=utf-8' `
  -Body $body
```

## 5. 批量请求的一致性风险

当前三个 POST 接口均为运行态逐项应用，**不具备事务回滚能力**。服务会按数组顺序校验并写入：如果前面的项合法、后面的项非法，接口整体返回 HTTP 400，但前面已经执行的修改仍然保留。

实测确认以下三类请求都会部分生效：

- 城市请求中，第一个城市合法、第二个城市缺少仓库名。
- 图表请求中，第一个城市/槽位合法、第二个槽位超出 1–8。
- 数据调整中，第一项合法、第二项使用非法侧面板位置 5。

调用方应遵守以下策略：

1. 发送前完整校验整个批次，不要依赖服务端失败后的自动回滚。
2. 对强一致性要求高的修改使用单项请求，或按城市拆成较小批次。
3. 收到 HTTP 400 后先通过 `GET /api/warehouse/snapshot` 和 `GET /api/warehouse/focus/{cityName}` 回读，再决定重试范围。
4. 重试时注意：城市 `ADD`、图表同槽 `ADD` 和数据调整是覆盖/合并语义，通常可安全重复；但 `applied` 只是已处理数量，不能作为实际变化数量。

## 6. 回读验证

城市和中心图表通过快照接口验证：

```http
GET /api/warehouse/snapshot
Authorization: Bearer <accessToken>
```

该接口返回的是**顶层数组**，不是 `{ "cities": [...] }`。每项结构为：

```json
{
  "type": "warehouse_update",
  "cityName": "佛山市",
  "action": "rise",
  "displayData": {
    "label": "佛山一号仓",
    "charts": []
  }
}
```

侧面板通过城市聚焦接口验证：

```http
GET /api/warehouse/focus/%E4%BD%9B%E5%B1%B1%E5%B8%82
Authorization: Bearer <accessToken>
```

城市名放入路径前必须进行 URL 编码。响应的侧面板位于 `panels` 字段。

## 7. 2026-07-29 本地接口实测记录

测试环境：`http://localhost:8080`，使用独立临时城市，测试结束后调用城市删除接口完成清理。共执行 28 个断言场景，28 个通过。

| 分类 | 已验证场景 | 结果 |
|---|---|---|
| 鉴权 | 正确 `accessToken`、缺少 Bearer Token | 通过；缺鉴权返回 401 |
| 城市 | 中文别名、批量添加、同名更新、非法操作、重复删除、删除级联 | 通过 |
| 图表 | 显式位置、自动顺位、同槽覆盖、单城市 REPLACE、按位置删除、槽位耗尽 | 通过 |
| 数据 | 仅 `cityId`、名称优先、中心/侧面板批量、浅合并、中文字段、空对象 | 通过 |
| 校验 | 未知城市、未知 ID、中心位置 9、侧面板位置 5、缺少 `sidePanel`、空数组 | 均正确返回 400 |
| 请求形状 | `/charts` 最外层误传对象 | 正确返回 400 |
| 一致性 | 城市、图表、数据三种批量中途失败 | 均确认前项不会回滚 |

本次实测没有发现正常请求无法写入或无法回读的问题。最重要的接入约束是：Bearer 字段使用 `accessToken`、图表和数据保持数组外壳、批量请求失败后必须回读确认实际状态。

## 8. 上游 GET 返回约定

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
