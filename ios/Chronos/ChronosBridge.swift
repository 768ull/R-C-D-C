import Foundation
import CoreLocation
import WebKit
import UserNotifications

final class ChronosBridge: NSObject, WKScriptMessageHandler, CLLocationManagerDelegate, UNUserNotificationCenterDelegate {
    private weak var webView: WKWebView?
    private let locationManager = CLLocationManager()
    private let notificationCenter = UNUserNotificationCenter.current()
    private let alarmsKey = "chronos.ios.alarms.v1"
    private var waitingForWeatherLocation = false

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.headingFilter = 1
        notificationCenter.delegate = self
    }

    func attach(_ webView: WKWebView) {
        self.webView = webView
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "chronos", let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "searchMapPlace":
            searchMapPlace(body["query"] as? String ?? "")
        case "startCompass":
            startCompass()
        case "stopCompass":
            locationManager.stopUpdatingHeading()
        case "searchWeatherCity":
            searchWeatherCity(body["query"] as? String ?? "")
        case "requestWeatherLocation":
            requestWeatherLocation()
        case "fetchWeather":
            fetchWeather(
                latitude: body["latitude"] as? Double ?? .nan,
                longitude: body["longitude"] as? Double ?? .nan,
                cityName: body["cityName"] as? String ?? "Local selecionado",
                admin1: body["admin1"] as? String ?? "",
                country: body["country"] as? String ?? ""
            )
        case "connectWeatherWidget":
            callJS("onChronosWeatherStatus", ["No iPhone, o widget do clima será disponibilizado como widget da tela inicial em uma etapa própria.", false])
        case "setWeatherWidgetBounds", "setWeatherTabVisible":
            break
        case "saveAlarm":
            if let alarm = body["alarm"] as? [String: Any] { saveAlarm(alarm) }
        case "setAlarmEnabled":
            setAlarmEnabled(id: body["id"], enabled: body["enabled"] as? Bool ?? false)
        case "deleteAlarm":
            deleteAlarm(id: body["id"])
        case "requestAlarmPermission":
            requestAlarmPermission()
        case "openSystemClock":
            callJS("onChronosWeatherStatus", ["O iOS não permite que o Chronos abra diretamente a tela interna do app Relógio. Seus alarmes continuam disponíveis no Chronos.", false])
        default:
            break
        }
    }

    // MARK: - Compass

    private func startCompass() {
        guard CLLocationManager.headingAvailable() else {
            callJS("onChronosCompassStatus", ["Este iPhone não disponibiliza leitura de bússola."])
            return
        }
        locationManager.startUpdatingHeading()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let value = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        callJS("onChronosCompassHeading", [value])
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        true
    }

    // MARK: - Location and weather

    private func requestWeatherLocation() {
        waitingForWeatherLocation = true
        switch locationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            locationManager.requestLocation()
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
            callJS("onChronosWeatherStatus", ["Autorize a localização para consultar o clima da sua região.", false])
        case .denied, .restricted:
            waitingForWeatherLocation = false
            callJS("onChronosWeatherStatus", ["Permita o acesso à localização nos Ajustes do iPhone ou pesquise uma cidade manualmente.", false])
        @unknown default:
            waitingForWeatherLocation = false
            callJS("onChronosWeatherStatus", ["Não foi possível verificar a permissão de localização.", false])
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard waitingForWeatherLocation else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.requestLocation()
        case .denied, .restricted:
            waitingForWeatherLocation = false
            callJS("onChronosWeatherStatus", ["Permissão de localização negada. Você ainda pode pesquisar uma cidade.", false])
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard waitingForWeatherLocation, let location = locations.last else { return }
        waitingForWeatherLocation = false
        callJS("onChronosWeatherLocation", [location.coordinate.latitude, location.coordinate.longitude])
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard waitingForWeatherLocation else { return }
        waitingForWeatherLocation = false
        callJS("onChronosWeatherStatus", ["Não foi possível obter sua localização. Ative a localização ou pesquise uma cidade.", false])
    }

    private func searchWeatherCity(_ query: String) {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            callJS("onChronosWeatherCities", [[], "Digite o nome de uma cidade."])
            return
        }

        var components = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        components.queryItems = [
            URLQueryItem(name: "count", value: "6"),
            URLQueryItem(name: "language", value: "pt"),
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "name", value: query)
        ]
        guard let url = components.url else { return }

        fetchJSON(url: url) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let object):
                let source = object["results"] as? [[String: Any]] ?? []
                let cities: [[String: Any]] = source.prefix(6).map { item in
                    [
                        "name": item["name"] ?? "Cidade",
                        "admin1": item["admin1"] ?? "",
                        "country": item["country"] ?? "",
                        "country_code": item["country_code"] ?? "",
                        "latitude": item["latitude"] ?? 0,
                        "longitude": item["longitude"] ?? 0
                    ]
                }
                let error = cities.isEmpty ? "Nenhuma cidade encontrada. Tente informar também o estado ou país." : NSNull() as Any
                self.callJS("onChronosWeatherCities", [cities, error])
            case .failure:
                self.callJS("onChronosWeatherCities", [[], "Não foi possível pesquisar a cidade. Verifique sua conexão e tente novamente."])
            }
        }
    }

    private func fetchWeather(latitude: Double, longitude: Double, cityName: String, admin1: String, country: String) {
        guard latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude) else {
            callJS("onChronosWeatherForecast", [NSNull(), "A localização escolhida é inválida."])
            return
        }

        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.5f", latitude)),
            URLQueryItem(name: "longitude", value: String(format: "%.5f", longitude)),
            URLQueryItem(name: "current", value: "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m"),
            URLQueryItem(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"),
            URLQueryItem(name: "forecast_days", value: "5"),
            URLQueryItem(name: "timezone", value: "auto")
        ]
        guard let url = components.url else { return }

        fetchJSON(url: url) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(var forecast):
                forecast["chronos_location"] = [
                    "name": cityName,
                    "admin1": admin1,
                    "country": country
                ]
                self.callJS("onChronosWeatherForecast", [forecast, NSNull()])
            case .failure:
                self.callJS("onChronosWeatherForecast", [NSNull(), "Não foi possível carregar a previsão agora. Confira a internet e tente novamente."])
            }
        }
    }

    // MARK: - Map search

    private func searchMapPlace(_ query: String) {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            callJS("onChronosMapSearchResult", [[], "Digite um endereço para pesquisar."])
            return
        }

        var components = URLComponents(string: "https://nominatim.openstreetmap.org/search")!
        components.queryItems = [
            URLQueryItem(name: "format", value: "jsonv2"),
            URLQueryItem(name: "limit", value: "5"),
            URLQueryItem(name: "addressdetails", value: "1"),
            URLQueryItem(name: "accept-language", value: "pt-BR"),
            URLQueryItem(name: "q", value: query)
        ]
        guard let url = components.url else { return }

        var request = URLRequest(url: url)
        request.setValue("Chronos iOS (+https://github.com/768ull/R-C-D-C)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }
            guard error == nil, let data,
                  let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let source = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                self.callJS("onChronosMapSearchResult", [[], "Não foi possível pesquisar agora. Verifique a internet e tente novamente."])
                return
            }

            let results: [[String: Any]] = source.prefix(5).map { item in
                [
                    "lat": item["lat"] ?? "",
                    "lon": item["lon"] ?? "",
                    "name": item["display_name"] ?? "Local encontrado"
                ]
            }
            self.callJS("onChronosMapSearchResult", [results, NSNull()])
        }.resume()
    }

    private func fetchJSON(url: URL, completion: @escaping (Result<[String: Any], Error>) -> Void) {
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Chronos iOS (+https://github.com/768ull/R-C-D-C)", forHTTPHeaderField: "User-Agent")

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                completion(.failure(error))
                return
            }
            guard let data, let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                completion(.failure(NSError(domain: "ChronosNetwork", code: 1)))
                return
            }
            completion(.success(object))
        }.resume()
    }

    // MARK: - iOS notifications used for alarms

    private var alarms: [[String: Any]] {
        get { UserDefaults.standard.array(forKey: alarmsKey) as? [[String: Any]] ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: alarmsKey) }
    }

    private func alarmID(_ value: Any?) -> String {
        guard let value else { return UUID().uuidString }
        return String(describing: value).replacingOccurrences(of: " ", with: "_")
    }

    private func saveAlarm(_ alarm: [String: Any]) {
        guard let rawTime = alarm["time"] as? String,
              rawTime.range(of: #"^([01]\d|2[0-3]):[0-5]\d$"#, options: .regularExpression) != nil else { return }

        let id = alarmID(alarm["id"])
        var updated = alarm
        updated["id"] = id
        if updated["enabled"] == nil { updated["enabled"] = true }

        var all = alarms
        if let index = all.firstIndex(where: { alarmID($0["id"]) == id }) {
            all[index] = updated
        } else {
            all.append(updated)
        }
        alarms = all

        requestNotificationAuthorization { [weak self] granted in
            guard let self else { return }
            if granted, (updated["enabled"] as? Bool ?? true) {
                self.scheduleAlarm(updated)
            }
        }
    }

    private func setAlarmEnabled(id: Any?, enabled: Bool) {
        let identifier = alarmID(id)
        var all = alarms
        guard let index = all.firstIndex(where: { alarmID($0["id"]) == identifier }) else { return }
        all[index]["enabled"] = enabled
        alarms = all
        removeAlarmNotifications(identifier)
        if enabled {
            requestNotificationAuthorization { [weak self] granted in
                guard granted, let self else { return }
                if let alarm = self.alarms.first(where: { self.alarmID($0["id"]) == identifier }) {
                    self.scheduleAlarm(alarm)
                }
            }
        }
    }

    private func deleteAlarm(id: Any?) {
        let identifier = alarmID(id)
        alarms.removeAll { alarmID($0["id"]) == identifier }
        removeAlarmNotifications(identifier)
    }

    private func requestNotificationAuthorization(completion: ((Bool) -> Void)? = nil) {
        notificationCenter.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { completion?(granted) }
        }
    }

    private func requestAlarmPermission() {
        requestNotificationAuthorization { [weak self] granted in
            self?.callJS("onChronosWeatherStatus", [
                granted
                    ? "Permissão de notificações concedida. O iOS poderá entregar os alarmes como notificações."
                    : "As notificações estão desativadas. Ative-as nos Ajustes para receber os alarmes do Chronos.",
                granted
            ])
        }
    }

    private func removeAlarmNotifications(_ identifier: String) {
        let ids = ["once"] + (1...7).map(String.init)
        notificationCenter.removePendingNotificationRequests(
            withIdentifiers: ids.map { "chronos.alarm.\(identifier).\($0)" }
        )
    }

    private func scheduleAlarm(_ alarm: [String: Any]) {
        let id = alarmID(alarm["id"])
        guard let time = alarm["time"] as? String else { return }
        let parts = time.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return }
        let hour = parts[0]
        let minute = parts[1]
        guard (0...23).contains(hour), (0...59).contains(minute) else { return }

        removeAlarmNotifications(id)
        let days = alarm["days"] as? [Bool] ?? []
        let selectedDays = days.indices.filter { $0 < 7 && days[$0] }

        if selectedDays.isEmpty {
            var components = Calendar.current.dateComponents([.year, .month, .day], from: Date())
            components.hour = hour
            components.minute = minute
            components.second = 0
            if let candidate = Calendar.current.date(from: components), candidate <= Date(),
               let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: candidate) {
                components = Calendar.current.dateComponents([.year, .month, .day], from: nextDay)
                components.hour = hour
                components.minute = minute
                components.second = 0
            }
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            addAlarmNotification(id: id, suffix: "once", alarm: alarm, trigger: trigger)
        } else {
            for day in selectedDays {
                // A interface usa segunda-feira como o primeiro dia; Calendar usa domingo = 1.
                let weekday = ((day + 1) % 7) + 1
                var components = DateComponents()
                components.calendar = Calendar.current
                components.weekday = weekday
                components.hour = hour
                components.minute = minute
                let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: true)
                addAlarmNotification(id: id, suffix: String(weekday), alarm: alarm, trigger: trigger)
            }
        }
    }

    private func addAlarmNotification(id: String, suffix: String, alarm: [String: Any], trigger: UNNotificationTrigger) {
        let content = UNMutableNotificationContent()
        content.title = "Chronos · Hora de acordar"
        let label = (alarm["label"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        content.body = label.isEmpty ? "Seu alarme está programado." : label
        content.sound = UNNotificationSound.default
        content.userInfo = ["chronosAlarmID": id]
        let request = UNNotificationRequest(
            identifier: "chronos.alarm.\(id).\(suffix)",
            content: content,
            trigger: trigger
        )
        notificationCenter.add(request) { _ in }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    private func callJS(_ function: String, _ arguments: [Any]) {
        guard JSONSerialization.isValidJSONObject(arguments),
              let argumentData = try? JSONSerialization.data(withJSONObject: arguments, options: [.fragmentsAllowed]),
              let argumentJSON = String(data: argumentData, encoding: .utf8),
              let functionData = try? JSONSerialization.data(withJSONObject: function, options: [.fragmentsAllowed]),
              let functionJSON = String(data: functionData, encoding: .utf8) else { return }

        let script = "(function(){var f=window[\(functionJSON)];if(typeof f==='function'){f.apply(window,\(argumentJSON));}})();"
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript(script, completionHandler: nil)
        }
    }
}
