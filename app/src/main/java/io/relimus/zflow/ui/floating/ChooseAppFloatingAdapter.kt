package io.relimus.zflow.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.UserHandle
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import io.relimus.zflow.R
import io.relimus.zflow.ui.floating_apps_sort.FloatingAppsSortActivity
import io.relimus.zflow.ui.choose_apps.ChooseAppsActivity
import io.relimus.zflow.room.FreeFormAppsEntity
import io.relimus.zflow.utils.cast
import io.relimus.zflow.service.FreeformRequestHandler

/**
 * @author sunshine
 * @date 2021/3/7
 * 选择应用侧边栏适配器
 */
class ChooseAppFloatingAdapter(
    private val context: Context,
    private val apps: List<FreeFormAppsEntity>?,
    private val callback: ChooseAppFloatingView.ClickListener,
    private val allAppsCallback: ChooseAppFloatingView.ClickListener
) : RecyclerView.Adapter<ChooseAppFloatingAdapter.ViewHolder>() {

    private var resolveInfoList: MutableList<ResolveInfo>
    private lateinit var launcherApps: LauncherApps
    private val userHandleMap = HashMap<Int, UserHandle>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = itemView.findViewById(R.id.imageView_icon)
        val click: View = itemView.findViewById(R.id.view_click)
        val appName: TextView = itemView.findViewById(R.id.textView_appName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val userManager = context.getSystemService(Context.USER_SERVICE).cast<UserManager>()
        launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE).cast()

        userManager.userProfiles.forEach {
            userHandleMap[io.relimus.zflow.systemapi.UserHandle.getUserId(it)] = it
        }

        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_floating,
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int {
        //2021.07.25更新，新增展示最小化的应用
        return apps?.size?.plus(2)!!
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        //2021.07.15优化了选择应用界面的样式，增加了应用名称显示，所有应用移动到了最上面，新增编辑应用
        when (position) {
            0 -> {
                holder.icon.setImageResource(R.drawable.ic_all)
                holder.appName.text = context.getString(R.string.all_apps)
                holder.click.setOnClickListener {
                    allAppsCallback.onClick()
                }
            }

            apps!!.size + 1 -> {
                holder.icon.setImageResource(R.drawable.ic_add)
                holder.appName.text = context.getString(R.string.edit_apps)
                holder.click.setOnClickListener {
                    context.startActivity(Intent(context, ChooseAppsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    callback.onClick()
                }
            }
            else -> {
                val packageName = apps[position - 1].packageName
                try {
                    //val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    val userHandle = if (userHandleMap.containsKey(apps[position - 1].userId)) userHandleMap[apps[position - 1].userId]!! else userHandleMap[0]!!
                    val appInfo = launcherApps.getApplicationInfo(apps[position - 1].packageName, 0, userHandle)
                    var activityName = ""
                    resolveInfoList.forEach {
                        it.activityInfo
                        if (it.activityInfo.applicationInfo.packageName == packageName) {
                            activityName = it.activityInfo.name
                        }
                    }
                    Glide.with(context)
                        .load(appInfo.loadIcon(context.packageManager))
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .into(holder.icon)
                    holder.appName.text = getLabel(appInfo, apps[position - 1].userId)
                    holder.click.setOnClickListener {
                        // 直接转发到 system_server，跳过 FreeformService 组件启动。
                        // 走异步入口：create() 内部查 Room，主线程查库会失败并被拒绝。
                        FreeformRequestHandler.launchAsync(
                            context = context,
                            packageName = packageName,
                            activityName = activityName,
                            userId = apps[position - 1].userId
                        ) { launched ->
                            if (launched) {
                                callback.onClick()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.freeform_start_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    //长按进入排序界面
                    holder.click.setOnLongClickListener {
                        // 长按只负责打开排序页并收起悬浮窗，
                        // 原本这里还会 startService 把自己当小窗内容打开（无意义且会失败）。
                        context.startActivity(Intent(context, FloatingAppsSortActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        callback.onClick()
                        true
                    }
                } catch (_: PackageManager.NameNotFoundException) {}
            }
        }

    }

    private fun getLabel(applicationInfo: ApplicationInfo, userId: Int): CharSequence {
        return if (userId == 0) {
            context.packageManager.getApplicationLabel(applicationInfo)
        } else {
            "${context.packageManager.getApplicationLabel(applicationInfo)}-${context.getString(R.string.fenshen)}${userId}"
        }
    }

    init {
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        resolveInfoList = context.packageManager.queryIntentActivities(intent, 0)
    }
}