package io.relimus.zflow.ui.floating_apps_sort

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import io.relimus.zflow.room.DatabaseRepository
import io.relimus.zflow.room.FreeFormAppsEntity
import kotlinx.coroutines.launch

/**
 * @author sunshine
 * @date 2021/3/7
 */
class FloatingAppsSortModel(application: Application) : AndroidViewModel(application) {
    private val repository = DatabaseRepository(application)

    fun getAllApps(): LiveData<List<FreeFormAppsEntity>?> {
        return repository.getAllFreeForm()
    }

    /**
     * 把拖动后的顺序整份写回。
     * [DatabaseRepository.updateFreeFormSort] 在同一事务里完成，顺序与界面一致。
     */
    fun saveOrder(list: List<FreeFormAppsEntity>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            repository.updateFreeFormSort(list)
        }
    }

    fun deleteNotInstall(notInstallList: List<FreeFormAppsEntity>) {
        if (notInstallList.isEmpty()) return
        viewModelScope.launch {
            repository.deleteMore(notInstallList)
        }
    }
}
