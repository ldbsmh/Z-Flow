package io.relimus.zflow.ui.choose_apps

import android.annotation.SuppressLint
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
import io.relimus.zflow.systemapi.UserHandle

/**
 * 应用选择列表。
 *
 * 勾选状态以 `packageName|userId` 为键维护在本地 [checkedKeys] 中：
 * 点击时直接改集合并只刷新受影响的两行，不再整表 notifyDataSetChanged。
 */
class AppsRecyclerAdapter(
    private var allAppsList: ArrayList<LauncherActivityInfo>,
    private val viewModel: ChooseAppsViewModel,
    selectedKeys: List<String>,
    private val type: Int
) : RecyclerView.Adapter<AppsRecyclerAdapter.ViewHolder>() {

    /** 已选置顶 + 过滤后的展示列表。 */
    private var items: MutableList<LauncherActivityInfo> = ArrayList(allAppsList)

    private val checkedKeys: MutableSet<String> = selectedKeys.toMutableSet()

    private fun keyOf(packageName: String, userId: Int) = "$packageName|$userId"

    private fun keyOf(info: LauncherActivityInfo): String {
        val packageName = info.applicationInfo.packageName
        val userId = if (type == ChooseAppsViewModel.TYPE_LANDSCAPE) {
            -1
        } else {
            UserHandle.getUserId(info.user, info.applicationInfo.uid)
        }
        return keyOf(packageName, userId)
    }

    /**
     * 搜索过滤结果。
     * 注意：filterAllAppsList 是“已选置顶”后的视图，过滤应该作用在它上面
     * 才能保证搜索时仍保持置顶顺序，这里直接用传入的完整列表重建。
     */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submitFilter(filtered: List<LauncherActivityInfo>) {
        allAppsList = ArrayList(filtered)
        sortCheckedToTop()
        notifyDataSetChanged()
    }

    /** 供全选/全不选使用的完整数据源。 */
    fun allApps(): List<LauncherActivityInfo> = allAppsList

    /**
     * 勾选状态变化（全选 / 全不选 / LiveData 回流）时刷新。
     * keys 已经带上 userId，直接使用。
     */
    fun updateChecked(keys: List<String>) {
        checkedKeys.clear()
        checkedKeys.addAll(keys)
        sortCheckedToTop()
        notifyItemRangeChanged(0, items.size, PAYLOAD_CHECKED)
    }

    private fun sortCheckedToTop() {
        items = if (checkedKeys.isEmpty()) {
            ArrayList(allAppsList)
        } else {
            val checked = ArrayList<LauncherActivityInfo>()
            val unchecked = ArrayList<LauncherActivityInfo>()
            allAppsList.forEach { if (keyOf(it) in checkedKeys) checked.add(it) else unchecked.add(it) }
            checked.addAll(unchecked)
            checked
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val view: View = itemView.findViewById(R.id.touch_root)
        val icon: ImageView = itemView.findViewById(R.id.image_icon)
        val name: AppCompatTextView = itemView.findViewById(R.id.text_title)
        val packageName: AppCompatTextView = itemView.findViewById(R.id.text_summary)
        val switch: SwitchCompat = itemView.findViewById(R.id.switch_app)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_app2, parent, false)
        )
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bind(holder, items[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECKED)) {
            holder.switch.isChecked = keyOf(items[position]) in checkedKeys
            return
        }
        onBindViewHolder(holder, position)
    }

    @SuppressLint("SetTextI18n")
    private fun bind(holder: ViewHolder, info: LauncherActivityInfo) {
        val appInfo = info.applicationInfo
        holder.name.text = info.label?.toString() ?: appInfo.packageName
        holder.packageName.text = appInfo.packageName
        Glide.with(holder.icon)
            .load(info.getBadgedIcon(0))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.icon)

        val key = keyOf(info)
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = key in checkedKeys

        holder.itemView.setOnClickListener { toggle(key, holder, info) }
        holder.switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == (key in checkedKeys)) return@setOnCheckedChangeListener
            toggle(key, holder, info)
        }
    }

    /** 单点切换：本地集合即时更新（开关控件已自带动画），再交给 ViewModel 落库。 */
    private fun toggle(key: String, holder: ViewHolder, info: LauncherActivityInfo) {
        val checked = key !in checkedKeys
        if (checked) checkedKeys.add(key) else checkedKeys.remove(key)
        // 不重建列表，保持滚动位置；下一次全量刷新时再置顶
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = checked
        viewModel.toggleApp(
            packageName = info.applicationInfo.packageName,
            userId = if (type == ChooseAppsViewModel.TYPE_LANDSCAPE) -1
            else UserHandle.getUserId(info.user, info.applicationInfo.uid),
            checked = checked
        )
    }

    private companion object {
        const val PAYLOAD_CHECKED = "payload:checked"
    }
}
