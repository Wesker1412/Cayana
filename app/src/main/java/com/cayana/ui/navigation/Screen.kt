package com.cayana.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
}
