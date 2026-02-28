package com.swaran.airbridge.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.swaran.airbridge.feature.dashboard.DashboardRoute

@Composable
fun AirBridgeApp() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "dashboard",
        modifier = Modifier.fillMaxSize()
    ) {
        composable("dashboard") {
            DashboardRoute()
        }
    }
}
