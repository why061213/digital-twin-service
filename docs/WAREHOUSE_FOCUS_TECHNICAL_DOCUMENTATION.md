# 仓库聚焦数据面板技术文档

## 1. 功能目标

仓库聚焦数据面板用于数字孪生地图 `ChinaMap3D` 的城市巡航场景：

1. 仓库城市升起并显示基础城市标签。
2. 镜头聚焦到某个仓库城市时，只显示当前城市标签。
3. 当前城市标签旁边展开数据面板，展示表格、柱状图、折线图、饼图或环形图。
4. 数据来源支持后端模拟、外部程序推送、CSV 表格上传。
5. 面板数量、结构、样式和校验字段由后端配置文件控制。

## 2. 后端结构

### 2.1 配置文件

配置入口：

`src/main/resources/warehouse.yml`

核心结构：

```yaml
warehouses:
  focus-panels:
    count: 3
    style:
      width: 260
      max-height: 420
      placement: right
      theme: cyan-dark
    panels:
      - id: inventory-table
        title: 实时库存明细
        chart-type: table
        height: 112
        required-columns: [metric, value, unit]
```

字段说明：

- `count`：聚焦时最多显示几个面板。
- `style`：面板整体样式配置，目前后端随消息下发，前端已预留使用。
- `panels`：面板结构列表。
- `id`：面板唯一标识，外部推送和上传校验都靠它匹配配置。
- `chart-type`：支持 `table`、`bar`、`line`、`pie`、`ring`。
- `required-columns`：外部数据必须包含的字段。
- `columns`：表格展示列定义。

### 2.2 配置绑定

配置类：

`src/main/java/com/jushen/digitaltwin/config/WarehouseProperties.java`

它把 `warehouse.yml` 绑定为：

- `WarehouseConfig`：仓库城市与名称。
- `FocusPanelsConfig`：聚焦面板总配置。
- `PanelConfig`：单个面板结构。
- `ColumnConfig`：表格列结构。

### 2.3 服务层

核心服务：

`src/main/java/com/jushen/digitaltwin/service/WarehousePushService.java`

职责：

- 生成仓库快照消息：`warehouse_update`
- 生成聚焦面板消息：`warehouse_focus`
- 按配置生成模拟面板数据
- 校验外部推送面板数据
- 校验 CSV 上传表格
- 通过 WebSocket 主动推送消息

核心消息格式：

```json
{
  "type": "warehouse_focus",
  "cityName": "佛山市",
  "style": {
    "width": 260,
    "maxHeight": 420,
    "placement": "right",
    "theme": "cyan-dark"
  },
  "panels": [
    {
      "id": "inventory-table",
      "title": "实时库存明细",
      "chartType": "table",
      "height": 112,
      "columns": [
        { "key": "metric", "label": "指标" },
        { "key": "value", "label": "数值" }
      ],
      "rows": [
        { "metric": "总库存", "value": 5280, "unit": "吨" }
      ],
      "option": {}
    }
  ]
}
```

### 2.4 REST 接口

控制器：

`src/main/java/com/jushen/digitaltwin/web/WarehouseController.java`

接口：

- `GET /api/warehouse/snapshot`
- `POST /api/warehouse/snapshot/push`
- `GET /api/warehouse/focus/{cityName}`
- `POST /api/warehouse/focus/{cityName}/push`
- `POST /api/warehouse/focus/{cityName}/panels`
- `POST /api/warehouse/focus/{cityName}/upload?panelId=inventory-table`

其中：

- `/focus/{cityName}`：获取模拟聚焦面板数据，不主动推送。
- `/focus/{cityName}/push`：生成模拟聚焦面板并通过 WebSocket 推送。
- `/focus/{cityName}/panels`：接收外部程序推送的数据，校验后通过 WebSocket 推送。
- `/focus/{cityName}/upload`：接收 CSV 文件，校验后通过 WebSocket 推送。

## 3. 前端结构

### 3.1 WebSocket 接入

文件：

`E:/jushen/data-showing-web/jishen-digital-twin/src/pages/Dashboard/hooks/useDashboardRealtime.ts`

新增类型：

```ts
export type WarehouseFocusPanel = {
  id: string;
  title: string;
  chartType: 'table' | 'bar' | 'line' | 'pie' | 'ring';
  height?: number;
  columns?: Array<{ key: string; label: string }>;
  rows?: Array<Record<string, any>>;
  option?: any;
};
```

新增回调：

```ts
onWarehouseFocus?: (cityName: string, panels: WarehouseFocusPanel[]) => void;
```

收到 `warehouse_focus` 后交给 Dashboard：

```ts
onWarehouseFocus(cityName, panels ?? []);
```

### 3.2 页面接线

文件：

`E:/jushen/data-showing-web/jishen-digital-twin/src/pages/Dashboard/DashboardPage.tsx`

新增逻辑：

- WebSocket 收到 `warehouse_focus` 时调用：

```ts
mapRef.current?.showCityPanels(cityName, panels);
```

- 进入数字孪生地图并请求仓库快照后，会预取每个仓库城市的聚焦面板：

```ts
GET /api/warehouse/focus/{cityName}
```

### 3.3 地图面板渲染

文件：

`E:/jushen/data-showing-web/jishen-digital-twin/src/pages/Dashboard/modules/ChinaMap3D.tsx`

新增暴露方法：

```ts
showCityPanels(cityName, panels)
clearCityPanels(cityName)
```

渲染规则：

- 面板挂载到当前城市的 CSS2D 标签容器中。
- `table` 使用 HTML table 渲染。
- `bar / line / pie / ring` 使用 ECharts 渲染。
- 俯瞰模式隐藏全部面板。
- 聚焦模式只显示当前聚焦城市面板。
- 地图卸载时释放 ECharts 实例。

## 4. 数据流

### 4.1 模拟数据流

```text
前端进入数字孪生地图
  -> POST /api/warehouse/snapshot/push
  -> 后端推送 warehouse_update
  -> 前端升起仓库城市
  -> 前端 GET /api/warehouse/focus/{cityName}
  -> 后端按配置生成 panels
  -> 前端缓存并渲染城市面板
  -> ChinaMap3D 巡航聚焦时显示当前城市面板
```

### 4.2 外部程序推送流

```text
外部程序 POST /api/warehouse/focus/{cityName}/panels
  -> 后端按 panelId 查配置
  -> 校验 rows 是否包含 required-columns
  -> 生成 warehouse_focus
  -> WebSocket 推给前端
  -> 当前/后续聚焦时显示新数据
```

### 4.3 表格上传流

```text
用户或外部程序上传 CSV
  -> POST /api/warehouse/focus/{cityName}/upload?panelId=...
  -> 后端读取 UTF-8 CSV
  -> 校验表头字段
  -> 生成对应 panel
  -> 推送 warehouse_focus
```

## 5. 当前边界

- 上传目前支持 UTF-8 CSV，暂未接 `.xlsx` 解析依赖。
- 后端 Maven 未在当前机器验证，因为命令行没有 `mvn`。
- 前端面板样式已可用，但后端下发的 `style` 目前只部分使用，后续可以继续增强。
