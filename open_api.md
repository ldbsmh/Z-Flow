# Z-Flow开放API（持续更新）

Z-Flow调用接口现已对外开放，你可以选择以下方式启动Z-Flow提供的小窗：

## 1.打开Z-Flow提供的应用选择界面
Z-Flow提供的打开应用选择界面的活动为：io.relimus.zflow.ui.floating.FloatingActivity。你可以通过其他应用调用该活动以打开应用选择界面。

<b>请注意：该活动目前需要Z-Flow的保活服务处于运行状态</b>

## 2.直接打开Z-Flow提供的小窗界面
除上述方式外，Z-Flow还提供广播方式接收外部应用发送的打开小窗指令。具体例子如下：

```kotlin
### 方法1:
val packageName: String = "io.relimus.zflow"
val activityName: String = "io.relimus.zflow.ui.main.MainActivity"
val userId: Int = 0
val intent = Intent("io.relimus.zflow.start_freeform").apply {
                    setPackage("io.relimus.zflow")
                    //要启动小窗程序的包名：如io.relimus.zflow
                    putExtra("packageName", packageName)
                    //要启动小窗的活动名称，请注意，该活动可能需要对外暴露才可启动。如io.relimus.zflow.ui.main.MainActivity
                    putExtra("activityName", activityName)
                    //可选，默认为-1。对于系统存在“应用分身”等情况，可以指定userId
                    putExtra("userId", userId)
                    //
                    putExtra(Intent.EXTRA_INTENT, intent)
                }
context.sendBroadcast(intent)
### 方法2：
val packageName: String = "io.relimus.zflow"
val activityName: String = "io.relimus.zflow.ui.main.MainActivity"
val startIntent: Intent = Intent().setComponent(ComponentName(packageName, activityName)
val intent = Intent("io.relimus.zflow.start_freeform").apply {
    setPackage("io.relimus.zflow")
    //
    putExtra(Intent.EXTRA_INTENT, startIntent)
}
context.sendBroadcast(intent)

```