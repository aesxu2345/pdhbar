# pdhbar

## WaitGroup 缓冲

- 客户权益查询从扫码入口直接进入持久化 WaitGroup，审核弹窗先显示 `N/A 查询中`，再由队列结果更新。
- WaitGroup 使用单向环形链表；`current` 只是遍历游标，不表示栈顶或队头。
- 普通扫码允许同一订单重复进入查询流程，不在业务扫码入口按订单号去重。
- 去重仅发生在 WaitGroup 内：同订单多个登记任务只保留一个；只要存在取消登记任务，就只保留一个取消登记节点并摘除该订单的其他节点。
- 节点保存权益审批状态、任务类型和创建时间：`0` 为无权益并拦截所有后续 GET，`1` 为有权益并直接执行 GET，`2` 为未定态并由 WaitGroup 先 POST 查询、转成 `0/1` 后再决定是否执行 GET。
- 查询弹窗仍处于 `N/A` 时点击“完成早餐”，登记任务以未定态 `2` 入队；已查询到无权益时“完成早餐”变灰，但“取消登记”仍可入队，并由状态 `0` 在队列执行层拦截。
- UIKit 创建后会在 `Display` 前调用 `fix2fail(len)`，并持续订阅长度变化，将环形链表当前节点数作为“已扫码未登记”修正值。
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

```json
{
  "order_code": "T202607010001",
  "card_no": null
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
    "avatar_b64": "<base64 of a 358x441 JPEG>",
    "avatar_url": "",
    "birth_date": "1990-01-01",
    "package_name_b64": "5L2T5qOA5aWX6aSQ",
    "has_breakfast": true,
    "allow_breakfast": true,
    "breakfast_status": 0,
    "reason_b64": ""
  }
}
```

### Photo contract
- `avatar_b64` and `avatar_url` are both optional; neither field is required for a successful customer response.
- `avatar_b64` is temporarily bypassed: the current client neither parses nor displays it.
- The approval dialog currently loads photos only from `avatar_url`.
- Absolute HTTP(S) URLs are used directly. Relative URLs are resolved against the backend scheme, host, and port used for the customer query.
- A missing, invalid, unreachable, or non-image URL leaves the fixed placeholder visible.
- Missing or failed photo data does not prevent the remaining customer fields from being parsed and displayed independently.

### UI behavior
- The approval dialog and all customer fields appear immediately with a fixed-size photo placeholder; `avatar_url` loads independently and may finish later.
- A new scan replaces the customer model and cancels the previous URL image request so stale photo results do not appear for the next customer.
- The client uses `allow_breakfast` and `breakfast_status` as the business gating inputs; `has_breakfast` is treated as informational only.
- After either breakfast action is queued, the approval dialog closes immediately and a fixed three-second visual confirmation is shown while network execution continues in the background.
- Scans may repeat normally; equivalent pending work is compacted only inside WaitGroup.
