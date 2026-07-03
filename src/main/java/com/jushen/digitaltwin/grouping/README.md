# 路线分组策略隔离模块

本包用于实验和演进道路路线分组机制，当前不直接改动现有 `RoutePushService` 或 Controller。

## 目标

- 支持多种分组方式动态切换。
- 支持同订单、同起终点、同路径三类核心业务语义。
- 后端输出 `groupScenario` 和 `displayTemplate`，前端不需要重复推断分组含义。
- 保持现有接口未来可平滑接入，前端无需立刻改动。

## 核心对象

- `RouteInfo`：分组策略依赖的最小路线视图。
- `OrderAwareRouteInfo`：可选扩展，提供 `orderId`。
- `PathAwareRouteInfo`：可选扩展，提供 `pathKey` 和 `segmentKeys`。
- `GroupSummary`：分组摘要，包含 `groupType`、`groupScenario`、`displayTemplate`、`orderIds`、`routeKey`、`pathKey`。
- `RouteGroupScenario`：具体业务场景协议。
- `DisplayTemplate`：建议前端采用的展示模板。
- `AdvancedGroupingStrategy`：新策略接口。
- `RouteGroupingEngine`：策略执行入口。

## 字段分工

| 字段 | 作用 |
| --- | --- |
| `groupType` | 粗分类，例如同订单、同路径、同起终点 |
| `groupScenario` | 具体业务场景，前端主要根据它选择展示逻辑 |
| `scenarioReason` | 给后台、日志、调试使用的中文解释 |
| `displayTemplate` | 建议前端采用的展示模板 |
| `styleHint` | 兼容旧命名，实际内容也是展示语义，不是 CSS |

## 分组场景

| groupScenario | 含义 | 建议模板 |
| --- | --- | --- |
| `single_order_single_path` | 单订单单路径 | `route_flow` |
| `single_order_multi_path` | 单订单多路径 | `order_progress` |
| `multi_order_same_path` | 多订单同路径 | `path_pressure` |
| `multi_order_same_route` | 多订单同起终点 | `route_cluster` |
| `same_route_multi_path` | 同起终点多路径 | `route_cluster` |
| `time_batch` | 同一时间批次 | `time_batch` |
| `sequential` | 顺序切分 | `basic` |
| `mixed` | 混合兜底 | `basic` |

## 已有策略

| 策略名 | 类 | 说明 |
| --- | --- | --- |
| `business-priority` | `BusinessPriorityGroupingStrategy` | 推荐默认策略：先同订单，再同路径，最后同起终点 |
| `by-order` | `OrderGroupingStrategy` | 按订单聚合，超过 `groupSize` 自动拆子组 |
| `by-route` | `RouteKeyGroupingStrategy` | 按 `from→to` 聚合 |
| `by-path` | `PathGroupingStrategy` | 按 `pathKey` 或坐标路径签名聚合 |
| `by-time-window` | `TimeWindowGroupingStrategy` | 按开始时间窗口聚合 |
| `sequential` | `AdvancedSequentialGroupingStrategy` | 按开始时间顺序切分 |

## 示例结果结构

```json
{
  "strategy": "business-priority",
  "groupSize": 5,
  "totalRoutes": 18,
  "groups": [
    {
      "groupId": "order-1-1-O-1001",
      "groupKey": "订单 O-1001",
      "groupType": "same_order",
      "groupScenario": "single_order_multi_path",
      "scenarioReason": "单订单多路径",
      "displayTemplate": "order_progress",
      "styleHint": {
        "category": "order",
        "priority": 100,
        "variant": "single_order_multi_path"
      },
      "orderIds": ["O-1001"],
      "routeKey": null,
      "pathKey": null,
      "count": 5
    }
  ]
}
```

## 后续接入建议

1. 让当前活跃路线模型实现 `RouteInfo`，或编写 adapter。
2. 如果有订单号，实现 `OrderAwareRouteInfo`。
3. 如果有外部路径规划结果，实现 `PathAwareRouteInfo`。
4. 配置默认策略为 `business-priority`。
5. API 保留可选参数 `strategy`，允许临时切换策略观察效果。
