# 浦医早餐系统前端开发契约

本文面向第二幕业务前端及早餐后端联调，定义当前 Android 客户端实际使用的接口和页面事件契约。

本文**不描述路由签名检查**。必须实现的内容为：

- `POST /query_breakfast_right`
- `GET /change_breakfast_status`
- `POST /upload_unregistered_scan`
- 第一幕 `/provider` 路由

第二幕 `/invalid/exam/<体检编号>` 是**可选**的 Android 扫码事件入口。

---

## 1. 接口总览

| 方法 | 路径 | 必要性 | 用途 |
| --- | --- | --- | --- |
| `POST` | `/query_breakfast_right` | 必要 | 查询客户信息与早餐权益 |
| `GET` | `/change_breakfast_status` | 必要 | 登记或取消早餐状态 |
| `POST` | `/upload_unregistered_scan` | 必要 | 上传已扫码但尚未登记的老化查询记录 |
| `GET` | `/provider` | 必要 | 向 UIKit 第一幕提供今日、本月、今年统计 |
| 页面虚拟导航 | `/invalid/exam/<体检编号>` | **可选** | 第二幕页面向 Android 发送体检编号 |

业务接口和 Provider 路由应按路径严格区分。不要让同一路径根据请求体字段猜测操作类型。

### 1.1 通用响应信封

早餐业务接口建议统一返回：

```json
{
  "code": 200,
  "msg": "ok",
  "data": {}
}
```

客户端成功判据：

- HTTP 状态码为 `2xx`；
- 如果响应包含 `code`，则 `code` 必须为 `200`。

推荐约定：

| `code` | 含义 |
| --- | --- |
| `200` | 成功 |
| `400` | 参数或业务状态错误 |
| `404` | 订单不存在 |
| `409` | 状态冲突或重复操作 |
| `500` | 后端内部错误 |

失败时 `msg` 应提供可直接展示或记录的简明原因。

---

## 2. POST `/query_breakfast_right`

### 2.1 用途

扫码后查询客户资料、早餐权益和当前早餐登记状态。该接口也是 WaitGroup 对早餐操作进行权益探测时使用的查询接口。

### 2.2 请求

```http
POST /query_breakfast_right
Content-Type: application/json; charset=utf-8
```

```json
{
  "order_code": "T202607010001",
  "card_no": "123456"
}
```

| 字段 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `order_code` | 字符串 | 必填 | 订单号或导检单条码；不得为空 |
| `card_no` | 字符串或 `null` | 可选 | 卡号；未知时允许省略或传 `null` |

后端必须根据 URL 路径选择业务，不应依赖无关字段判断这是查询、登记还是未登记上传。

### 2.3 成功响应

```json
{
  "code": 200,
  "msg": "ok",
  "data": {
    "order_code": "T202607010001",
    "card_no": "123456",
    "name_b64": "5byg5LiJ",
    "sex_b64": "55S3",
    "phone": "138****8888",
    "avatar_url": "/avatars/T202607010001.jpg",
    "birth_date": "1990-01-01",
    "package_name_b64": "5L2T5qOA5aWX6aSQ",
    "exam_type_b64": "5YWl6IGM5L2T5qOA",
    "organization_name_b64": "5p+Q5p+Q5YWs5Y+4",
    "exam_total_price_b64": "NjgwLjAw",
    "has_breakfast": true,
    "allow_breakfast": true,
    "breakfast_status": 0,
    "reason_b64": ""
  }
}
```

当前客户端可从顶层或 `data` 对象读取业务字段；推荐始终使用上述 `data` 信封。

### 2.4 字段契约

| 字段 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `order_code` | 字符串 | 必填 | 必须与查询订单一致 |
| `card_no` | 字符串或 `null` | 可选 | 卡号 |
| `name_b64` | Base64 字符串 | 建议提供 | UTF-8 姓名 |
| `sex_b64` | Base64 字符串 | 可选 | UTF-8 性别 |
| `phone` | 字符串 | 可选 | 建议脱敏 |
| `avatar_url` | 字符串 | 可选 | 唯一头像来源；支持绝对 HTTP(S) URL 或相对路径 |
| `birth_date` | 字符串 | 可选 | 建议格式 `yyyy-MM-dd` |
| `package_name_b64` | Base64 字符串 | 可选 | UTF-8 套餐名称 |
| `exam_type_b64` | Base64 字符串 | 可选 | UTF-8 体检类型 |
| `organization_name_b64` | Base64 字符串 | 可选 | UTF-8 单位名称 |
| `exam_total_price_b64` | Base64 字符串 | 可选 | UTF-8 体检总价；解码后须为非负十进制字符串且最多两位小数 |
| `has_breakfast` | 布尔值 | 可选 | 冗余展示字段，不参与业务判断 |
| `allow_breakfast` | 布尔值 | **必填** | 是否具有当前早餐操作权益 |
| `breakfast_status` | 整数 | **必填** | `0` 未登记，`1` 已登记 |
| `reason_b64` | Base64 字符串 | 条件必填 | `allow_breakfast=false` 时提供 UTF-8 原因 |

Base64 字段必须是 UTF-8 文本的标准 Base64。单个可选字段缺失或 Base64 无效时，不应影响其他字段。

客户端不再读取 `avatar_b64`，头像只能使用 `avatar_url`。URL 缺失、无效、不可达或内容不是图片时显示占位头像。

### 2.5 前端业务判断

只使用 `allow_breakfast` 和 `breakfast_status`：

```text
allow_breakfast = true 且 breakfast_status = 0
    可执行完成早餐

allow_breakfast = false
    禁止完成早餐
    展示 reason_b64 解码结果

breakfast_status = 1
    显示已完成早餐
```

`has_breakfast` 不得用于业务分支。为什么不允许早餐由后端决定，前端只展示 `reason_b64`。

---

## 3. GET `/change_breakfast_status`

### 3.1 用途

登记或取消早餐状态。客户端通过查询参数提交，不使用 JSON 请求体。

### 3.2 登记请求

```http
GET /change_breakfast_status?order_code=T202607010001&breakfast_status=1&breakfast_remark=PDA%E6%89%AB%E7%A0%81%E6%A0%B8%E9%94%80%E6%97%A9%E9%A4%90
```

### 3.3 取消请求

```http
GET /change_breakfast_status?order_code=T202607010001&breakfast_status=0&breakfast_remark=PDA%E5%8F%96%E6%B6%88%E6%97%A9%E9%A4%90%E6%A0%B8%E9%94%80
```

| 查询参数 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `order_code` | 字符串 | 必填 | 订单号；不得为空 |
| `breakfast_status` | 字符串整数 | 必填 | `1` 登记，`0` 取消登记 |
| `breakfast_remark` | 字符串 | 建议提供 | 操作备注，必须正确 URL 编码 |

### 3.4 响应

成功：

```json
{
  "code": 200,
  "msg": "ok"
}
```

失败：

```json
{
  "code": 400,
  "msg": "订单不存在或当前状态不允许操作"
}
```

客户端最多执行 5 次 GET 尝试。HTTP 非成功或 `code != 200` 均视为失败。

接口应尽量具备幂等性：重复提交目标状态时返回成功并保持该状态，避免网络重试产生不必要的业务错误。

---

## 4. POST `/upload_unregistered_scan`

### 4.1 用途与任务筛选

上传已完成权益查询、但仍在等待操作员登记或取消的老化 `QueryBreakfastRight` 节点。周期性老化检查或保存后端配置时触发，每批最多 10 条。

上传筛选必须同时满足：

- 任务类型为 `QueryBreakfastRight`；
- `awaitingAction == true`；
- 已达到老化阈值，或由保存配置操作强制触发。

`Enroll` 和 `CancelEnrollment` 都必须绕过该接口。尤其是取消登记节点只调用 `GET /change_breakfast_status`，不得被打包为“已扫码未登记”。

### 4.2 请求

```http
POST /upload_unregistered_scan
Content-Type: application/json; charset=utf-8
```

```json
[
  {
    "order_code": "T202607010001",
    "created_at": 1785290000000
  },
  {
    "order_code": "T202607010002",
    "created_at": 1785290001000
  }
]
```

请求体始终是顶层 JSON 数组，长度为 `1..10`。即使只有一条记录也发送单元素数组。

| 字段 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `order_code` | 字符串 | 必填 | 老化查询节点对应的订单号 |
| `created_at` | 64 位整数 | 必填 | 首次进入 WaitGroup 的 Unix Epoch 毫秒时间戳 |

### 4.3 响应和出队

```json
{
  "code": 200,
  "msg": "received"
}
```

只有 HTTP 状态严格为 `200`，且响应中的可选业务 `code` 不存在或等于 `200` 时，客户端才把对应查询节点标记为 `WaitApprovalState.Aged(10)` 并从环形链表摘除。上传失败时整批保留，等待后续老化轮再次提交。

---

## 5. 必要：第一幕 `/provider` 路由契约

### 5.1 用途与请求行为

UIKit 第一幕通过 Provider 获取今日、本月、今年的早餐统计。Provider 是**必要接口**。

```http
GET /provider
Accept: application/json
```

实际部署时 `/provider` 可以位于配置的第一幕主路由下。前端和后端只需保证最终 Provider URL 返回本节规定的数据。

客户端行为：

- 首屏加载后在后台请求；
- 每 10 秒重新请求；
- 按 `period` 匹配，不依赖数组顺序；
- 三个周期作为一个原子数据集接收；
- HTTP 非成功、读取失败或任意周期无效时，三个周期全部进入 `N/A`；
- 连续 10 轮无有效数据时提示一次，后续继续轮询；
- 任意一轮成功会重置连续失败计数。

### 5.2 响应

Provider 必须返回 JSON 数组，并各包含一条 `day`、`month`、`year`：

```json
[
  {
    "period": "day",
    "label": "今日",
    "completed": { "count": 151, "percentage": 74.0 },
    "pending": { "count": 53, "percentage": 26.0 },
    "total": 204
  },
  {
    "period": "month",
    "label": "本月",
    "completed": { "count": 1840, "percentage": 83.6 },
    "pending": { "count": 360, "percentage": 16.4 },
    "total": 2200
  },
  {
    "period": "year",
    "label": "今年",
    "completed": { "count": 18600, "percentage": 88.6 },
    "pending": { "count": 2400, "percentage": 11.4 },
    "total": 21000
  }
]
```

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `period` | 字符串 | 分别为 `day`、`month`、`year`，不可缺失或重复 |
| `label` | 字符串 | 周期显示名 |
| `completed.count` | 非负数值 | 已完成数量 |
| `completed.percentage` | 非负数值 | 已完成百分比 |
| `pending.count` | 非负数值 | 剩余数量 |
| `pending.percentage` | 非负数值 | 剩余百分比 |
| `total` | 非负数值 | 总数 |

每条记录必须满足：

$$
completed.count + pending.count = total
$$

在合理舍入误差内还应满足：

$$
completed.percentage + pending.percentage = 100
$$

以及：

$$
completed.percentage \approx \frac{completed.count}{total} \times 100
$$

$$
pending.percentage \approx \frac{pending.count}{total} \times 100
$$

任意记录缺失、重复、字段缺失、负数或数量与百分比不一致时，整组数据无效。

### 5.3 与“已扫码未登记”的关系

Provider 只返回 `completed`、`pending` 和 `total`，不得返回或覆盖本地 `scan2fail`。

Android 客户端把 WaitGroup 长度作为独立的“已扫码未登记”数量下发给 UIKit。有效 Provider 数据存在时，显示值重新计算：

```text
displayTotal     = provider.total + scan2fail
completedPercent = provider.completed.count / displayTotal * 100
pendingPercent   = provider.pending.count / displayTotal * 100
scan2failPercent = scan2fail / displayTotal * 100
```

Provider 刷新和周期切换不得清空 `scan2fail`。

---

## 6. 可选：第二幕无效路由事件

> **本节为可选功能。** 不需要由第二幕页面触发 Android 扫码/详情流程时，可以完全不实现。

第二幕页面可通过一次同源虚拟导航，把体检编号发送给 Android：

```text
/invalid/exam/<体检编号>
```

示例：

```text
https://example.com/invalid/exam/TJ-DEMO-002
```

### 6.1 前端触发

```javascript
function sendExamNoToAndroid(examNo) {
  const normalized = String(examNo ?? "").trim();
  if (!normalized) return;

  const target = new URL(
    `/invalid/exam/${encodeURIComponent(normalized)}`,
    window.location.origin
  );
  window.location.assign(target.href);
}
```

应对点击操作做防抖，避免重复触发同一编号。

### 6.2 严格匹配条件

只有同时满足以下条件才发布事件：

1. 目标 URL 与当前 `secondRoute` 同源；
2. 路径严格为 `invalid`、`exam`、`<体检编号>` 三个非空段；
3. 前两段精确匹配；
4. 最后一段非空；
5. 最后一段经 URL 解码后作为体检编号发送给 Android。

匹配成功时，导航会被拦截，GeckoView 不会真正加载该虚拟页面。

### 6.3 不会触发的情况

- 普通 HTTP 404；
- 跨源 `/invalid/exam/...`；
- `/invalid`；
- `/invalid/exam` 或 `/invalid/exam/`；
- `/foo/invalid/exam/123`；
- `/invalid/foo/123`；
- 只在查询参数或 fragment 中出现 `invalid`、`exam`；
- 路径结构不完全匹配。

该机制依据导航前的 URL 同源性和路径结构触发，与服务器是否真的存在这个页面、是否返回 404 无关。

---

## 7. 联调检查表

### 必要功能

- [ ] `POST /query_breakfast_right` 能按 `order_code` 返回完整业务信封
- [ ] `allow_breakfast` 和 `breakfast_status` 类型稳定
- [ ] Base64 字段使用 UTF-8 标准 Base64
- [ ] `exam_total_price_b64` 返回 UTF-8 价格字符串的标准 Base64
- [ ] 头像只使用 `avatar_url`
- [ ] `GET /change_breakfast_status` 支持登记和取消，并尽量幂等
- [ ] `/upload_unregistered_scan` 接收 `1..10` 条顶层 JSON 数组
- [ ] `CancelEnrollment` 不进入未登记上传批次
- [ ] 未登记上传以 HTTP 200 明确表示接收成功
- [ ] `/provider` 一次返回完整且一致的 `day/month/year`
- [ ] Provider 支持每 10 秒请求一次

### 可选功能

- [ ] 第二幕通过同源 `/invalid/exam/<编码后的体检编号>` 向 Android 发送编号
- [ ] 对虚拟导航入口进行防抖
