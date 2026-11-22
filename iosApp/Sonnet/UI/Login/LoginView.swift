import SwiftUI
import sharedKit

struct LoginView: View {
    @State private var store: LoginStore
    private let showCancelButton: Bool
    private let onCancel: () -> Void

    init(
        container: IosAppContainer,
        authPresenter: WebAuthSessionPresenter,
        showCancelButton: Bool = false,
        onCancel: @escaping () -> Void = {},
        onLoginCompleted: @escaping () -> Void = {}
    ) {
        self._store = State(
            initialValue: LoginStore(
                container: container,
                authPresenter: authPresenter,
                onLoginCompleted: onLoginCompleted
            )
        )
        self.showCancelButton = showCancelButton
        self.onCancel = onCancel
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Spacer(minLength: 48)

                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "headphones.circle.fill")
                            .font(.system(size: 48))
                            .foregroundStyle(SonnetColors.accent)
                            .accessibilityHidden(true)

                        Text("Connect to Sonnet")
                            .font(.largeTitle.weight(.bold))

                        Text(
                            "Enter your Sonnet server URL. The app will discover its mobile OIDC settings and sign in through your browser."
                        )
                        .font(.body)
                        .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Server URL")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.secondary)

                        TextField(
                            "https://sonnet.example.com",
                            text: Binding(
                                get: { store.uiState.serverUrl },
                                set: { store.updateServerUrl($0) }
                            )
                        )
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.URL)
                        .submitLabel(.go)
                        .disabled(store.uiState.isLoading)
                        .padding(14)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color(uiColor: .secondarySystemGroupedBackground))
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(Color(uiColor: .separator), lineWidth: 1)
                        )
                        .onSubmit {
                            if store.canSubmit {
                                store.startLogin()
                            }
                        }

                        if let errorMessage = store.uiState.errorMessage, !errorMessage.isEmpty {
                            Text(errorMessage)
                                .font(.callout)
                                .foregroundStyle(.red)
                                .padding(.top, 4)
                        }
                    }

                    Button {
                        store.startLogin()
                    } label: {
                        HStack(spacing: 8) {
                            if store.uiState.isLoading {
                                ProgressView()
                                    .tint(.white)
                                    .accessibilityLabel("Signing in")
                            } else {
                                Text("Sign in")
                                    .fontWeight(.semibold)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(SonnetColors.accent)
                    .disabled(!store.canSubmit)
                    .padding(.top, 4)

                    Spacer(minLength: 24)
                }
                .padding(.horizontal, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(uiColor: .systemGroupedBackground))
            .toolbar {
                if showCancelButton {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            onCancel()
                        }
                    }
                }
            }
        }
    }
}
