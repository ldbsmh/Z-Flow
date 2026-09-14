package io.relimus.zflow.ui.choose_apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherActivityInfo
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
import io.relimus.zflow.room.FreeformBlacklistEntity
import io.relimus.zflow.room.NotificationAppsEntity
import io.relimus.zflow.systemapi.UserHandle
import io.relimus.zflow.utils.cast

class AppsRecyclerAdapter<T>(
    private var allAppsList: ArrayList<LauncherActivityInfo>,
    private val viewModel: ChooseAppsViewModel,
    private val appsList: ArrayList<T>,
    private val type: Int,
    private val context: Context
) : RecyclerView.Adapter<AppsRecyclerAdapter.ViewHolder>() {

    private var filterAllAppsList = allAppsList
    private var checkedPackages: Set<String> = emptySet()

    init {
        // 初始化时根据已勾选列表构建置顶包名集合
        checkedPackages = collectCheckedPackages()
        sortCheckedToTop()
    }

    /**
     * 根据 type 从 appsList 中提取已勾选应用的包名集合。
     */
    private fun collectCheckedPackages(): Set<String> {
        return when (type) {
            ChooseAppsActivity.TYPE_FLOATING -> appsList.mapNotNull { (it as? FreeFormAppsEntity)?.packageName }.toSet()
            ChooseAppsActivity.TYPE_NOTIFICATION -> appsList.mapNotNull { (it as? NotificationAppsEntity)?.packageName }.toSet()
            ChooseAppsActivity.TYPE_BLACKLIST -> appsList.mapNotNull { (it as? FreeformBlacklistEntity)?.packageName }.toSet()
            ChooseAppsActivity.TYPE_LANDSCAPE -> appsList.mapNotNull { it as? String }.toSet()
            else -> emptySet()
        }
    }

    /**
     * 把已勾选的应用（在 checkedPackages 中）移到列表前面，
     * 保持它们之间的相对顺序不变，未勾选的维持原顺序在后。
     */
    private fun sortCheckedToTop() {
        if (checkedPackages.isEmpty()) return
        val checked = filterAllAppsList.filter { it.applicationInfo.packageName in checkedPackages }
        val unchecked = filterAllAppsList.filter { it.applicationInfo.packageName !in checkedPackages }
        filterAllAppsList = ArrayList(checked + unchecked)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val view: View = itemView.findViewById(R.id.touch_root)
        val icon: ImageView = itemView.findViewById(R.id.image_icon)
        val name: AppCompatTextView = itemView.findViewById(R.id.text_title)
        val packageName: AppCompatTextView = itemView.findViewById(R.id.text_summary)
        val switch: SwitchCompat = itemView.findViewById(R.id.switch_app)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app2, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filterAllAppsList[position]
        val userId = UserHandle.getUserId(item.user, item.applicationInfo.uid)
        val packageName = item.applicationInfo.packageName

        Glide.with(context)
            .load(item.applicationInfo.loadIcon(context.packageManager))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.icon)

        holder.name.text = if (userId == 0) item.label else "${item.label}-分身${userId}"
        holder.packageName.text = packageName

        holder.switch.isChecked = when (type) {
            ChooseAppsActivity.TYPE_FLOATING -> contains(FreeFormAppsEntity(-1, packageName, userId))
            ChooseAppsActivity.TYPE_NOTIFICATION -> contains(NotificationAppsEntity(packageName, userId))
            ChooseAppsActivity.TYPE_BLACKLIST -> contains(FreeformBlacklistEntity(packageName, userId))
            ChooseAppsActivity.TYPE_LANDSCAPE -> containsLandscape(packageName)
            else -> false
        }

        holder.view.setOnClickListener {
            holder.switch.isChecked = !holder.switch.isChecked
            if (holder.switch.isChecked) {
                insert(position)
            } else {
                delete(position)
            }
        }
    }

    override fun getItemCount(): Int = filterAllAppsList.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateDate(newAllAppsList: ArrayList<LauncherActivityInfo>) {
        filterAllAppsList = newAllAppsList
        // 搜索过滤后仍保持已勾选应用置顶
        if (checkedPackages.isNotEmpty()) {
            val checked = filterAllAppsList.filter { it.applicationInfo.packageName in checkedPackages }
            val unchecked = filterAllAppsList.filter { it.applicationInfo.packageName !in checkedPackages }
            filterAllAppsList = ArrayList(checked + unchecked)
        }
        notifyDataSetChanged()
    }

    private fun insert(position: Int) {
        val packageName = filterAllAppsList[position].applicationInfo.packageName
        val userId = UserHandle.getUserId(filterAllAppsList[position].user, filterAllAppsList[position].applicationInfo.uid)
        viewModel.insertApps(packageName, userId)

        when (type) {
            ChooseAppsActivity.TYPE_FLOATING -> {
                appsList.add(FreeFormAppsEntity(-1, packageName, userId).cast())
            }
            ChooseAppsActivity.TYPE_NOTIFICATION -> {
                appsList.add(NotificationAppsEntity(packageName, userId).cast())
            }
            ChooseAppsActivity.TYPE_BLACKLIST -> {
                appsList.add(FreeformBlacklistEntity(packageName, userId).cast())
            }
            ChooseAppsActivity.TYPE_LANDSCAPE -> {
                appsList.add(packageName.cast())
            }
        }
    }

    private fun delete(position: Int) {
        val packageName = filterAllAppsList[position].applicationInfo.packageName
        val userId = UserHandle.getUserId(filterAllAppsList[position].user, filterAllAppsList[position].applicationInfo.uid)
        viewModel.deleteApps(packageName, userId)

        when (type) {
            ChooseAppsActivity.TYPE_FLOATING -> remove(FreeFormAppsEntity(-1, packageName, userId))
            ChooseAppsActivity.TYPE_NOTIFICATION -> remove(NotificationAppsEntity(packageName, userId))
            ChooseAppsActivity.TYPE_BLACKLIST -> remove(FreeformBlacklistEntity(packageName, userId))
            ChooseAppsActivity.TYPE_LANDSCAPE -> removeLandscape(packageName)
        }
    }

    // ===== Entity-based contains (原有) =====

    private fun contains(item: FreeFormAppsEntity): Boolean {
        appsList.forEach {
            val castItem = it.cast<FreeFormAppsEntity>()
            if (castItem.userId == item.userId && castItem.packageName == item.packageName) return true
        }
        return false
    }

    private fun contains(item: NotificationAppsEntity): Boolean {
        appsList.forEach {
            val castItem = it.cast<NotificationAppsEntity>()
            if (castItem.userId == item.userId && castItem.packageName == item.packageName) return true
        }
        return false
    }

    private fun contains(item: FreeformBlacklistEntity): Boolean {
        appsList.forEach {
            val castItem = it.cast<FreeformBlacklistEntity>()
            if (castItem.userId == item.userId && castItem.packageName == item.packageName) return true
        }
        return false
    }

    // ===== Landscape (String-based) =====

    private fun containsLandscape(packageName: String): Boolean {
        appsList.forEach {
            if (it as String == packageName) return true
        }
        return false
    }

    private fun removeLandscape(packageName: String) {
        appsList.remove(packageName.cast())
    }

    // ===== Entity-based remove (原有) =====

    private fun remove(item: FreeFormAppsEntity) {
        var toBeRemovedItem: T? = null
        appsList.forEach {
            val castItem = it.cast<FreeFormAppsEntity>()
            if (castItem.userId == item.userId && castItem.packageName == item.packageName) {
                toBeRemovedItem = it
                return@forEach
            }
        }
        appsList.remove(toBeRemovedItem)
    }

    private fun remove(item: NotificationAppsEntity) {
        var toBeRemovedItem: T? = null
        appsList.forEach {
            val castItem = it.cast<NotificationAppsEntity>()
            if (castItem.userId == item.userId && castItem.packageName == item.packageName) {
                toBeRemovedItem = it
                return@forEach
            }
        }
        appsList.remove(toBeRemovedItem)
    }

    private fun remove(item: FreeformBlacklistEntity) {
        var toBeRemovedItem: T? = null
        appsList.forEach {
            val castItem = it.cast<FreeformBlacklistEntity>()
            if (castItem.userId == item.userId && castItem.packageName == item.packageName) {
                toBeRemovedItem = it
                return@forEach
            }
        }
        appsList.remove(toBeRemovedItem)
    }
}