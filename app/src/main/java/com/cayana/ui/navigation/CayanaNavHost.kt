package com.cayana.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cayana.ui.home.HomeScreen
import com.cayana.ui.home.HomeViewModel
import com.cayana.ui.settings.SettingsScreen
import com.cayana.ui.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun CayanaNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = koinViewModel()
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = koinViewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
