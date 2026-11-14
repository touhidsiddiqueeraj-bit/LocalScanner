package com.localscanner.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localscanner.app.di.AppContainer
import com.localscanner.app.ui.screens.capture.CaptureScreen
import com.localscanner.app.ui.screens.crop.CropScreen
import com.localscanner.app.ui.screens.detail.DocDetailScreen
import com.localscanner.app.ui.screens.filter.FilterScreen
import com.localscanner.app.ui.screens.home.HomeScreen
import com.localscanner.app.ui.screens.pdf.PdfExportScreen
import com.localscanner.app.ui.screens.settings.SettingsScreen
import com.localscanner.app.ui.screens.viewer.PageViewerScreen

object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    const val CROP = "crop"
    const val FILTER = "filter"
    const val DOC = "doc/{docId}"
    const val PDF = "pdf/{docId}"
    const val VIEWER = "viewer/{docId}?page={page}"
    const val SETTINGS = "settings"

    fun doc(docId: String) = "doc/$docId"
    fun pdf(docId: String) = "pdf/$docId"
    fun viewer(docId: String, page: Int = 0) = "viewer/$docId?page=$page"
}

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onScan = { nav.navigate(Routes.CAPTURE) },
                onOpenDoc = { nav.navigate(Routes.doc(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onImportReady = { nav.navigate(Routes.FILTER) { popUpTo(Routes.HOME) } },
            )
        }

        composable(Routes.CAPTURE) {
            CaptureScreen(
                onDone = { nav.navigate(Routes.CROP) { popUpTo(Routes.HOME) } },
                onCancel = { nav.popBackStack() },
            )
        }

        composable(Routes.CROP) {
            CropScreen(
                onDone = { nav.navigate(Routes.FILTER) { popUpTo(Routes.HOME) } },
            )
        }

        composable(Routes.FILTER) {
            FilterScreen(
                onSaved = { docId ->
                    nav.navigate(Routes.doc(docId)) { popUpTo(Routes.HOME) { inclusive = false } }
                },
            )
        }

        composable(
            Routes.DOC,
            arguments = listOf(navArgument("docId") { type = NavType.StringType }),
        ) { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            DocDetailScreen(
                docId = docId,
                onBack = { nav.popBackStack() },
                onExportPdf = { nav.navigate(Routes.pdf(docId)) },
                onOpenViewer = { nav.navigate(Routes.viewer(docId)) },
                onAddPages = { nav.navigate(Routes.CAPTURE) },
                onViewPage = { page -> nav.navigate(Routes.viewer(docId, page)) },
            )
        }

        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument("docId") { type = NavType.StringType },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 0
                },
            ),
        ) { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            val page = entry.arguments?.getInt("page") ?: 0
            PageViewerScreen(
                docId = docId,
                initialPage = page,
                onBack = { nav.popBackStack() },
            )
        }

        composable(
            Routes.PDF,
            arguments = listOf(navArgument("docId") { type = NavType.StringType }),
        ) { entry ->
            val docId = entry.arguments?.getString("docId") ?: return@composable
            PdfExportScreen(
                docId = docId,
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
