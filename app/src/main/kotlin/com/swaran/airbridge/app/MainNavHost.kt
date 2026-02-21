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
import com.swaran.airbridge.feature.dashboard.DashboardRoute
import com.swaran.airbridge.feature.filebrowser.FileBrowserRoute
import com.swaran.airbridge.feature.permissions.ui.PermissionRoute

/**
 * Sealed class representing navigation routes in the app.
 */
sealed class Route(val route: String) {
    data object Permissions : Route("permissions")
    data object Dashboard : Route("dashboard")
    data object FileBrowser : Route("filebrowser")
}

@Composable
fun MainNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = Route.Permissions.route) {
        composable(Route.Permissions.route) {
            PermissionRoute(
                onNavigateToDashboard = { 
                    navController.navigate(Route.Dashboard.route) { 
                        popUpTo(Route.Permissions.route) { inclusive = true } 
                    } 
                },
                onNavigateToSettings = { openAppSettings(context) }
            )
        }
        
        composable(Route.Dashboard.route) {
            DashboardRoute(
                onNavigateToFileBrowser = { navController.navigate(Route.FileBrowser.route) },
                onNavigateToPermissions = { navController.navigate(Route.Permissions.route) }
            )
        }
        
        composable(Route.FileBrowser.route) {
            FileBrowserRoute(
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
