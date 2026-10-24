package io.relimus.zflow.ui.floating

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.github.promeg.pinyinhelper.Pinyin
import io.relimus.zflow.R
import io.relimus.zflow.systemapi.UserHandle
import io.relimus.zflow.service.FreeformRequestHandler

class AllAppsAdapter(
    private val context: Context,
    private val allAppsList: ArrayList<LauncherActivityInfo>,
    private val callback: ChooseAppFloatingView.ClickListener,
    // 列表构建时一次性取回的黑名单快照（"pkg|userId"）。
    // 点击回调在主线程，不能再去查 Room（MyDatabase 已移除 allowMainThreadQueries）。
    private val blacklistedKeys: Set<String> = emptySet()
) : RecyclerView.Adapter<AllAppsAdapter.ViewHolder>() {


    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = itemView.findViewById(R.id.imageView_icon)
        val click: View = itemView.findViewById(R.id.view_click)
        val appName: TextView = itemView.findViewById(R.id.textView_appName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_floating,
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int = allAppsList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val applicationInfo = allAppsList[position].applicationInfo
        val packageName = applicationInfo.packageName
        val activityName = allAppsList[position].name
        try {
            Glide.with(context)
                .load(applicationInfo.loadIcon(context.packageManager))
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(holder.icon)
            holder.appName.text = allAppsList[position].label
            holder.click.setOnClickListener {
                val userId = UserHandle.getUserId(allAppsList[position].user, applicationInfo.uid)

                // 黑名单判定走列表构建时取好的内存快照：
                // 点击回调在主线程，直接查 Room 会抛 "Cannot access database on the main thread"，
                // 旧实现在 catch 里返回 null 后被误判为「已拉黑」，导致侧边栏点任何应用都报黑名单。
                if ("$packageName|$userId" in blacklistedKeys) {
                    Toast.makeText(context, context.getString(R.string.app_blacklisted), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 直接转发给 system_server 的 FreeformManager：
                // 不再依赖 startService(FreeformService)，避免组件启动被后台限制拦截。
                // 必须异步：create() 内部要查 Room，主线程查库会抛异常并被 fail-closed 拒绝。
                FreeformRequestHandler.launchAsync(
                    context = context,
                    packageName = packageName,
                    activityName = activityName,
                    userId = userId
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
        } catch (_: PackageManager.NameNotFoundException) {}
    }

    fun getIndex(str: String?): Int {
        allAppsList.forEach {
            // 空标签的坏数据归入"#"桶，与 ChooseAppFloatingView 的拼音映射保持一致
            val letter = it.label.firstOrNull()?.let { c -> Pinyin.toPinyin(c).substring(0..0) } ?: "#"
            if (letter == str) {
                return allAppsList.indexOf(it)
            }
        }
        return -1
    }
}