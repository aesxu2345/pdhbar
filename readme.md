# Android 早餐核销 App Runtime 设计文档

## 0. 设计目标

本设计用于一个 Android Kotlin App，核心场景是：

* PDA 扫描导检单条形码；
* 根据条码触发早餐核销业务；
* 调用院内 HTTP 接口；
* UI 线程和业务线程异步通信；
* 使用一个顶层缓冲区 `std` 管理运行时对象；
* 使用 `PipeFallIO` / `PipeFall` 管理事件流；
* 使用 `OnBindSystemIO(pipefall)` 把 `PipeFallIO` 上转型为 `IBinder`，作为系统级通信锚点；
* 后续允许在这些对象上继续建立语法糖。

本文档描述的是**对象设计、生命周期、线程边界、接口签名和约束规则**。

---

# 1. 总体对象结构

运行时顶层命名空间叫：

```kt
std
```

`std` 是 App 进程级缓冲区，不是 Activity 级变量。

推荐结构：

```text
std
├── api: Postman
│     └── target: NetTarget
│
├── barscanner: BarScanner
│     └── 扫码对象
│
├── pipefall: PipeFallIO
│     └── UI / Worker 事件通信接口
│
├── systemIO: IBinder
│     └── OnBindSystemIO(pipefall) 生成
│
└── uifurge: UIfurge
      └── UI 事件管理器
```

核心语义：

```text
std 是总缓冲区
Postman 是 HTTP 接口工厂
PostmanAction 是一次性请求对象
barscanner 是扫码器对象
UIfurge 管 UI 事件
PipeFall / PipeFallIO 管事件管道
IBinder 只作为 PipeFallIO 的锚点
```

---

# 2. 生命周期约定

## 2.1 App / Activity 生命周期分工

```text
Application.onCreate:
    初始化 std 的底层环境

Activity.onCreate:
    装配 std 的运行时对象
    创建 pipefall
    创建 systemIO
    创建 api
    创建 barscanner
    创建 uifurge

Activity.onStart:
    UI attach
    UIfurge 可以绑定当前 Activity / View
    不创建全局对象

Activity.onResume:
    barscanner.start()
    开始接收扫码输入

Activity.onPause:
    barscanner.stop()
    停止接收扫码输入

Activity.onStop:
    flush 可恢复状态
    不销毁 std

Activity.onDestroy:
    释放页面级资源
    释放扫码句柄
    不作为业务最终兜底
```

## 2.2 重要规则

```text
std 不挂在 Activity.onStart 上
std 不依赖 Activity.onDestroy 才保存状态
onCreate 是装配窗口
onResume 是运行窗口
onPause 是暂停窗口
onDestroy 是释放窗口
```

`onDestroy()` 不是可靠的业务兜底点，因为 Android 进程可能被系统直接杀掉。

因此：

```text
核心业务状态不能只存在 Activity
关键核销结果必须及时进入本地持久化 / pending 队列
```

---

# 3. std 顶层缓冲区

## 3.1 std 的职责

`std` 是 App 进程内的顶层运行时命名空间。

它负责保存长生命周期对象：

```text
api
barscanner
pipefall
systemIO
uifurge
```

它不应该保存：

```text
Activity
View
Dialog
Button
旧的 Context
临时请求对象
临时 UI 事件
```

## 3.2 std 对象签名

```kt
class std private constructor(
    val api: Postman,
    val barscanner: BarScanner,
    val pipefall: PipeFallIO,
    val systemIO: IBinder,
    val uifurge: UIfurge
) {
    companion object {
        private var buffer: std? = null

        fun onCreate(): std {
            buffer?.let { return it }

            val pipefall = PipeFall()
            val systemIO = OnBindSystemIO(pipefall)
            val api = Postman(
            target = NetTarget(
                scheme = "http",
                host = "172.16.203.56",
                port = 8088,
                path = "/query_breakfast_right"
            )
        )
            val barscanner = NewBarScanner(api = api, pipefall = pipefall)
            val uifurge = UIfurge(pipefall = pipefall)

            return std(api, barscanner, pipefall, systemIO, uifurge)
                .also { buffer = it }
        }

        fun run(): std = buffer ?: onCreate()
    }
}
```

## 3.3 std 的生命周期

```text
Activity 重建：
    std 仍然可以存在

页面切后台：
    std 仍然存在

旋转屏幕：
    std 仍然存在

系统杀进程：
    std 消失

App 崩溃：
    std 消失
```

所以 `std` 是**进程级缓冲区**，不是永久存储。

---

# 4. NetTarget 设计

## 4.1 为什么不能只叫 ipv4

最初接口成员可以是：

```text
ipv4: String
```

但这会限制后续扩展。

未来可能增加：

```text
scheme
host
port
path
headers
timeout
retry
terminalId
operatorId
token
```

所以不再使用单独的 `ipv4` 字符串，而是使用结构体：

```kt
NetTarget
```

## 4.2 NetTarget 签名

```kt
data class NetTarget(
    val scheme: String = "http",
    val host: String,
    val port: Int = 80,
    val path: String = "",

    val headers: Map<String, String> = emptyMap(),

    val connectTimeoutMs: Long = 3000,
    val readTimeoutMs: Long = 5000,
    val retry: Int = 0,

    val terminalId: String? = null,
    val operatorId: String? = null
) {
    fun url(): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$host:$port$cleanPath"
    }
}
```

## 4.3 早餐接口示例

当前接口：

```text
GET http://172.16.203.56:8088/change_breakfast_status

参数：
order_code          订单号，必填
breakfast_status    状态，1=登记，0=取消登记，必填
breakfast_remark    备注，可选

返回：
code = 200 成功
code = 400 错误
msg  = 错误信息
```

对应 `NetTarget`：

```kt
NetTarget(
    scheme = "http",
    host = "172.16.203.56",
    port = 8088,
    path = "/change_breakfast_status"
)
```

---

# 5. Postman 设计

## 5.1 Postman 的定义

这里的 `Postman` 不是 Postman 软件本身。

在本设计里：

```text
Postman = HTTP 接口工厂对象
```

它内部持有一个 `NetTarget`。

它不直接执行请求，而是生成一次性请求对象。

```text
Postman
├── target: NetTarget
├── get(xwww)
└── post(json)
```

## 5.2 get / post 语义

```text
get(xwww):
    生成 GET + x-www-form-urlencoded/query 参数请求对象

post(json):
    生成 POST + JSON body 请求对象
```

注意：

```text
get/post 不是最终执行点
Do 才是最终执行点
```

## 5.3 Postman 签名

```kt
class Postman(
    val target: NetTarget
) {
    fun get(xwww: Map<String, String>): PostmanAction {
        return GetXwwwAction(target, xwww)
    }

    fun post(json: Map<String, Any?>): PostmanAction {
        return PostJsonAction(target, json)
    }
}
```

---

# 6. PostmanAction 设计

## 6.1 为什么需要 PostmanAction

不能让全局 `Postman` 自己反复修改内部 `excute`。

错误模型：

```text
std.api.get(...) 修改 std.api 内部 execute
std.api.Do()
```

问题：

```text
A 扫码生成请求 A
B 扫码生成请求 B
B 覆盖了 std.api 内部 execute
A 再 Do 时可能执行到 B 的请求
```

这会导致并发串单。

所以修正为：

```text
std.api 是长活对象
std.api.get(...) 生成短活 action
action.Do() 执行
action.ok 暴露结果
action 用完后等待 GC
```

## 6.2 PostmanAction 生命周期

```text
get/post 生成 action
Do() 执行 action
Do() 写入 action.ok
上层读取 action.ok
局部变量作用域结束
action 无引用
等待 GC
```

## 6.3 PostmanAction 公开成员

`Boolean` 结果不能只作为 `.Do()` 的临时返回值。

否则上层容易失联。

所以结果作为公开成员：

```kt
action.ok
```

同时保留：

```kt
action.done
action.msg
```

## 6.4 PostmanAction 签名

```kt
interface PostmanAction {
    val ok: Boolean
    val done: Boolean
    val msg: String?

    suspend fun Do(): PostmanAction
}
```

## 6.5 OncePostmanAction

一次性请求对象只能执行一次。

```kt
abstract class OncePostmanAction : PostmanAction {

    final override var ok: Boolean = false
        protected set

    final override var done: Boolean = false
        protected set

    final override var msg: String? = null
        protected set

    final override suspend fun Do(): PostmanAction {
        if (done) {
            ok = false
            msg = "action already executed"
            return this
        }

        done = true

        ok = try {
            runOnce()
        } catch (e: Exception) {
            msg = e.message
            false
        }

        return this
    }

    protected abstract suspend fun runOnce(): Boolean
}
```

## 6.6 GET Action

```kt
class GetXwwwAction(
    private val target: NetTarget,
    private val xwww: Map<String, String>
) : OncePostmanAction() {

    override suspend fun runOnce(): Boolean {
        /*
            这里执行 HTTP GET。

            目标：
            target.url()

            参数：
            xwww 作为 query 参数

            成功条件：
            后端返回 code == 200

            失败条件：
            网络失败
            HTTP 失败
            后端 code != 200
        */

        return true
    }
}
```

## 6.7 POST JSON Action

```kt
class PostJsonAction(
    private val target: NetTarget,
    private val json: Map<String, Any?>
) : OncePostmanAction() {

    override suspend fun runOnce(): Boolean {
        /*
            这里执行 HTTP POST。

            Content-Type:
            application/json

            body:
            json

            成功条件：
            后端返回 code == 200
        */

        return true
    }
}
```

## 6.8 早餐核销调用示例

登记早餐：

```kt
val action = std.api.get(
    mapOf(
        "order_code" to orderCode,
        "breakfast_status" to "1",
        "breakfast_remark" to "PDA扫码核销早餐"
    )
).Do()

if (action.ok) {
    std.pipefall.writeCommand(
        UiCommand.Toast("早餐核销成功")
    )
} else {
    std.pipefall.writeCommand(
        UiCommand.Toast(action.msg ?: "早餐核销失败")
    )
}
```

取消登记：

```kt
val action = std.api.get(
    mapOf(
        "order_code" to orderCode,
        "breakfast_status" to "0",
        "breakfast_remark" to "PDA取消早餐核销"
    )
).Do()

if (action.ok) {
    std.pipefall.writeCommand(
        UiCommand.Toast("已取消早餐核销")
    )
} else {
    std.pipefall.writeCommand(
        UiCommand.Toast(action.msg ?: "取消早餐核销失败")
    )
}
```

---

# 7. BarScanner 设计

## 7.1 BarScanner 的定义

`barscanner` 是 `std` 下的扫码对象。

它在 `onCreate` 阶段创建。

```text
onCreate:
    val barscanner = NewBarScanner(...)
```

## 7.2 BarScanner 的职责

`BarScanner` 只负责：

```text
启动扫码
停止扫码
释放扫码句柄
接收扫码结果
把扫码结果投递到 PipeFall
```

它不直接渲染 UI。

它不直接持有 Activity。

## 7.3 BarScanner 签名

```kt
class BarScanner(
    private val api: Postman,
    private val pipefall: PipeFallIO
) {
    fun start() {
        /*
            开始扫码监听。
            可绑定 PDA 扫码枪 SDK、广播、输入框监听等。
        */
    }

    fun stop() {
        /*
            暂停扫码监听。
            Activity.onPause 时调用。
        */
    }

    fun destroy() {
        /*
            释放扫码 SDK 句柄。
            Activity.onDestroy 时调用。
        */
    }

    suspend fun onScan(orderCode: String) {
        pipefall.writeEvent(
            UiEvent.ScanCode(orderCode)
        )
    }
}
```

## 7.4 BarScanner 生命周期

```text
onCreate:
    创建 barscanner

onResume:
    barscanner.start()

onPause:
    barscanner.stop()

onDestroy:
    barscanner.destroy()
```

## 7.5 BarScanner 约束

```text
barscanner 可以长活
扫码事件必须短活
扫码事件进入 PipeFall
不要让 BarScanner 直接操作 Activity/View/Dialog
```

---

# 8. UIfurge 设计

## 8.1 UIfurge 的定义

`UIfurge` 是 UI 事件管理器。

它负责 UI 层事件和 PipeFall 的连接。

```text
UIfurge
├── 接收 UI 点击
├── 接收扫码输入回调
├── 把 UI 事件写入 PipeFall
├── 接收 UI Command
└── 在 UI 线程渲染
```

## 8.2 UIfurge 不负责的事情

```text
不直接发 HTTP 请求
不直接保存业务状态
不直接做早餐核销逻辑
不持有长生命周期 Activity
```

## 8.3 UIfurge 签名

```kt
class UIfurge(
    private val pipefall: PipeFallIO
) {
    fun onScanInput(code: String) {
        pipefall.writeEvent(
            UiEvent.ScanCode(code)
        )
    }

    fun onBreakfastDoneClick(orderCode: String) {
        pipefall.writeEvent(
            UiEvent.BreakfastDone(orderCode)
        )
    }

    fun onBreakfastCancelClick(orderCode: String) {
        pipefall.writeEvent(
            UiEvent.BreakfastCancel(orderCode)
        )
    }

    fun render(command: UiCommand) {
        when (command) {
            is UiCommand.Toast -> {
                /*
                    UI 线程 Toast
                */
            }

            is UiCommand.ShowCustomer -> {
                /*
                    UI 线程弹窗
                */
            }

            UiCommand.CloseDialog -> {
                /*
                    UI 线程关闭弹窗
                */
            }
        }
    }
}
```

## 8.4 UI 线程规则

任何真正 UI 渲染必须发生在主线程。

```text
Worker 线程不能直接修改 View
Worker 线程只能发送 UiCommand
UIfurge 收到 UiCommand 后切回 UI 线程渲染
```

---

# 9. UiEvent / UiCommand

## 9.1 UiEvent

`UiEvent` 表示 UI 层进入业务层的事件。

```kt
sealed interface UiEvent {
    data class ScanCode(
        val code: String
    ) : UiEvent

    data class BreakfastDone(
        val orderCode: String
    ) : UiEvent

    data class BreakfastCancel(
        val orderCode: String
    ) : UiEvent

    data object DialogCancel : UiEvent
}
```

方向：

```text
UI Thread / Scanner
    -> UIfurge / BarScanner
    -> PipeFall.writeEvent()
    -> Worker Thread
```

## 9.2 UiCommand

`UiCommand` 表示业务层返回 UI 层的渲染命令。

```kt
sealed interface UiCommand {
    data class Toast(
        val message: String
    ) : UiCommand

    data class ShowCustomer(
        val orderCode: String,
        val cardNo: String?,
        val name: String,
        val sex: String?,
        val phone: String?,
        val avatarUrl: String?,
        val birthDate: String?,
        val packageName: String?,
        val hasBreakfast: Boolean
    ) : UiCommand

    data object CloseDialog : UiCommand
}
```

方向：

```text
Worker Thread
    -> PipeFall.writeCommand()
    -> UIfurge
    -> UI Thread render
```

---

# 10. PipeFallIO / PipeFall 设计

## 10.1 核心要求

用户要求：

```text
设置一个 pipefall 接口
里面是每个事件的套接字
这两个是异步执行的
使用通信管道连接它们做流传输
接口成员再上转型到 IBinder
以 IBinder 作为锚点接收另一个线程 UI 的内容
```

本设计保持这条主线，但只小修对象签名：

```text
PipeFallIO 本身具备 Binder 能力
PipeFall 继承 PipeFallIO
OnBindSystemIO(pipefall) 返回 IBinder
```

## 10.2 PipeFallIO 签名

最小 Binder-capable 签名：

```kt
abstract class PipeFallIO : Binder() {

    abstract fun writeEvent(event: UiEvent)

    abstract fun writeCommand(command: UiCommand)
}
```

这意味着：

```text
PipeFallIO : Binder
Binder : IBinder
所以 PipeFallIO 可以被上转型为 IBinder
```

## 10.3 PipeFall 签名

```kt
class PipeFall : PipeFallIO() {

    override fun writeEvent(event: UiEvent) {
        /*
            UI -> Worker

            这里不直接做业务。
            这里只负责把事件写入事件管道。
        */
    }

    override fun writeCommand(command: UiCommand) {
        /*
            Worker -> UI

            这里不直接渲染 UI。
            这里只负责把 UI 命令写入事件管道。
        */
    }
}
```

## 10.4 PipeFall 的语义

```text
PipeFall 是通信管道
PipeFallIO 是管道接口
writeEvent 是 UI 到 Worker 的入口
writeCommand 是 Worker 到 UI 的入口
```

## 10.5 “每个事件的套接字”解释

概念上可以这样理解：

```text
PipeFall
├── scanSocket
│     └── ScanCode 事件
│
├── breakfastDoneSocket
│     └── BreakfastDone 事件
│
├── breakfastCancelSocket
│     └── BreakfastCancel 事件
│
└── renderSocket
      └── UiCommand 渲染命令
```

但第一版实现不必真的创建很多 socket 类。

第一版可以只保留：

```text
writeEvent(event)
writeCommand(command)
```

后续如果事件量变大，再把内部拆分为多个 socket。

---

# 11. OnBindSystemIO 设计

## 11.1 方法定义

`OnBindSystemIO()` 是 Binder 出口。

它接收 `PipeFallIO`，返回 `IBinder`。

```kt
fun OnBindSystemIO(pipefall: PipeFallIO): IBinder {
    return pipefall
}
```

## 11.2 方法语义

```text
OnBindSystemIO(pipefall)
    输入：PipeFallIO
    输出：IBinder
```

因为：

```text
PipeFallIO : Binder
Binder : IBinder
```

所以这里是合法上转型。

## 11.3 std 中的用法

```kt
std.pipefall = PipeFall()

std.systemIO = OnBindSystemIO(std.pipefall)
```

得到：

```text
std.pipefall 是 PipeFallIO
std.systemIO 是 IBinder
二者指向同一套通信对象
```

## 11.4 取回 PipeFallIO

同进程内可以：

```kt
val pipe = std.systemIO as PipeFallIO
```

然后：

```kt
pipe.writeEvent(
    UiEvent.ScanCode("123456")
)
```

## 11.5 重要边界

当前设计适合：

```text
同一个 App 进程内
UI 线程和 Worker 线程之间
用 IBinder 做锚点
```

如果以后要跨进程，需要把 `PipeFallIO` 替换成：

```text
AIDL
Messenger
ResultReceiver
```

但不改主结构。

---

# 12. AIDL / Messenger / ResultReceiver 预留签名

本设计当前采用：

```kt
abstract class PipeFallIO : Binder()
```

如果后续要跨进程，只允许改对象签名，不改整体对象关系。

## 12.1 AIDL 版本

AIDL 接口：

```aidl
interface IPipeFallIO {
    oneway void writeEvent(in Bundle event);
    oneway void writeCommand(in Bundle command);
}
```

Kotlin：

```kt
class PipeFall : IPipeFallIO.Stub() {

    override fun writeEvent(event: Bundle) {
        /*
            UI -> Worker
        */
    }

    override fun writeCommand(command: Bundle) {
        /*
            Worker -> UI
        */
    }
}
```

`OnBindSystemIO()`：

```kt
fun OnBindSystemIO(pipefall: IPipeFallIO): IBinder {
    return pipefall.asBinder()
}
```

主线仍然是：

```text
std.pipefall = PipeFall()
std.systemIO = OnBindSystemIO(std.pipefall)
```

## 12.2 Messenger 版本

```kt
class PipeFallIO(
    val messenger: Messenger
)
```

```kt
fun OnBindSystemIO(pipefall: PipeFallIO): IBinder {
    return pipefall.messenger.binder
}
```

## 12.3 ResultReceiver 版本

`ResultReceiver` 更适合做回调 socket，不适合作为主锚点。

可以作为 `PipeFallIO` 内部成员：

```kt
class PipeFallIO(
    val receiver: ResultReceiver
)
```

但主锚点仍建议是：

```text
Binder / AIDL / Messenger
```

---

# 13. 异步执行模型

## 13.1 线程划分

```text
UI Thread:
    Activity
    Dialog
    Button
    Toast
    UIfurge.render()

Worker Thread:
    扫码业务处理
    HTTP 请求
    本地缓存
    pending 队列
    早餐核销逻辑
```

## 13.2 通信方向

UI 到 Worker：

```text
UI 点击 / 扫码输入
    -> UIfurge / BarScanner
    -> PipeFall.writeEvent(UiEvent)
    -> Worker 消费 UiEvent
```

Worker 到 UI：

```text
Worker 完成业务
    -> PipeFall.writeCommand(UiCommand)
    -> UIfurge.render()
    -> UI Thread 渲染
```

## 13.3 禁止事项

```text
Worker 线程不能直接操作 View
Worker 线程不能直接弹 Dialog
Worker 线程不能直接 Toast
UI 线程不能执行耗时 HTTP
UI 线程不能阻塞等待接口返回
```

---

# 14. 早餐核销完整事件流

## 14.1 扫码查看客户信息

```text
1. PDA 扫描导检单条码
2. barscanner 收到 orderCode
3. barscanner 写入 PipeFall：
       UiEvent.ScanCode(orderCode)

4. Worker 收到 ScanCode
5. Worker 查询客户信息
6. Worker 写入 PipeFall：
       UiCommand.ShowCustomer(...)

7. UIfurge 收到 ShowCustomer
8. UI 线程弹出客户信息弹窗
```

## 14.2 点击完成早餐

```text
1. 阿姨点击【完成早餐】
2. UIfurge 写入 PipeFall：
       UiEvent.BreakfastDone(orderCode)

3. Worker 收到 BreakfastDone
4. Worker 创建一次性请求：
       std.api.get(...)

5. action.Do()
6. action.ok == true:
       写入本地成功状态
       PipeFall.writeCommand(Toast("早餐核销成功"))

7. action.ok == false:
       写入 pending 队列
       PipeFall.writeCommand(Toast("核销失败，已进入待重试队列"))

8. action 作用域结束
9. action 等待 GC
```

## 14.3 点击取消

```text
1. 阿姨点击【取消】
2. UIfurge 写入 PipeFall：
       UiEvent.DialogCancel

3. Worker 或 UI 处理关闭命令
4. PipeFall.writeCommand(UiCommand.CloseDialog)
5. UIfurge 在 UI 线程关闭弹窗
```

## 14.4 点击取消早餐登记

```text
1. 阿姨点击【取消早餐登记】
2. UIfurge 写入 PipeFall：
       UiEvent.BreakfastCancel(orderCode)

3. Worker 调用：
       std.api.get(
           order_code = orderCode,
           breakfast_status = 0
       ).Do()

4. action.ok == true:
       本地状态改为未登记
       UI 提示取消成功

5. action.ok == false:
       进入失败处理
```

---

# 15. 对象生命周期和 GC 规则

## 15.1 长活对象

以下对象长活，挂在 `std`：

```text
std.api
std.run().barscanner
std.pipefall
std.systemIO
std.uifurge
```

## 15.2 短活对象

以下对象短活：

```text
PostmanAction
UiEvent
UiCommand
HTTP 请求参数 Map
扫码事件对象
```

## 15.3 PostmanAction GC 规则

```text
action 由 get/post 生成
action.Do() 执行
action.ok 被上层读取
action 不写入 std
action 不写入全局变量
action 不进入长生命周期队列
局部作用域结束后等待 GC
```

错误写法：

```kt
std.lastAction = std.api.get(params)
```

原因：

```text
std.lastAction 会持有 action
action 不会被 GC
并且可能导致串单
```

正确写法：

```kt
val action = std.api.get(params).Do()

if (action.ok) {
    // success
} else {
    // fail
}
```

---

# 16. 命名规则

## 16.1 固定命名

```text
std             顶层运行时缓冲区
barscanner      扫码器对象
Postman         HTTP 接口工厂
NetTarget       接口目标结构体
PostmanAction   一次性请求对象
Do              请求执行器
ok              请求结果公开成员
UIfurge         UI 事件管理器
PipeFall        事件管道实现
PipeFallIO      事件管道接口
OnBindSystemIO  PipeFallIO -> IBinder 的绑定出口
systemIO        IBinder 锚点
```

## 16.2 保留 excute 拼写说明

设计中曾提到 `excute` 空方法。

正式代码中可以保留语义，但建议内部使用：

```text
execute
```

如果为了 DSL 风格必须叫：

```text
excute
```

也可以，但需要统一，不要一半 `excute` 一半 `execute`。

---

# 17. 不允许随意改动的主线

为了避免 agent 把方案改散，以下主线不要改：

```text
1. 顶层缓冲区必须叫 std
2. 扫码对象必须叫 barscanner
3. HTTP 接口工厂叫 Postman
4. 接口地址成员必须是 NetTarget 结构体
5. get/post 生成一次性 PostmanAction
6. Do 执行 action
7. action.ok 是公开成员
8. UIfurge 专门管理 UI 事件
9. PipeFallIO / PipeFall 管事件管道
10. OnBindSystemIO(pipefall) 返回 IBinder
11. IBinder 只作为 PipeFallIO 锚点
12. UI 和 Worker 异步执行
```

---

# 18. 允许小改的地方

只允许改对象签名，不允许重写架构。

允许：

```text
PipeFallIO : Binder()
```

改成：

```text
AIDL Stub
Messenger wrapper
ResultReceiver wrapper
```

允许：

```text
PostmanAction.Do()
```

内部改 OkHttp / HttpURLConnection / Retrofit 实现。

允许：

```text
PipeFall 内部事件管道
```

从简单函数改为：

```text
Channel
SharedFlow
Handler
Looper
BlockingQueue
```

不允许：

```text
把 std 拆掉
把 Postman 改成直接执行请求
把 action.ok 改回 Do 的临时返回值
让 UI 线程直接跑 HTTP
让 Worker 线程直接操作 View
让每个 UiEvent 都变成 IBinder
```

---

# 19. Agent 实现检查表

实现代码前，agent 必须确认：

```text
[ ] std 是否存在
[ ] std.api 是否是 Postman
[ ] std.api.target 是否是 NetTarget
[ ] std.run().barscanner 是否由 onCreate 返回的缓冲区持有
[ ] std.pipefall 是否是 PipeFallIO
[ ] std.systemIO 是否来自 OnBindSystemIO(std.pipefall)
[ ] PipeFallIO 是否可以上转型为 IBinder
[ ] get/post 是否返回 PostmanAction
[ ] PostmanAction 是否有公开 ok 成员
[ ] Do() 是否返回 PostmanAction 自身
[ ] action 是否一次性执行
[ ] UIfurge 是否只管 UI 事件
[ ] Worker 是否通过 PipeFall 接收 UiEvent
[ ] UI 是否通过 PipeFall 接收 UiCommand
[ ] Worker 是否没有直接操作 View
[ ] UI 是否没有直接执行 HTTP
```

---

# 20. 最终一句话总结

本设计的核心是：

```text
std 是总缓冲区；
Postman 是长活 HTTP 工厂；
PostmanAction 是短活一次性请求；
Do() 执行后写 action.ok；
UIfurge 管 UI；
PipeFall 管异步事件流；
OnBindSystemIO 把 PipeFallIO 上转型成 IBinder 作为系统通信锚点；
UI 和 Worker 通过 PipeFall 异步通信，互不直接越界。
```

---

# 21. 项目树与许可引用

```text
pdhbar/
├── LICENSE                         # 项目源码 MIT License
├── THIRD_PARTY_NOTICES.md          # 第三方组件许可与致谢
├── readme.md                       # 设计文档与项目说明
├── build.gradle.kts                # 根 Gradle 配置
├── settings.gradle.kts             # Gradle 项目配置
├── gradle/libs.versions.toml       # 依赖版本清单
└── app/
    ├── build.gradle.kts            # Android App 模块配置
    └── src/main/
        ├── AndroidManifest.xml     # App 声明、名称、图标、权限
        ├── java/com/pdyy/pdhbar/   # Kotlin 源码
        └── res/                    # 图标、主题、字符串、音效等资源
```

项目源码采用 MIT License：

```text
Copyright (c) 2026 LuYuHeng <aesxu2345@outlook.com>
```

贡献者：

```text
LuYuHeng <aesxu2345@outlook.com>
```

完整协议见 `LICENSE`。第三方组件许可与致谢见 `THIRD_PARTY_NOTICES.md`。
