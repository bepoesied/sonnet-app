import SwiftUI
import sharedKit
import AuthenticationServices

struct LoginView: View {
    @EnvironmentObject var appState: AppState
    @State private var serverUrl: String = ""
    @State private var isLoading: Bool = false
    @State private var errorMessage: String? = nil
    @State private var authSession: ASWebAuthenticationSession?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Connect to Sonnet")
                .font(.largeTitle)
                .fontWeight(.bold)
                .padding(.bottom, 12)

            Text("Enter your Sonnet server URL. The app will discover its mobile OIDC settings and sign in through your browser.")
                .font(.body)
                .foregroundStyle(.secondary)
                .padding(.bottom, 24)

            TextField("https://sonnet.example.com", text: $serverUrl)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
                .disabled(isLoading)
                .padding(.bottom, 16)

            if let error = errorMessage {
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .padding(.bottom, 16)
            }

            Button(action: startLogin) {
                if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    Text("Sign in")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(isLoading || serverUrl.isBlank)

            Spacer()
        }
        .padding(24)
        .navigationBarHidden(true)
        .onAppear {
            if let saved = appState.container.loginHelper.savedServerUrl() {
                serverUrl = saved
            }
            observeEffects()
        }
    }

    private func startLogin() {
        isLoading = true
        errorMessage = nil
        appState.container.loginHelper.onServerUrlChange(serverUrl: serverUrl)
        appState.container.loginHelper.startLogin(onSuccess: {
            // Effect will be collected by observeEffects
        }, onError: { error in
            isLoading = false
            errorMessage = error
        })
    }

    private func observeEffects() {
        appState.container.loginHelper.collectEffects { effect in
            DispatchQueue.main.async {
                if let openAuth = effect as? LoginEffectOpenAuthBrowser {
                    let authData = openAuth.authData as! AuthSessionData
                    IosPlatformAuthProvider.companion.currentAuthData = authData
                    launchAuthSession(authData: authData)
                } else if effect is LoginEffectLoginCompleted {
                    isLoading = false
                    appState.isAuthenticated = true
                    appState.navigationPath = NavigationPath()
                }
            }
        }
    }

    private func launchAuthSession(authData: AuthSessionData) {
        guard let url = URL(string: authData.authUrl) else {
            isLoading = false
            errorMessage = "Invalid authorization URL."
            return
        }

        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "sonnet") { callbackURL, error in
            DispatchQueue.main.async {
                if let error = error {
                    self.isLoading = false
                    self.errorMessage = error.localizedDescription
                    return
                }
                guard let callbackURL = callbackURL else {
                    self.isLoading = false
                    self.errorMessage = "Authorization was cancelled."
                    return
                }
                self.handleCallback(callbackURL)
            }
        }
        session.prefersEphemeralWebBrowserSession = true
        self.authSession = session
        session.start()
    }

    private func handleCallback(_ url: URL) {
        let urlString = url.absoluteString
        guard urlString.hasPrefix("sonnet://auth/callback") else { return }
        guard let authData = IosPlatformAuthProvider.companion.currentAuthData else { return }

        let queryItems = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        let code = queryItems?.first(where: { $0.name == "code" })?.value
        let state = queryItems?.first(where: { $0.name == "state" })?.value

        guard let code = code else {
            isLoading = false
            errorMessage = "Authorization code not found."
            return
        }

        let result = AuthCallbackResult(code: code, state: state, authData: authData)

        appState.container.loginHelper.completeLogin(authResult: result, onSuccess: {
            self.isLoading = false
            self.appState.isAuthenticated = true
            self.appState.navigationPath = NavigationPath()
        }, onError: { error in
            self.isLoading = false
            self.errorMessage = error
        })
    }
}

extension String {
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
