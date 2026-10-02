package com.cayana.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cayana.ui.home.HomeScreen
import com.cayana.ui.home.HomeViewModel
import com.cayana.ui.onboarding.OnboardingScreen
import com.cayana.ui.onboarding.OnboardingViewModel
import com.cayana.ui.settings.SettingsScreen
import com.cayana.ui.settings.SettingsViewModel
import com.cayana.ui.settings.repository.SettingsRepository
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun CayanaNavHost(
    navController: NavHostController = rememberNavController(),
    settingsRepository: SettingsRepository = koinInject(),
    overrideStartDestination: String? = null
) {
    val settingsState by settingsRepository.getSettings().collectAsState(initial = null)

    if (overrideStartDestination == null && settingsState == null) {
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    val startDestination = overrideStartDestination
        ?: if (settingsState?.onboardingCompleted == true) {
            Screen.Home.route
        } else {
            Screen.Onboarding.route
        }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Onboarding.route) {
            val onboardingViewModel: OnboardingViewModel = koinViewModel()
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onComplete = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

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
