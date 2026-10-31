import SwiftUI
import WebKit

struct ContentView: View {
    let bridge: ChronosBridge

    var body: some View {
        ChronosWebView(bridge: bridge)
            .ignoresSafeArea()
            .background(Color(.systemBackground))
    }
}

private struct ChronosWebView: UIViewRepresentable {
    let bridge: ChronosBridge

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        controller.add(bridge, name: "chronos")

        if let scriptURL = Bundle.main.url(forResource: "ios-bridge", withExtension: "js"),
           let script = try? String(contentsOf: scriptURL, encoding: .utf8) {
            controller.addUserScript(WKUserScript(
                source: script,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            ))
        }

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.uiDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = UIColor.systemBackground
        webView.scrollView.backgroundColor = UIColor.systemBackground
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        bridge.attach(webView)

        if let pageURL = Bundle.main.url(forResource: "index", withExtension: "html") {
            webView.loadFileURL(
                pageURL,
                allowingReadAccessTo: pageURL.deletingLastPathComponent()
            )
        } else {
            webView.loadHTMLString(
                "<html><body style='font-family:-apple-system;padding:24px'>Não foi possível carregar a interface do Chronos.</body></html>",
                baseURL: nil
            )
        }

        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "chronos")
        uiView.stopLoading()
    }

    final class Coordinator: NSObject, WKUIDelegate {
        func webView(
            _ webView: WKWebView,
            runJavaScriptAlertPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo,
            completionHandler: @escaping () -> Void
        ) {
            guard let controller = webView.findViewController() else {
                completionHandler()
                return
            }

            let alert = UIAlertController(title: "Chronos", message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
            controller.present(alert, animated: true)
        }

        func webView(
            _ webView: WKWebView,
            runJavaScriptConfirmPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo,
            completionHandler: @escaping (Bool) -> Void
        ) {
            guard let controller = webView.findViewController() else {
                completionHandler(false)
                return
            }

            let alert = UIAlertController(title: "Chronos", message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "Cancelar", style: .cancel) { _ in completionHandler(false) })
            alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
            controller.present(alert, animated: true)
        }
    }
}

private extension UIView {
    func findViewController() -> UIViewController? {
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController { return controller }
            responder = current.next
        }
        return nil
    }
}
