# 02 Authentication

## Goal

Implement the Sonnet mobile login flow exactly as described in `MOBILE.md`, starting from a user-entered server URL and ending with Sonnet access and refresh tokens.

## Contract Summary

1. User enters Sonnet server URL.
2. App fetches `GET /api/mobile-config`.
3. App runs native OIDC Authorization Code + PKCE using app callback `sonnet://auth/callback`.
4. App exchanges the auth code directly with the OIDC provider.
5. App sends the returned `id_token` to `POST /api/auth/oidc-login`.
6. Sonnet returns `access_token`, `refresh_token`, and `user`.

## Scope

- Server URL entry and validation.
- Mobile config discovery.
- PKCE auth flow.
- Secure token persistence.
- Session restore on startup.
- Token refresh.
- Logout.
- Clear local books on logout while retaining server URL.

## Deliverables

- Login screen for server URL entry.
- Native browser-based OIDC flow with PKCE.
- Secure token storage.
- Session bootstrap on cold start.
- `GET /api/me` validation of restored access token.
- `POST /api/auth/token-refresh` fallback when access token is invalid.
- `POST /api/auth/logout` support when online.

## Important Behavior

- The app should not assume OIDC endpoints until `mobile-config` is fetched.
- Access token should be kept in the least persistent practical storage, but v1 can restore from secure storage if that keeps the implementation simpler.
- Refresh token must be stored securely.
- If refresh fails, local session should be cleared.
- Logout must clear downloaded books and local book metadata, but keep the saved server URL.
- Production should reject insecure `http` server URLs.
- Debug builds may allow `http`.

## Suggested Tasks

1. Add deep link intent filter for `sonnet://auth/callback`.
2. Build server configuration screen.
3. Implement `GET /api/mobile-config` client and validation.
4. Implement native OIDC login with PKCE.
5. Implement `POST /api/auth/oidc-login`.
6. Persist tokens and current user.
7. Implement startup restore flow using `GET /api/me` then refresh fallback.
8. Implement logout and local data clearing.

## Edge Cases

- Server URL malformed.
- `mobile-config` unavailable or returns `503`.
- OIDC provider returns no `id_token`.
- App is killed during login redirect.
- Refresh token has expired.

## Definition Of Done

- A user can configure a server, sign in, restart the app, remain signed in, refresh tokens, and log out cleanly.