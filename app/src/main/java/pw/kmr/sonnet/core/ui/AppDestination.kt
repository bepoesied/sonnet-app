package pw.kmr.sonnet.core.ui

sealed class AppDestination(val route: String) {
    data object Login : AppDestination("login")

    data object Library : AppDestination("library")
}
