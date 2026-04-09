package dev.ranmt.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

sealed class AppDestination(val route: String, val label: String) {
    data object History : AppDestination("history", "History")
    data object NewMeasurement : AppDestination("new", "New")
    data object SessionDetail : AppDestination("details/{id}", "Details")
    data object Running : AppDestination("running", "Running")
    data object Settings : AppDestination("settings", "Settings")
}

@Composable
fun RANMTBottomBar(navController: NavHostController, modifier: Modifier = Modifier) {
    val items = listOf(AppDestination.History, AppDestination.NewMeasurement)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    BottomAppBar(modifier = modifier) {
        items.forEach { destination ->
            val selected =
                currentDestination?.hierarchy?.any { it.route == destination.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(destination.route) {
                            popUpTo(AppDestination.History.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    val icon = when (destination) {
                        AppDestination.History -> Icons.Outlined.History
                        AppDestination.NewMeasurement -> Icons.Outlined.PlayArrow
                        else -> Icons.Outlined.History
                    }
                    Icon(imageVector = icon, contentDescription = destination.label)
                },
                label = { Text(destination.label) }
            )
        }
    }
}
