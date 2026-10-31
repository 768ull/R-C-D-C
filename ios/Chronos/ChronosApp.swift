import SwiftUI
import UserNotifications

@main
struct ChronosApp: App {
    @State private var bridge = ChronosBridge()

    var body: some Scene {
        WindowGroup {
            ContentView(bridge: bridge)
                .preferredColorScheme(.light)
        }
    }
}
