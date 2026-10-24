package io.relimus.zflow.ui.choose_apps

import android.annotation.SuppressLint
import android.content.pm.LauncherActivityInfo
import android.os.Bundle
import android.os.UserManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Filter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import io.relimus.zflow.R
import io.relimus.zflow.databinding.ActivityChooseAppsBinding
import io.relimus.zflow.utils.cast

/**
 * 应用选择页。
 *
 * 生命周期约定：应用列表的加载与缓存都在 [ChooseAppsViewModel] 中完成，
 * Activity 只负责把状态渲染到当前仍然有效的 ViewBinding 上，
 * 因此旋转屏幕、快速退出都不会再出现“后台任务回调旧 Activity”的问题。
 */
class ChooseAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChooseAppsBinding
    private lateinit var viewModel: ChooseAppsViewModel
    private lateinit var userManager: UserManager

    private var adapter: AppsRecyclerAdapter? = null
    private val filter = AppNameFilter()

    /** 最近一次渲染用的应用列表，供搜索过滤与全选使用。 */
    private var renderedApps: ArrayList<LauncherActivityInfo> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[ChooseAppsViewModel::class.java]
        userManager = getSystemService(USER_SERVICE).cast<UserManager>()

        val type = intent.getIntExtra("type", ChooseAppsViewModel.TYPE_FLOATING)
        viewModel.type = type

        supportActionBar?.title = getString(
            when (type) {
                ChooseAppsViewModel.TYPE_BLACKLIST -> R.string.label_blacklist_apps
                ChooseAppsViewModel.TYPE_LANDSCAPE -> R.string.label_landscape_apps
                ChooseAppsViewModel.TYPE_NOTIFICATION -> R.string.label_notification_apps
                ChooseAppsViewModel.TYPE_TRUSTED -> R.string.label_trusted_callers
                else -> R.string.label_floating_apps
            }
        )

        viewModel.state.observe(this) { state ->
            when (state) {
                is ChooseAppsViewModel.LoadState.Loading -> Unit
                is ChooseAppsViewModel.LoadState.Failed -> hideLoading()
                is ChooseAppsViewModel.LoadState.Success -> render(type, state)
            }
        }

        // 已有缓存时直接复用，只有首次进入才真正查询
        viewModel.loadApps(userManager)
        observeSelectedList(type)
    }

    /** 悬浮窗 / 通知 / 黑名单：数据库里的勾选状态变化后增量刷新列表。 */
    private fun observeSelectedList(type: Int) {
        when (type) {
            ChooseAppsViewModel.TYPE_FLOATING ->
                viewModel.getAllApps().observe(this) { list ->
                    adapter?.updateChecked(list.orEmpty().map { "${it.packageName}|${it.userId}" })
                }
            ChooseAppsViewModel.TYPE_NOTIFICATION ->
                viewModel.getAllNotificationApps().observe(this) { list ->
                    adapter?.updateChecked(list.orEmpty().map { "${it.packageName}|${it.userId}" })
                }
            ChooseAppsViewModel.TYPE_BLACKLIST ->
                viewModel.getAllBlacklistApps().observe(this) { list ->
                    adapter?.updateChecked(list.orEmpty().map { "${it.packageName}|${it.userId}" })
                }
            else -> Unit
        }
    }

    private fun render(type: Int, state: ChooseAppsViewModel.LoadState.Success) {
        hideLoading()

        if (adapter != null && renderedApps.size == state.allApps.size) {
            // 同一份数据（例如旋转屏幕后重建）：只更新勾选状态，不重建列表
            adapter?.updateChecked(state.selected)
            return
        }

        renderedApps = ArrayList(state.allApps)
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        adapter = AppsRecyclerAdapter(renderedApps, viewModel, state.selected, type)
        binding.recyclerApps.adapter = adapter
    }

    private fun hideLoading() {
        binding.lottieView.cancelAnimation()
        binding.lottieView.animate().alpha(0f).setDuration(300).start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_choose_apps, menu)
        menu.findItem(R.id.app_bar_search)?.actionView.cast<SearchView>()?.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean = false

                override fun onQueryTextChange(newText: String): Boolean {
                    filter.filter(newText)
                    return true
                }
            }
        )
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.choose_all -> viewModel.insertAllApps(renderedApps)
            R.id.choose_all_cancel -> viewModel.deleteAll()
        }
        return super.onOptionsItemSelected(item)
    }

    inner class AppNameFilter : Filter() {
        override fun performFiltering(constraint: CharSequence): FilterResults {
            val results = FilterResults()
            val source = renderedApps
            results.values = if (constraint.isBlank()) {
                ArrayList(source)
            } else {
                ArrayList(source.filter { it.label.contains(constraint, ignoreCase = true) })
            }
            results.count = (results.values as ArrayList<*>).size
            return results
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun publishResults(constraint: CharSequence, results: FilterResults) {
            adapter?.submitFilter(results.values.cast<ArrayList<LauncherActivityInfo>>())
        }
    }
}
