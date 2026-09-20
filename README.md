# pdhbar

## WaitGroup 缓冲

- 客户权益查询由扫码入口在 WaitGroup 外直接执行；审核弹窗先显示 `N/A 查询中`，再由查询结果更新。
- WaitGroup 的业务入队动作固定为两个：`ApproveBreakfast -> Enroll` 和 `CancelEnrollment -> CancelEnrollment`。按钮文字可以变化，但扫码、查询和“关闭”均不入队。
- WaitGroup 使用单向环形链表；`current` 只是遍历游标，不表示栈顶或队头。
- 普通扫码允许同一订单重复进入查询流程，不在业务扫码入口按订单号去重。
- 去重仅发生在 WaitGroup 内：同订单多个登记任务只保留一个；只要存在取消登记任务，就只保留一个取消登记节点并摘除该订单的其他节点。
- 节点保存权益审批状态、任务类型和创建时间：`0` 为无权益并拦截所有后续 GET，`1` 为有权益并直接执行 GET，`2` 为未定态并由 WaitGroup 先 POST 查询、转成 `0/1` 后再决定是否执行 GET，`3` 为保留且完全惰性的忽略状态，`10` 为已老化状态。
- 状态 `3` 的节点仍保留在环和持久化快照中，但现有状态提交、网络执行、重试、普通超时、未登记老化上传、任务压缩和所有出队操作均直接忽略它；恢复后同样保持惰性。
- 独立的 `HourlyOrderTicker` 在进程存活期间启动后立即执行一次，随后每小时使用当前请求时间向前回溯八小时的 UTC 秒区间 `[start_utc, end_utc)` POST `/query_order_codes_by_time_range`。请求窗口固定维持八小时；响应返回后才弱匹配导检单号中的 `YYMMDD + 至少四位流水号`（例如 `2608130090` 表示 2026-08-13），允许测试前缀及健康证 `-1` 等后缀，无法匹配的记录按非当天处理。启动请求和 WaitGroup 快照恢复均投递到独立 `Dispatchers.IO` 协程失败域，Activity 主线程不等待云缓存请求或持久化环恢复。当天的 `data[].order_code` 由 WaitGroup actor 批量写入 `QueryBreakfastRight + Ignored(3)`，整批只持久化一次；同一导检单已有状态 `3` 节点时视为正常重复并忽略。
- ticker 扫描状态 `3` 缓存池时，以节点本地 `created_at` 判断新鲜度；最新有效节点与当前系统时间相差严格小于一小时时计入近期节点。首次扫描近期节点数就是 `0`，或节点数从非零变为 `0` 时，均不等待原每小时周期，立即 POST `/query_order_codes_by_time_range` 补拉最近八小时记录；持续为空时合并重复信号，避免每 `100ms` 产生 POST 风暴。恰好一小时、超过一小时和未来时间戳不计入近期节点。
- Logcat 的 `HourlyOrderTicker`/运行时日志会输出状态 `3` 缓存池总数、近期节点数、节点单号与创建/缓存时间、范围 POST 区间、HTTP 状态、原始响应体、插入/重复统计和权益缓存回填结果，便于现场核查 ticker 行为。
- ticker 会通过 Toast 告知操作员本轮决策：应用启动、近期状态 `3` 为空或每小时定时检查触发刷新时显示刷新原因；请求完成后显示服务端获取数、新增数和重复数；失败时显示异常原因。扫描确认最近一小时仍有有效状态 `3` 节点时显示“不刷新”及有效记录数。相同的持续空池状态不会每 `100ms` 重复 Toast。
- ticker 使用单独命名并单独构造的 `tickerNetTarget` 和 `tickerPostman`，不复用主业务 `api` 对象；保存后端配置时两个 Postman 只同步 scheme、host 和 port。
- 独立缓存回填循环观察状态 `3` 且 `cache_node == null` 的节点，逐个调用 `/query_breakfast_right`。查询成功后按节点 ID 和导检单号将完整缓存悲观覆盖写入，不比较写入瞬间的旧值；失败记录继续留待下一轮。ticker 不直接加锁，环读写仍由 WaitGroup actor 串行化，插入与回填分别由独立 `try/catch` 隔离。
- `cache_node.cached_at` 记录该份权益缓存成功取得时的 Unix Epoch 毫秒。状态 `3` 缓存距离当前时间严格超过 7 天时，由缓存维护或扫码读取触发 WaitGroup actor 自动摘除并持久化；恰好 7 天、未来时间或无缓存的状态 `3` 节点不会按此规则摘除。
- 扫码后仍立即显示 `N/A 查询中` 占位，但正式审批内容优先按导检单号读取状态 `3` 节点：其 `cache_node` 非空且单号一致时直接展示缓存，不再发起本次 POST；节点不存在、缓存为空、单号不一致或读取失败时 fallback 到原 `/query_breakfast_right` 查询。
- 审批弹窗命中状态 `3` 的 `cache_node` 时显示绿色小字“预缓存记录”；普通实时 POST 查询和查询中占位不显示该标识。
- 扫描命中的状态 `3` 节点 ID 会保留到操作员点击两个业务按钮之一。按钮仍通过同一个 WaitGroup actor 回合执行原有普通任务压缩和入队，但状态 `3` 转状态 `1` 时的来源节点摘除逻辑当前为空实现，因此来源状态 `3` 节点继续保留。通用出队、老化和状态提交仍无权摘除状态 `3`。
- “独立进程狙击”实现为独立 IO 协程失败域发起、WaitGroup actor 原子执行，而不是 Android OS 多进程；这样打开 App 时不等待云缓存订单号请求，也不会产生多个内存环或并发覆盖同一 SharedPreferences 快照，并且不增加第三个业务入口。
- 状态 `3` 缓存回填采用事件驱动：范围请求确实写入新节点后才唤醒，不再每 `100ms` 扫描环；每小时仅低频兜底。每个缓存节点使用持久化、单调递增的 `cacheIndex`，额外任务队列只保存该索引。默认调度器依据 Android `memoryClass` 和低内存设备标记选择 `64–512` 的有界容量，以 `TreeMap` 红黑树、一把短锁和 `1–2` 个后台 Worker 执行；HTTP、延迟及持久化均不持锁。`CacheRefillEngineFactory` 仍保留 `LegacyPolling` 扫描实现用于维护诊断，默认使用 `IndexedBounded`。
- `cache_node` 正文不再嵌入整个 WaitGroup 环快照，而是按 `order_code` 保存到 WaitGroup 私有的持久键值索引 `breakfast_wait_group_cache_index`。环只维护节点结构，缓存索引仅在 WaitGroup 内部读写，不向 `std` 开放；旧 schema 7/8 快照会自动迁移到 schema 9。
- 查询弹窗仍处于 `N/A` 时点击“完成早餐”，登记任务以未定态 `2` 入队；已查询到无权益时“完成早餐”变灰，但“取消登记”仍可入队，并由状态 `0` 在队列执行层拦截。
- 环形节点包含可空的 `cache_node` 对象，缓存权益查询响应中的原始客户字段。已完成查询后点击两个审核按钮时，缓存随唯一业务入口写入节点；查询尚未完成时保持为空，并在 WaitGroup 后续权益探测成功后补齐。
- `cache_node` 保存 `order_code`、`cached_at`、`card_no`、`name_b64`、`sex_b64`、`phone`、`avatar_url`、`birth_date`、`package_name_b64`、`exam_type_b64`、`organization_name_b64`、`exam_total_price_b64`、`has_breakfast`、`allow_breakfast`、`breakfast_status` 和 `reason_b64`。Base64 字段保留服务端原值，避免持久化过程损失信息。
- WaitGroup 持久化快照 schema 为 `8`；schema `1..6` 的旧节点仍可恢复且 `cache_node` 默认为空。schema `7` 的已有缓存迁移时以迁移时间作为 `cached_at`，立即写回 schema `8`，避免后续重启反复刷新七天宽限期。
- UIKit 使用 `app/libs/UIKitInsight-release.aar` 提供的当前公开接口完成显示、侧栏事件和卡号监听。

## POST `/query_order_codes_by_time_range`

该接口供独立的 `HourlyOrderTicker` 查询指定 UTC 时间区间内的导检单号。应用进程启动后立即调用一次，此后每小时调用一次。接口使用与主业务 Postman 分离的 `tickerPostman` 和 `tickerNetTarget`。

请求头：

```http
POST /query_order_codes_by_time_range
Content-Type: application/json; charset=utf-8
```

请求体：

```jsonc
{
  "start_utc": 1786525200, // 必填：区间起点，Unix Epoch 秒
  "end_utc": 1786554000 // 必填：区间终点，Unix Epoch 秒
}
```

| 字段 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `start_utc` | 64 位整数 | 必填 | UTC 区间起点，单位为秒，必须大于或等于 `0` |
| `end_utc` | 64 位整数 | 必填 | UTC 区间终点，单位为秒，必须严格大于 `start_utc` |

时间区间采用半开区间 `[start_utc, end_utc)`。当前 ticker 每次把 `end_utc` 设置为请求发起时的当前 UTC Epoch 秒，并把 `start_utc` 设置为 `end_utc - 28800`，即向前回溯八小时。ticker 仍每小时调度一次，状态 `3` 节点的近期判定仍严格小于一小时。

成功响应：

```jsonc
{
  "code": 200,
  "status": true,
  "data": [
    { "order_code": "2608130090" },
    { "order_code": "2608130091" }
  ],
  "msg": ""
}
```

| 字段 | 类型 | 必要性 | 说明 |
| --- | --- | --- | --- |
| `code` | 整数 | 必填 | 业务状态码；成功必须严格等于 `200` |
| `status` | 布尔值 | 必填 | 业务成功标志；成功必须严格等于 `true` |
| `data` | 对象数组 | 必填 | 区间内记录；允许空数组，每项必须包含字符串字段 `order_code` |
| `msg` | 字符串 | 可选 | 业务提示；失败时可作为日志信息 |

客户端只有在 HTTP 状态位于 `200..299`、顶层 `code == 200`、`status == true` 且 `data` 是 JSON 数组时才接受响应。每个数组元素必须是对象且 `order_code` 必须是字符串；单号会去除首尾空白，空字符串会被忽略，同一响应中的重复单号会去重。合法单号随后通过 WaitGroup actor 以 `QueryBreakfastRight + Ignored(3)` 插入环；环内已有同单号状态 `3` 节点时不会重复插入。

HTTP 失败、业务 `code != 200`、`status != true`、缺少 `data`、记录缺少字符串 `order_code` 或 JSON 格式错误均视为本轮失败。本轮不会插入任何解析失败的记录，也不会影响已有环节点；ticker 等到下一小时再重新查询。该 API 本身不是普通业务入队入口。

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
    "order_code": "2608130090",
    "created_at": 1785290000000
  },
  {
    "order_code": "2608130091",
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

客户端只有在 HTTP 状态严格为 `200`，并且可选业务字段 `code` 不存在或等于 `200` 时，才把整批记录视为成功。成功后 actor 将对应节点标记为 `WaitApprovalState.Aged(10)`，再从真实环形链表摘除并更新持久化 `len`；不得只修改 UI 计数。任一失败均保留整批节点，等待后续老化轮重试，不进行部分出队。

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
  "order_code": "2608130090", // 必填：订单号或导检单条码
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
    "order_code": "2608130090", // 必填：订单号
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

## 2026-09-16 更新：极低性能设备优化与稳定性修复

本节记录已落盘的修改与验证边界，不表示所有死机、ANR 或闪退均已消除。前文部分内容为历史实现说明；涉及当前并发配置、持久化版本、UIKit 路径和日志策略时，以本节及实际源码为准。

### 相机与扫码资源

- `BreakfastApp.kt`：仅在相机扫码页需要识别且 Activity 处于 `RESUMED` 时启用相机。UIKit 显示、客户弹窗、手动输入、审批提交/确认期间及退后台时停止不必要的相机工作；暂停时释放自有 CameraX 用例、分析器及执行器。
- 硬件扫码枪监听与相机启停分离，保留 UIKit 页面上的扫码枪输入；拒绝后台和已失效实例的扫码回调。
- 相机权限申请按进入扫码页触发，避免拒绝权限后因权限窗口引起的暂停/恢复反复申请。
- 扫描线动画仅在相机页面活跃时运行，动画值在 Canvas 绘制阶段读取，避免逐帧重组整块扫码界面。
- `FrameThrottle.kt` / `MLKitBarcodeAnalyzer.kt`：图像转换和 ML Kit 提交前按单调时钟节流，最小间隔为 `150ms`；跳过的帧及时关闭，保留已销毁分析器的迟到结果隔离。
- `ScannerCommandDispatcher.kt`：扫码枪配置、扫描和延迟停止移到专用串行后台线程；最多接纳一个未完成操作，同一服务连接不重复配置。厂商 Binder 卡住时不阻塞界面线程，也不无限堆积任务或创建替代线程。

### WaitGroup 启动、保存与缓存维护

- `WaitGroupBuffer` 的 `persist()`、`ageUnregistered()` 及需要解析/替换 delegate 的 suspend 操作在 IO 执行，避免生命周期回调把队列恢复或声明锁等待带回主线程。
- 保存请求使用合并通道和单个 IO 消费协程；恢复尚未完成时，重复生命周期保存不会按调用次数创建协程。
- 状态 `3` 过期清理改为单遍摘链，保留环中存活节点的顺序；缓存索引删除使用同一 Editor 批量提交，保留存活订单及磁盘代次检查。
- 扫码缓存读取继续使用已发布的内存索引，不等待 actor、恢复锁或磁盘维护；未就绪或未命中时回退到实时查询。
- 当前持久化快照为 **schema 10**，包含单调递增的 `cacheIndex` / `next_cache_index`；状态 `3` 缓存及无缓存节点采用一天 TTL，不再使用前文旧版七天规则。
- 默认 `indexedBoundedMinWorkers=4`；显式配置范围仍为 `1..32`，未配置时按设备评估。没有通过减少既定 worker 数或清空缓存来掩盖性能问题。

### 网络请求限额、取消与隔离

- `BoundedHttpResponse.kt`：GET 成功流、错误流和原始 POST 正文均按实际累计字节限制为 **4 MiB**，不只依赖 `Content-Length`；原始 HTTP 响应元数据另有 **64 KiB** 限额。
- `BlockingHttpRequests.kt` / `BoundedHttpTransport.kt`：请求拥有覆盖排队、DNS、连接、写入和读取的单次终止预算，默认由 `3s` 连接超时加 `8s` 读取超时组成，即 `11s`。这是每次尝试的预算，不是含重试的整项业务时限。
- 取消或超时会触发实际连接关闭；收尾等待旧工作线程和资源清理完成，避免仅取消协程后就启动重叠请求。`CancellationException` 不作为普通网络失败重试。
- 前台查询/审批与后台范围查询/缓存回填使用独立的有界资源通道；线程按需创建、空闲回收。后台传输上限覆盖 `32` 个显式配置 worker 加一个范围查询，不改变引擎默认并发配置。
- URL 请求使用直接声明的 `OkHttp 4.12.0`，保留默认 TLS 校验；原始 HTTP/1.1 POST 的报文、请求头和不新增 User-Agent 的行为保留。
- 网络日志不再输出完整响应正文、请求 URL 或服务端自由文本，避免大日志分配和客户信息泄露；前文关于完整响应体日志的说明不再适用。

### UIKit、诊断与业务边界

- UIKit 制品位于 `uikit/UIKitInsight-release.aar`，构建前校验 `uikit/SHA256SUMS`，不再使用前文旧路径 `app/libs/`。
- 保留此前的常驻恢复宿主：故障 Toast 后由宿主管理延迟重开，先释放旧实例再创建新代，恢复不因切到扫码页而被取消；连续失败的限次保护仍保留，不通过无限重启或视觉遮挡冒充恢复成功。
- `HostBrowserDebug.kt` 的详细日志和堆栈默认不开启，由 `BreakfastBrowserDebug` 的 DEBUG 日志开关控制，减少挂载、释放时的额外分配。
- `BreakfastApplication.kt` 仅在主进程安装宿主诊断和采集退出历史，避免 Gecko 子进程重复初始化并竞争同名诊断文件；保留原系统致命异常处理器。
- 审批业务入队仍只有 `ApproveBreakfast` 和 `CancelEnrollment` 两个入口；普通扫码/查询不入队，`unlinkIgnoredSource()` 仍返回 `false`。
- UIKit 签名校验保持 `bypass=false`；UI 恢复不重建或清空业务 runtime、WaitGroup、scan2fail 及用户数据。

### 验证记录与尚存风险

- 已补充网络慢流/超限/取消及资源竞态、扫码枪串行调度、帧节流，以及队列恢复期间主线程响应和批量过期清理回归测试。
- 2026-09-16 16:53 的本地 JVM 测试报告共 **99 项，失败 0、错误 0**；同次已有 Debug APK 和 Android 测试 APK 产物，补丁空白检查通过。
- **Android 测试 APK 构建不等于真机测试执行。** 尚未验证极低性能设备上的实际 CPU/PSS、冷启动耗时、反复前后台切换，以及 WebView/Gecko 故障后是否真正恢复可交互。
- WaitGroup 全环 JSON/哈希和普通缓存逐条 `commit()` 的成本仍存在，尚未全面优化。
- 平台 DNS 或厂商 Binder 若不可中断，无法保证立即结束；网络终止预算也不能强行终止这类底层调用，收尾等待可能超过预算。目前通过主线程隔离和有界资源限制避免无限扩张，不能据此宣称所有卡死和闪退均已修复。
