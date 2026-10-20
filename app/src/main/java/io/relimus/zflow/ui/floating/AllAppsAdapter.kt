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
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.systemapi.UserHandle
import io.relimus.zflow.service.FreeformRequestHandler

class AllAppsAdapter(
    private val context: Context,
    private val allAppsList: ArrayList<LauncherActivityInfo>,
    private val callback: ChooseAppFloatingView.ClickListener
) : RecyclerView.Adapter<AllAppsAdapter.ViewHolder>() {

    private val repository = DatabaseRepository(context)

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

                // null = 查询失败，按黑名单处理（宁可不启动）
                if (repository.isBlacklisted(packageName, userId) != false) {
                    Toast.makeText(context, context.getString(R.string.app_blacklisted), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // 直接转发给 system_server 的 FreeformManager：
                // 不再依赖 startService(FreeformService)，避免组件启动被后台限制拦截。
                val launched = FreeformRequestHandler.launch(
                    context = context,
                    packageName = packageName,
                    activityName = activityName,
                    userId = userId
                )
                if (!launched) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.freeform_start_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                callback.onClick()
            }
        } catch (_: PackageManager.NameNotFoundException) {}
    }

    fun getIndex(str: String?): Int {
        allAppsList.forEach {
            if (Pinyin.toPinyin(it.label[0]).substring(0..0) == str) {
                return allAppsList.indexOf(it)
            }
        }
        return -1
    }
}