package com.davidvancl.espcontrol

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.davidvancl.espcontrol.ui.device.DeviceScreen
import com.davidvancl.espcontrol.ui.device.DeviceViewModel
import com.davidvancl.espcontrol.ui.list.DeviceListScreen
import com.davidvancl.espcontrol.ui.list.DeviceListViewModel
import com.davidvancl.espcontrol.ui.list.SettingsScreen
import com.davidvancl.espcontrol.ui.theme.ESPControlTheme
import kotlinx.serialization.Serializable

@Serializable
private object DeviceListRoute

@Serializable
private object SettingsRoute

@Serializable
private data class DeviceRoute(val id: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as EspControlApp).repository
        setContent {
            ESPControlTheme {
                val navController = rememberNavController()
                val localNetwork = rememberLocalNetworkPermission()
                val listViewModel: DeviceListViewModel = viewModel(
                    factory = viewModelFactory { initializer { DeviceListViewModel(repository) } },
                )

                // Discovery runs while the app is in the foreground, on every screen.
                LifecycleStartEffect(listViewModel, localNetwork) {
                    if (localNetwork == true) listViewModel.startDiscovery()
                    onStopOrDispose { listViewModel.stopDiscovery() }
                }

                NavHost(navController, startDestination = DeviceListRoute) {
                    composable<DeviceListRoute> {
                        DeviceListScreen(
                            viewModel = listViewModel,
                            localNetworkDenied = localNetwork == false,
                            onOpen = { navController.navigate(DeviceRoute(it)) },
                            onSettings = { navController.navigate(SettingsRoute) },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(listViewModel, onBack = { navController.popBackStack() })
                    }
                    composable<DeviceRoute> { entry ->
                        val id = entry.toRoute<DeviceRoute>().id
                        val deviceViewModel: DeviceViewModel = viewModel(
                            factory = viewModelFactory { initializer { DeviceViewModel(repository, id) } },
                        )
                        DeviceScreen(deviceViewModel, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

/**
 * Android 17+ requires a runtime permission for local network access (mDNS and WebSocket).
 * Returns whether access is granted, `null` while asking.
 */
@Composable
private fun rememberLocalNetworkPermission(): Boolean? {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK) return true
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        val allowed = ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) granted = true else launcher.launch(LOCAL_NETWORK_PERMISSION)
    }
    return granted
}

private const val LOCAL_NETWORK_SDK = 37
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
