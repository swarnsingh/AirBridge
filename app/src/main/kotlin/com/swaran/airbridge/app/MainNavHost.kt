package com.swaran.airbridge.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.swaran.airbridge.feature.dashboard.DashboardScreen
import com.swaran.airbridge.feature.filebrowser.FileBrowserScreen
import com.swaran.airbridge.feature.permissions.PermissionScreen

@Composable
fun MainNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = "permissions") {
        composable("permissions") {
            PermissionScreen(
                onNavigateToDashboard = { 
                    navController.navigate("dashboard") { 
                        popUpTo("permissions") { inclusive = true } 
                    } 
                },
                onNavigateToSettings = { openAppSettings(context) }
            )
        }
        
        composable("dashboard") {
            DashboardScreen(
                onNavigateToFileBrowser = { navController.navigate("filebrowser") },
                onNavigateToPermissions = { navController.navigate("permissions") }
            )
        }
        
        composable("filebrowser") {
            FileBrowserScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    context.startActivity(intent)
}
