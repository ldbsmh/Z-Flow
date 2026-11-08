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
 * 点击时直接改集合；勾选集合变化导致顺序变化时才整表重绑，否则只刷开关。
 */
class AppsRecyclerAdapter(
    private var allAppsList: ArrayList<LauncherActivityInfo>,
    private val viewModel: ChooseAppsViewModel,
    selectedKeys: List<String>,
    private val type: Int
) : RecyclerView.Adapter<AppsRecyclerAdapter.ViewHolder>() {

    private val checkedKeys: MutableSet<String> = selectedKeys.toMutableSet()

    /** 已选置顶 + 过滤后的展示列表。首次进入就要置顶，所以构造时先排一次。 */
    private var items: MutableList<LauncherActivityInfo> = ArrayList(allAppsList)

    init {
        sortCheckedToTop()
    }

    private fun keyOf(packageName: String, userId: Int) = "$packageName|$userId"

    /**
     * 名单是否只按包名、不分用户：横屏与白名单都是这种。
     * 对应的选择态在 ViewModel 里也按 -1 组装（LANDSCAPE_USER / TRUSTED_USER），
     * 两边必须一致，否则首屏勾选态完全对不上。
     */
    private fun isUserAgnostic(): Boolean =
        type == ChooseAppsViewModel.TYPE_LANDSCAPE || type == ChooseAppsViewModel.TYPE_TRUSTED

    private fun userIdOf(info: LauncherActivityInfo): Int = if (isUserAgnostic()) {
        NO_USER
    } else {
        UserHandle.getUserId(info.user, info.applicationInfo.uid)
    }

    private fun keyOf(info: LauncherActivityInfo): String =
        keyOf(info.applicationInfo.packageName, userIdOf(info))

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
        // 集合没变（例如自己刚点过、回流的是同一批 key）就只刷开关，
        // 避免无意义的重绑把滚动位置顶掉。
        if (checkedKeys.size == keys.size && checkedKeys.containsAll(keys)) {
            notifyItemRangeChanged(0, items.size, PAYLOAD_CHECKED)
            return
        }
        checkedKeys.clear()
        checkedKeys.addAll(keys)
        resortAndNotify()
    }

    /**
     * 全选 / 全不选后同步本地勾选态。
     * 悬浮窗 / 通知 / 黑名单的勾选来自 Room 回流，交给 [updateChecked]；
     * 横屏与白名单没有回流通道，只能在这里补齐，否则列表既不显示勾选也不置顶。
     */
    fun syncAllChecked(checked: Boolean) {
        if (hasSelectionStream()) return
        checkedKeys.clear()
        if (checked) allAppsList.forEach { checkedKeys.add(keyOf(it)) }
        resortAndNotify()
    }

    /**
     * 该类型的勾选状态是否有数据库回流。
     * 只有经过 Room 的三种类型会在写入后收到 [updateChecked]；
     * 横屏（Provider/SharedPreferences）与白名单（本地文件）没有，需要本地自行重排。
     */
    private fun hasSelectionStream(): Boolean = when (type) {
        ChooseAppsViewModel.TYPE_LANDSCAPE, ChooseAppsViewModel.TYPE_TRUSTED -> false
        else -> true
    }

    /**
     * 重排并刷新。
     * 顺序真的变了就必须整表重绑：带 payload 的局部刷新只改开关，
     * 位置交换之后行内的图标 / 名称还停在上一行的数据上。
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun resortAndNotify() {
        val previous = items
        sortCheckedToTop()
        val moved = previous.size != items.size ||
            previous.indices.any { previous[it] !== items[it] }
        if (moved) notifyDataSetChanged()
        else notifyItemRangeChanged(0, items.size, PAYLOAD_CHECKED)
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

        holder.itemView.setOnClickListener { toggle(key, info) }
        holder.switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == (key in checkedKeys)) return@setOnCheckedChangeListener
            toggle(key, info)
        }
    }

    /** 单点切换：本地集合即时更新（开关控件已自带动画），再交给 ViewModel 落库。 */
    private fun toggle(key: String, info: LauncherActivityInfo) {
        val checked = key !in checkedKeys
        if (checked) checkedKeys.add(key) else checkedKeys.remove(key)
        // 有回流的类型等数据回来再排（保持现有手感）；横屏 / 白名单没有回流，
        // 不在这里自己排就永远不会置顶——这就是横屏列表勾选后不动的原因。
        if (!hasSelectionStream()) resortAndNotify()
        viewModel.toggleApp(
            packageName = info.applicationInfo.packageName,
            userId = userIdOf(info),
            checked = checked
        )
    }

    private companion object {
        const val PAYLOAD_CHECKED = "payload:checked"

        /** 不分用户的名单（横屏 / 白名单）在键里统一用的 userId。 */
        const val NO_USER = -1
    }
}
