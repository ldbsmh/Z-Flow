package io.relimus.zflow.ui.floating_apps_sort

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.relimus.zflow.R
import io.relimus.zflow.databinding.ActivityFloatingAppsSortBinding
import io.relimus.zflow.room.FreeFormAppsEntity
import io.relimus.zflow.utils.PackageUtils
import io.relimus.zflow.utils.cast
import java.util.Collections

/**
 * 悬浮窗应用排序。
 *
 * 排序模型：列表里的每一项就是一个 (packageName, userId)，sortNum 是这一项的
 * 位置序号，拖动结束后整体重排并写回。
 *
 * 修正点：旧实现拖动时交换两个实体的 sortNum 再调用两次 update ——
 * 先写会造成主键冲突（旧版 sortNum 同时是主键），冲突被静默吞掉后排序就“丢了”；
 * 现改为 Repository 里单事务整份写回，排序与列表写操作不再互相覆盖。
 * 现在改为一次事务批量写回，结果与界面上看到的一致。
 */
class FloatingAppsSortActivity : AppCompatActivity() {

    private lateinit var viewModel: FloatingAppsSortModel
    private lateinit var binding: ActivityFloatingAppsSortBinding

    private var firstInit = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFloatingAppsSortBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.label_sort_apps)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[FloatingAppsSortModel::class.java]

        val launcherApps: LauncherApps = getSystemService(LAUNCHER_APPS_SERVICE).cast()
        val userManager = getSystemService(USER_SERVICE).cast<UserManager>()
        val userHandleMap = HashMap<Int, UserHandle>()
        userManager.userProfiles.forEach {
            userHandleMap[io.relimus.zflow.systemapi.UserHandle.getUserId(it)] = it
        }

        viewModel.getAllApps().observe(this) { list ->
            val appsList = list.orEmpty().toMutableList().cast<ArrayList<FreeFormAppsEntity>>()

            if (firstInit) {
                firstInit = false
                val noInstallAppsList = appsList.filter {
                    !isInstalled(it, userHandleMap, launcherApps)
                }
                if (noInstallAppsList.isNotEmpty()) {
                    // 清理后 LiveData 会再次回调，那时再渲染列表
                    viewModel.deleteNotInstall(noInstallAppsList)
                    return@observe
                }
            }

            binding.progress.isIndeterminate = false
            binding.progress.visibility = View.GONE

            Collections.sort(appsList, AppsComparable())
            buildList(appsList)
        }
    }

    private fun isInstalled(
        entity: FreeFormAppsEntity,
        userHandleMap: Map<Int, UserHandle>,
        launcherApps: LauncherApps
    ): Boolean {
        return if (entity.userId == 0) {
            PackageUtils.hasInstallThisPackage(entity.packageName, packageManager)
        } else {
            val userHandle = userHandleMap[entity.userId] ?: userHandleMap[0] ?: return true
            PackageUtils.hasInstallThisPackageWithUserId(entity.packageName, launcherApps, userHandle)
        }
    }

    private fun buildList(list: List<FreeFormAppsEntity>) {
        val appsList = ArrayList(list)

        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = null
        binding.recyclerApps.adapter = AppsSortRecyclerAdapter(packageManager, appsList)

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, NO_SWIPE)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // 由适配器维护自己的数据源，这里只做位置交换
                (recyclerView.adapter as? AppsSortRecyclerAdapter)?.moveItem(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                ) ?: return false
                return true
            }

            override fun isLongPressDragEnabled(): Boolean = true

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 拖动结束后一次性写回整份顺序：
                // Repository 在单个事务内先校验快照、再整份写回新顺序。
                val adapter = recyclerView.adapter as? AppsSortRecyclerAdapter ?: return
                viewModel.saveOrder(adapter.currentList())
            }
        })

        itemTouchHelper.attachToRecyclerView(binding.recyclerApps)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val NO_SWIPE = 0
    }

    class AppsComparable : Comparator<FreeFormAppsEntity> {
        override fun compare(o1: FreeFormAppsEntity?, o2: FreeFormAppsEntity?): Int {
            return (o1?.sortNum ?: 0).compareTo(o2?.sortNum ?: 0)
        }
    }
}
