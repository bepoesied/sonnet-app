
sealed class AppDestination(val route: String) {
    data object Login : AppDestination("login")
    data object Library : AppDestination("library")
    data object Player : AppDestination("player")
}