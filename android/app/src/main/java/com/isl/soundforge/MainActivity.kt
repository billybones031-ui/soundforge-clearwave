package com.isl.soundforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.isl.soundforge.ui.EngineViewModel
import com.isl.soundforge.ui.Screen
import com.isl.soundforge.ui.theme.SoundForgeTheme
import com.isl.soundforge.ui.screens.AuthScreen
import com.isl.soundforge.ui.screens.HomeScreen
import com.isl.soundforge.ui.screens.LibraryScreen
import com.isl.soundforge.ui.screens.ProcessScreen
import com.isl.soundforge.ui.screens.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SoundForgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SoundForgeTheme.colors.background
                ) {
                    val vm: EngineViewModel = viewModel()
                    val state by vm.state.collectAsStateWithLifecycle()
                    val navController = rememberNavController()

                    val startDest = if (state.isAuthenticated) Screen.HOME.route
                                    else Screen.AUTH.route

                    NavHost(navController = navController, startDestination = startDest) {
                        composable(Screen.AUTH.route) {
                            AuthScreen(
                                vm = vm,
                                onAuthenticated = {
                                    navController.navigate(Screen.HOME.route) {
                                        popUpTo(Screen.AUTH.route) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(Screen.HOME.route) {
                            HomeScreen(
                                vm = vm,
                                onNavigate = { navController.navigate(it.route) }
                            )
                        }
                        composable(Screen.PROCESS.route) {
                            ProcessScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.LIBRARY.route) {
                            LibraryScreen(
                                vm = vm,
                                onOpenFile = {
                                    vm.selectLibraryItem(it)
                                    navController.navigate(Screen.PROCESS.route)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.SETTINGS.route) {
                            SettingsScreen(
                                vm = vm,
                                onSignOut = {
                                    navController.navigate(Screen.AUTH.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
