package io.relimus.zflow.ui.floating

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.net.toUri
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.github.promeg.pinyinhelper.Pinyin
import io.relimus.zflow.R
import io.relimus.zflow.room.FreeFormAppsEntity
import io.relimus.zflow.ui.view.WaveSideBarView
import io.relimus.zflow.utils.PackageUtils
import io.relimus.zflow.utils.cast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/**
 * @date 2022/8/23
 * @author sunshine0523
 */
class ChooseAppFloatingView(
    private val context: Context,
    var showPositionX: Int,
    private val removeCallback: OnWindowRemoveCallback
){
    //服务
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE).cast()
    private val displayManager: DisplayManager = context.getSystemService(Context.DISPLAY_SERVICE).cast()
    private val defaultDisplay: Display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    //scope
    private val scope = MainScope()

    //处理多用户
    private val launcherApps: LauncherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE).cast()
    private val userManager: UserManager = context.getSystemService(Context.USER_SERVICE).cast()
    private val userHandleMap = HashMap<Int, UserHandle>()

    private val chooseAppFloatingViewModel = ChooseAppFloatingViewModel(context)
    private var allFreeFormApps: ArrayList<FreeFormAppsEntity>? = null

    private var floatingView: View? = null
    private var floatingViewLayoutParams = WindowManager.LayoutParams()
    private var allAppsView: View? = null

    //物理屏幕方向，1竖屏，2横屏
    private var screenRotation = context.resources.configuration.orientation

    //所有应用列表
    private var allAppsList = ArrayList<LauncherActivityInfo>()
    //使用拼音排序
    private var appsPinyinMap = HashMap<String, String>()

    init {
        userManager.userProfiles.forEach {
            userHandleMap[io.relimus.zflow.systemapi.UserHandle.getUserId(it)] = it
        }
    }

    fun showFloatingView() {
        scope.launch(Dispatchers.IO) {
            allFreeFormApps = chooseAppFloatingViewModel.getAllFreeFormApps().first().cast()
            withContext(Dispatchers.Main) {
                floatingView = LayoutInflater.from(context).inflate(R.layout.view_choose_app_floating, FrameLayout(context), false)
                floatingViewLayoutParams.apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    height = WindowManager.LayoutParams.MATCH_PARENT
                    //选择应用界面决定还是禁止在系统界面显示了
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    format = PixelFormat.RGBA_8888
                    gravity = Gravity.CENTER_VERTICAL

                    val width = if (defaultDisplay.rotation == Surface.ROTATION_0 || defaultDisplay.rotation == Surface.ROTATION_180) {
                        min(context.resources.displayMetrics.heightPixels, context.resources.displayMetrics.widthPixels)
                    } else {
                        max(context.resources.displayMetrics.heightPixels, context.resources.displayMetrics.widthPixels)
                    }
                    x = width / 2
                    windowAnimations = android.R.style.Animation_Dialog
                }

                setFloatingViewContent(floatingView)

                //click out to remove
                floatingView?.setOnClickListener {
                    removeWindow()
                }

                try {
                    windowManager.addView(floatingView, floatingViewLayoutParams)
                } catch (_: Exception) {
                    windowManager.removeViewImmediate(floatingView)
                    if (Settings.canDrawOverlays(context)) {
                        windowManager.addView(floatingView, floatingViewLayoutParams.apply {
                            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        })
                    } else {
                        try {
                            Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri()
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(
                                intent
                            )
                        } catch (_: Exception) {
                            Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * 屏幕方向改变监听
     */
    fun onScreenRotationChanged(newRotation: Int) {
        screenRotation = newRotation
        try {
            windowManager.removeViewImmediate(floatingView)
        }catch (_: Exception){ }
        try {
            windowManager.removeViewImmediate(allAppsView)
        }catch (_: Exception) { }
        removeCallback.onChooseAppWindowRemove()
    }

    /**
     * 设置悬浮窗口的内容
     * 就是设置显示的APP
     */
    private fun setFloatingViewContent(floatingView: View?) {
        //TODO 这里有一个奇怪的事情：当下面的xml布局为google的cardview时，就会解析失败，但是用androidx的就可以
        val container = floatingView?.findViewById<LinearLayout>(
            if (showPositionX == -1) R.id.recycler_view_contain_left else R.id.recycler_view_contain_right
        )
        val recyclerAppsLayout = LayoutInflater.from(context).inflate(R.layout.view_choose_app_floting_view_recycler_app, container, false)
        val recyclerView: RecyclerView = recyclerAppsLayout.findViewById(R.id.recycler_view)
        container?.addView(recyclerAppsLayout)

        //删除已经卸载的app
        val noInstallAppsList = ArrayList<FreeFormAppsEntity>()
        allFreeFormApps?.forEach {
            if (it.userId == 0) {
                if (!PackageUtils.hasInstallThisPackage(it.packageName, context.packageManager)) {
                    noInstallAppsList.add(it)
                }
            } else {
                val userHandle = if (userHandleMap.containsKey(it.userId)) userHandleMap[it.userId]!! else userHandleMap[0]!!
                if (!PackageUtils.hasInstallThisPackageWithUserId(it.packageName, launcherApps, userHandle)) {
                    noInstallAppsList.add(it)
                }
            }
        }
        noInstallAppsList.forEach {
            allFreeFormApps?.remove(it)
        }

        Collections.sort(allFreeFormApps!!, AppsComparable())

        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = ChooseAppFloatingAdapter(
            context,
            allFreeFormApps,
            object: ClickListener() {
                override fun onClick() {
                    removeWindow()
                }
            },
            object: ClickListener() {
                override fun onClick() {
                    try {
                        windowManager.removeViewImmediate(floatingView)
                    } catch (_: Exception) {}
                    showAllAppsView()
                }
            }
        )

        if (noInstallAppsList.isNotEmpty()) {
            //Delete NOT install apps from db
            scope.launch(Dispatchers.IO) {
                chooseAppFloatingViewModel.deleteNotInstall(noInstallAppsList)
            }
        }
    }

    private fun showAllAppsView() {
        if (Settings.canDrawOverlays(context)) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val realWidth: Int
            val realHeight: Int
            val overlayWidth: Int
            val overlayHeight: Int

            if (defaultDisplay.rotation == Surface.ROTATION_90 || defaultDisplay.rotation == Surface.ROTATION_270) {
                realWidth = max(bounds.width(), bounds.height())
                realHeight = min(bounds.width(), bounds.height())
                overlayWidth = realWidth / 2
                overlayHeight = realHeight
            } else {
                realWidth = min(bounds.width(), bounds.height())
                realHeight = max(bounds.width(), bounds.height())
                overlayWidth = realWidth / 4 * 3
                overlayHeight = realHeight / 3 * 2
            }

            allAppsView = LayoutInflater.from(context).inflate(R.layout.view_all_apps, FrameLayout(context), false)
            val layoutParams = WindowManager.LayoutParams().apply {
                width = overlayWidth
                height = overlayHeight
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.RGBA_8888
                gravity = Gravity.CENTER_VERTICAL
            }
            val recyclerView = allAppsView!!.findViewById<RecyclerView>(R.id.recyclerView)
            val lottieAnimationView = allAppsView!!.findViewById<LottieAnimationView>(R.id.lottieView)

            scope.launch(Dispatchers.IO) {
                allAppsList.clear()
                userManager.userProfiles.forEach {
                    allAppsList.addAll(launcherApps.getActivityList(null, it))
                }

                allAppsList.forEach {
                    appsPinyinMap[it.label.toString()] = Pinyin.toPinyin(it.label[0])
                }
                Collections.sort(allAppsList, PinyinComparable())

                withContext(Dispatchers.Main) {
                    lottieAnimationView.cancelAnimation()
                    lottieAnimationView.animate().alpha(0f).setDuration(300).start()

                    val adapter = AllAppsAdapter(
                        context,
                        allAppsList,
                        object : ClickListener() {
                            override fun onClick() {
                                try {
                                    windowManager.removeViewImmediate(allAppsView)
                                }catch (_: Exception) {}

                                removeCallback.onChooseAppWindowRemove()
                            }
                        }
                    )
                    recyclerView.layoutManager = GridLayoutManager(context, 3)
                    recyclerView.adapter = adapter

                    allAppsView!!.findViewById<WaveSideBarView>(R.id.waveSideBarView).setOnTouchLetterChangeListener {
                        val pos = adapter.getIndex(it)
                        if(pos != -1){
                            recyclerView.scrollToPosition(pos)
                            val layoutManager = recyclerView.layoutManager.cast<GridLayoutManager>()
                            layoutManager.scrollToPositionWithOffset(pos, 0)
                        }
                    }

                    //点击外部关闭悬浮窗
                    allAppsView!!.setOnClickListener {
                        try {
                            windowManager.removeViewImmediate(allAppsView)
                        } catch (_: Exception) {}
                        removeCallback.onChooseAppWindowRemove()
                    }
                }
            }

            windowManager.addView(allAppsView, layoutParams)
        } else {
            try {
                Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(
                    intent
                )
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun removeWindow() {
        try {
            windowManager.removeViewImmediate(floatingView)
        } catch (_: Exception) {}

        removeCallback.onChooseAppWindowRemove()
    }

    /**
     * 根据序号排序
     */
    class AppsComparable: Comparator<FreeFormAppsEntity> {
        override fun compare(o1: FreeFormAppsEntity?, o2: FreeFormAppsEntity?): Int {
            return o1!!.sortNum.compareTo(o2!!.sortNum)
        }
    }

    /**
     * 根据拼音排序
     */
    inner class PinyinComparable : Comparator<LauncherActivityInfo>{
        override fun compare(o1: LauncherActivityInfo?, o2: LauncherActivityInfo?): Int {
            return appsPinyinMap[o1!!.label]!!.compareTo(appsPinyinMap[o2!!.label]!!)
        }
    }

    /**
     * 当叠加层移除时回调
     */
    interface OnWindowRemoveCallback {
        fun onChooseAppWindowRemove()
    }

    /**
     * 悬浮窗点击监听器
     */
    abstract class ClickListener {
        open fun onClick() {}
    }
}