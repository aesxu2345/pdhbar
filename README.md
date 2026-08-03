# pdhbar

## WaitGroup 缓冲

- 客户权益查询由扫码入口在 WaitGroup 外直接执行；审核弹窗先显示 `N/A 查询中`，再由查询结果更新。
- WaitGroup 的业务入队动作固定为两个：`ApproveBreakfast -> Enroll` 和 `CancelEnrollment -> CancelEnrollment`。按钮文字可以变化，但扫码、查询和“关闭”均不入队。
- WaitGroup 使用单向环形链表；`current` 只是遍历游标，不表示栈顶或队头。
- 普通扫码允许同一订单重复进入查询流程，不在业务扫码入口按订单号去重。
- 去重仅发生在 WaitGroup 内：同订单多个登记任务只保留一个；只要存在取消登记任务，就只保留一个取消登记节点并摘除该订单的其他节点。
- 节点保存权益审批状态、任务类型和创建时间：`0` 为无权益并拦截所有后续 GET，`1` 为有权益并直接执行 GET，`2` 为未定态并由 WaitGroup 先 POST 查询、转成 `0/1` 后再决定是否执行 GET。
- 查询弹窗仍处于 `N/A` 时点击“完成早餐”，登记任务以未定态 `2` 入队；已查询到无权益时“完成早餐”变灰，但“取消登记”仍可入队，并由状态 `0` 在队列执行层拦截。
- UIKit 使用 `app/libs/UIKitInsight-release.aar` 提供的当前公开接口完成显示、侧栏事件和卡号监听。

## 环形链表老化轮询与 POST 契约

该实现是对持久化单向环形链表进行周期性老化扫描，不是另建一份 UI 计数。老化轮每 `30` 秒唤醒一次，普通老化阈值为节点创建后 `10` 分钟；保存后端配置时可强制立即扫描。每轮最多选择 `10` 条记录。

只有同时满足以下条件的节点才允许作为“已扫码未登记”记录上传：

- 任务类型是 `QueryBreakfastRight`；
- `awaitingAction == true`，即权益查询已完成但仍在等待操作员处理；
- 已达到 `10` 分钟老化阈值，或本轮由保存后端配置强制触发。

`Enroll` 和 `CancelEnrollment` 必须绕过此接口。特别是取消登记只能执行早餐状态变更，不得被包装成“已扫码未登记”。当前业务入口规则下，扫码、查询和点击“关闭”不会创建 WaitGroup 节点，因此也不会凭空产生老化上传记录。

### POST `/upload_unregistered_scan`

请求头：

```http
POST /upload_unregistered_scan
Content-Type: application/json; charset=utf-8
```

请求体必须是长度为 `1..10` 的顶层 JSON 数组；即使只有一条记录也不能省略 `[]`：

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

| 字段 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `order_code` | 字符串 | 必填 | 老化节点对应的订单号 |
| `created_at` | 64 位整数 | 必填 | 节点首次创建时的 Unix Epoch 毫秒时间戳 |

成功响应示例：

```json
{
  "code": 200,
  "msg": "received"
}
```

客户端只有在 HTTP 状态严格为 `200`，并且可选业务字段 `code` 不存在或等于 `200` 时，才把整批记录视为成功。成功后 actor 将对应节点标记为 `WaitApprovalState.Aged(4)`，再从真实环形链表摘除并更新持久化 `len`；不得只修改 UI 计数。任一失败均保留整批节点，等待后续老化轮重试，不进行部分出队。

该 POST 使用最多 `30` 次请求尝试（首次加 `29` 次重试），重试间隔为 `100ms`，连接超时为 `3s`，读取超时为 `8s`。HTTP `4xx` 或明确的业务 `code != 200` 属于确定性失败，会立即停止本轮重试。

a MIT based tample for sunmi PDAs

## Licensing

The pdhbar source code is licensed under the root [MIT License](LICENSE).

The bundled UIKitInsight AAR is a separately licensed component from
[aesxu2345/insight-board-kit](https://github.com/aesxu2345/insight-board-kit).
It is not covered by pdhbar's MIT License. See:

- [UIKitInsight license notice](uikit/LICENSE)
- [UIKitInsight commercial integration license](uikit/COMMERCIAL-INTEGRATION-LICENSE.md)
- [UIKitInsight NOTICE](uikit/NOTICE)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## POST contract for breakfast inquiry

The client currently calls POST /query_breakfast_right with a JSON body shaped like this:

```jsonc
{
  "order_code": "T202607010001", // 必填：订单号或导检单条码
  "card_no": null // 可选：卡号
}
```

### Request notes
- `order_code` is required and is populated from the scanned barcode or card number.
- `card_no` is optional and may be omitted when no card number is available.
- The runtime uses an HTTP/1.1 raw socket request with `Content-Type: application/json; charset=utf-8`.

### Success conditions
The request is treated as successful when:
- the HTTP status code is in the `200-299` range, and
- the response does not contain a business `code` field, or `code` is `200`.

### Response fields
The response is expected to contain a payload similar to:

```jsonc
{
  "code": 200, // 业务状态码
  "msg": "ok", // 业务消息
  "data": {
    "order_code": "T202607010001", // 必填：订单号
    "card_no": "123456", // 可选：卡号
    "name_b64": "5byg5LiJ", // 姓名，UTF-8 Base64
    "sex_b64": "55S3", // 性别，UTF-8 Base64
    "phone": "138****8888", // 手机号
    "avatar_url": "", // 可选：头像 URL
    "birth_date": "1990-01-01", // 出生日期
    "package_name_b64": "5L2T5qOA5aWX6aSQ", // 套餐名称，UTF-8 Base64
    "exam_type_b64": "5YWl6IGM5L2T5qOA", // 可选：体检类型，UTF-8 Base64
    "organization_name_b64": "5p+Q5p+Q5YWs5Y+4", // 可选：单位名称，UTF-8 Base64
    "exam_total_price_b64": "NjgwLjAw", // 可选：UTF-8 体检总价字符串 "680.00" 的 Base64
    "has_breakfast": true, // 展示字段
    "allow_breakfast": true, // 早餐权益判定字段
    "breakfast_status": 0, // 早餐登记状态
    "reason_b64": "" // 不允许早餐的理由，UTF-8 Base64
  }
}
```

### Optional examination fields
- `exam_type_b64` optionally contains the UTF-8 examination type encoded as Base64.
- `organization_name_b64` optionally contains the UTF-8 organization name encoded as Base64.
- `exam_total_price_b64` optionally contains a non-negative decimal string with at most two decimal places encoded as UTF-8 Base64. After decoding, it remains a string and is never parsed as a floating-point number.
- Missing, blank, undecodable, or malformed optional examination fields do not reject the remaining customer response.
- After the query completes, each missing examination field is shown in green at its own dialog position as `记录缺失，请人工核对`.

### Photo contract
- The approval dialog loads photos only from the optional `avatar_url` field.
- Absolute HTTP(S) URLs are used directly. Relative URLs are resolved against the backend scheme, host, and port used for the customer query.
- A missing, invalid, unreachable, or non-image URL leaves the fixed placeholder visible.
- Missing or failed photo data does not prevent the remaining customer fields from being parsed and displayed independently.

### UI behavior
- The approval dialog and all customer fields appear immediately with a fixed-size photo placeholder; `avatar_url` loads independently and may finish later.
- A new scan replaces the customer model and cancels the previous URL image request so stale photo results do not appear for the next customer.
- The client uses `allow_breakfast` and `breakfast_status` as the business gating inputs; `has_breakfast` is treated as informational only.
- After either breakfast action is queued, the approval dialog closes immediately and a fixed three-second visual confirmation is shown while network execution continues in the background.
- Background enrollment results are also posted to the Android notification shade. Successful notifications include the order code and customer name; blocked notifications include the decoded `reason_b64` when available.
- Approval notifications remain available for later review and can be dismissed like ordinary system notifications. Android 13 and newer require the operator to grant notification permission.
- Scans may repeat normally; equivalent pending work is compacted only inside WaitGroup.
