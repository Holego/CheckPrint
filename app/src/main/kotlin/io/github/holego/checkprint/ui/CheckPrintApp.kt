package io.github.holego.checkprint.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.holego.checkprint.MainViewModel
import io.github.holego.checkprint.R

const val GITHUB_URL = "https://github.com/Holego/CheckPrint"

enum class Tab(val labelRes: Int, val icon: ImageVector) {
    SCAN(R.string.tab_scan, Icons.Filled.Wifi),
    PRINT(R.string.tab_print, Icons.Filled.Print),
    ABOUT(R.string.tab_about, Icons.Filled.Info),
}

@Composable
fun CheckPrintApp(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.SCAN) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.SCAN -> ScanScreen(vm) { ip, port ->
                    vm.selectTarget(ip, port)
                    tab = Tab.PRINT
                }
                Tab.PRINT -> PrintScreen(vm, snackbar)
                Tab.ABOUT -> AboutScreen()
            }
        }
    }
}
