package io.relimus.zflow.ui.choose_apps

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class ChooseAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChooseAppsBinding
    private lateinit var viewModel: ChooseAppsViewModel

    private lateinit var pm: PackageManager
    private val allAppsList = ArrayList<LauncherActivityInfo>()
    private var appsList: ArrayList<*>? = null

    private lateinit var userManager: UserManager
    private lateinit var launcherApps: LauncherApps

    private var needToUpdateView = false
    private var firstObserver = true
    private var type = TYPE_FLOATING
    private var adapter: AppsRecyclerAdapter<*>? = null
    private val filter = AppNameFilter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[ChooseAppsViewModel::class.java]

        pm = packageManager
        userManager = getSystemService(USER_SERVICE).cast<UserManager>()
        launcherApps = getSystemService(LAUNCHER_APPS_SERVICE).cast<LauncherApps>()

        type = intent.getIntExtra("type", TYPE_FLOATING)
        viewModel.type = type

        when (type) {
            TYPE_FLOATING -> {
                supportActionBar!!.title = getString(R.string.label_floating_apps)
                viewModel.getAllApps().observe(this) { list ->
                    appsList = list.cast<ArrayList<*>>()
                    showAppsList(type)
                }
            }
            TYPE_BLACKLIST -> {
                supportActionBar!!.title = getString(R.string.label_blacklist_apps)
                viewModel.getAllBlacklistApps().observe(this) { list ->
                    appsList = list.cast<ArrayList<*>>()
                    showAppsList(type)
                }
            }
            TYPE_LANDSCAPE -> {
                supportActionBar!!.title = getString(R.string.label_landscape_apps)
                val landscapePkgs = viewModel.getAllLandscapeApps()
                appsList = ArrayList(landscapePkgs)
                showAppsList(type)
            }
            else -> {
                supportActionBar!!.title = getString(R.string.label_notification_apps)
                viewModel.getAllNotificationApps().observe(this) { list ->
                    appsList = list.cast<ArrayList<*>>()
                    showAppsList(type)
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun showAppsList(type: Int) {
        if (firstObserver || needToUpdateView) {
            GlobalScope.launch(Dispatchers.IO) {
                if (allAppsList.isEmpty()) {
                    userManager.userProfiles.forEach {
                        allAppsList.addAll(launcherApps.getActivityList(null, it))
                    }

                    allAppsList.sortWith { o1, o2 ->
                        Collator.getInstance().compare(
                            o1!!.applicationInfo.loadLabel(packageManager),
                            o2!!.applicationInfo.loadLabel(packageManager)
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.lottieView.cancelAnimation()
                    binding.lottieView.animate().alpha(0f).setDuration(300).start()

                    binding.recyclerApps.layoutManager = LinearLayoutManager(this@ChooseAppsActivity)
                    adapter = AppsRecyclerAdapter(allAppsList, viewModel, appsList!!, type, this@ChooseAppsActivity)
                    binding.recyclerApps.adapter = adapter

                    firstObserver = false
                    needToUpdateView = false
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_choose_apps, menu)
        val searchItem = menu.findItem(R.id.app_bar_search)
        val searchView = searchItem?.actionView.cast<SearchView>()
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean = false

            override fun onQueryTextChange(newText: String): Boolean {
                filter.filter(newText)
                return true
            }
        })

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.choose_all -> {
                needToUpdateView = true
                viewModel.insertAllApps(allAppsList, userManager)
            }
            R.id.choose_all_cancel -> {
                if (appsList != null && appsList!!.isNotEmpty()) {
                    needToUpdateView = true
                    viewModel.deleteAll()
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    inner class AppNameFilter : Filter() {
        override fun performFiltering(constraint: CharSequence): FilterResults {
            val results = FilterResults()
            var newAllAppsList = ArrayList<LauncherActivityInfo>()

            if (constraint.isBlank()) {
                newAllAppsList = allAppsList
            } else {
                allAppsList.forEach {
                    if (it.label.contains(constraint, ignoreCase = true)) {
                        newAllAppsList.add(it)
                    }
                }
            }

            results.values = newAllAppsList
            results.count = newAllAppsList.size
            return results
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun publishResults(constraint: CharSequence, results: FilterResults) {
            adapter?.updateDate(results.values.cast<ArrayList<LauncherActivityInfo>>())
        }
    }

    companion object {
        const val TYPE_FLOATING = 1
        const val TYPE_NOTIFICATION = 2
        const val TYPE_BLACKLIST = 3
        const val TYPE_LANDSCAPE = 4
    }
}