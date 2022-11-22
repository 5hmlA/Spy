package osp.sparkj.spy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
import android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import osp.dfj.vcr.Spy
import osp.dfj.vcr.StorageConfig
import osp.dfj.vcr.VideoConfig
import osp.dfj.vcr.open
import osp.sparkj.spy.databinding.ActivityMainBinding
import java.io.File
import kotlin.math.log

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    var recording = false
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.e("Spy","======== ${Environment.isExternalStorageManager()}")
            if (!Environment.isExternalStorageManager()) {
//                Android 11 引入了 MANAGE_EXTERNAL_STORAGE 权限，该权限提供对应用专属目录和 MediaStore 之外文件的写入权限。如需详细了解此权限，以及为何大多数应用无需声明此权限即可实现其用例，请参阅有关如何管理存储设备上所有文件的指南。
                startActivity(Intent(ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))

            }
        }

        val spy = Spy.monitor(this)

        binding.fab.setOnClickListener { view ->
            recording(spy)

            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAnchorView(R.id.fab)
                .setAction("Action", null).show()
        }
    }

    private fun recording(spy: Spy) {
        if (!recording) {
            spy.record()
        } else {
            lifecycleScope.launch {
                spy.stop(true)?.open(this@MainActivity)
            }
        }
        recording = !recording
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}