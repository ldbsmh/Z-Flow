package io.relimus.zflow.ui.floating_apps_sort

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import io.relimus.zflow.R
import io.relimus.zflow.room.FreeFormAppsEntity
import io.relimus.zflow.utils.cast

/**
 * 小窗应用排序列表。
 *
 * 数据源由适配器自己持有（[moveItem] 只动内部列表），
 * 拖动结束后由 Activity 调用 [currentList] 一次性落库。
 */
class AppsSortRecyclerAdapter(
    private val pm: PackageManager,
    appsList: ArrayList<FreeFormAppsEntity>
) : RecyclerView.Adapter<AppsSortRecyclerAdapter.ViewHolder>() {

    private val items = ArrayList(appsList)

    private var context: Context? = null
    private var launcherApps: LauncherApps? = null

    /** 每次重建列表时刷新：拖动结束后以这里的数据（含新序号）写库。 */
    private val userHandleMap = HashMap<Int, UserHandle>()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val view: View = itemView.findViewById(R.id.view_app_click)
        val icon: ImageView = itemView.findViewById(R.id.imageView_app_icon)
        val name: AppCompatTextView = itemView.findViewById(R.id.textView_app_name)
        val switch: SwitchCompat = itemView.findViewById(R.id.switch_app)
    }

    fun currentList(): List<FreeFormAppsEntity> = items


    /** 交换两项位置；失败（位置越界）返回 false。 */
    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        context = ctx
        launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE).cast()
        val userManager = ctx.getSystemService(Context.USER_SERVICE).cast<UserManager>()
        userHandleMap.clear()
        userManager.userProfiles.forEach {
            userHandleMap[io.relimus.zflow.systemapi.UserHandle.getUserId(it)] = it
        }
        return ViewHolder(LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx = context ?: return
        val entity = items[position]
        val userHandle = userHandleMap[entity.userId] ?: userHandleMap[0] ?: return

        val applicationInfo = try {
            launcherApps?.getApplicationInfo(entity.packageName, 0, userHandle)
        } catch (e: Exception) {
            null
        }

        if (applicationInfo == null) {
            // 应用可能刚被卸载：显示包名，避免整个列表绑定崩溃
            holder.name.text = entity.packageName
            holder.switch.isChecked = false
            return
        }

        Glide.with(ctx)
            .load(applicationInfo.loadIcon(ctx.packageManager))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.icon)

        holder.name.text = if (entity.userId == 0) {
            applicationInfo.loadLabel(ctx.packageManager)
        } else {
            "${applicationInfo.loadLabel(ctx.packageManager)}-分身${entity.userId}"
        }
        holder.switch.isChecked = true
    }
}
