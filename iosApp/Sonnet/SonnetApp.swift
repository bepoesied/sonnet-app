import SwiftUI
import UIKit
import sharedKit

@MainActor
final class SonnetAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        SonnetBackgroundTasks.register {
            AppStore.shared.container
        }
        return true
    }

    func applicationWillTerminate(_ application: UIApplication) {
        AppStore.shared.handleWillTerminate()
    }
}

@main
struct SonnetApp: App {
    @UIApplicationDelegateAdaptor(SonnetAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var appStore = AppStore.shared

    var body: some Scene {
        WindowGroup {
            RootView(appStore: appStore)
        }
        .onChange(of: scenePhase) { _, newPhase in
            appStore.handleScenePhase(newPhase)
        }
    }
}
