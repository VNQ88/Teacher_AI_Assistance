# Authentication API Specification

Specification for frontend integration with TeacherAssistantAI authentication APIs.

## Base Contract

Base URL:

```text
/api
```

Auth base path:

```text
/api/auth
```

Default content type:

```http
Content-Type: application/json
```

Successful responses use this envelope:

```json
{
  "status": 200,
  "message": "Human-readable message",
  "data": {}
}
```

Error responses use this envelope:

```json
{
  "timestamp": "2026-06-03T10:00:00.000+00:00",
  "status": 400,
  "path": "/api/auth/...",
  "error": "Invalid Argument",
  "message": "Error details"
}
```

## Frontend Token Rules

- Store both `accessToken` and `refreshToken` after login or refresh.
- Send `accessToken` to protected APIs with `Authorization: Bearer <accessToken>`.
- Send `refreshToken` in JSON body for `/auth/refresh` and `/auth/logout`.
- Do not send refresh token in `Referer`; backend no longer reads it.
- After refresh succeeds, replace both old tokens with returned tokens.
- Refresh token rotation is one-time-use. If two refresh requests use the same refresh token, only one should succeed.
- After reset password, all previously issued access and refresh tokens become invalid.
- Tokens issued before the `tokenType` change may be rejected. If token validation fails, force user to login again.

## Token Types

JWT tokens include `tokenType`:

```text
ACCESS
REFRESH
```

Usage:

- Protected APIs accept only `ACCESS` tokens.
- `/auth/refresh` accepts only `REFRESH` tokens.
- `/auth/logout` requires a valid `REFRESH` token. `ACCESS` token is optional and best-effort for blacklist.

## Endpoints

## 1. Login

```http
POST /api/auth/login
```

Request body:

```json
{
  "email": "student@example.com",
  "password": "password123"
}
```

Validation:

- `email`: required, valid email.
- `password`: required, minimum 5 characters.

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "Authentication successful",
  "data": {
    "accessToken": "<jwt-access-token>",
    "refreshToken": "<jwt-refresh-token>"
  }
}
```

Frontend behavior:

- Save `accessToken` and `refreshToken`.
- Use `accessToken` for protected API calls.

Common errors:

- Invalid email/password can return validation/auth error.
- Unactivated account behavior depends on Spring Security user details validation.

## 2. Register

```http
POST /api/auth/register
```

Request body:

```json
{
  "email": "student@example.com",
  "password": "password123",
  "fullName": "Student Name"
}
```

Validation:

- `email`: required, valid email.
- `password`: required, minimum 5 characters.
- `fullName`: required.

Success response: `202 Accepted`

For new account:

```json
{
  "status": 202,
  "message": "Registration successful. Please check your email to activate your account.",
  "data": {
    "expiresInSeconds": 900,
    "resent": false
  }
}
```

For existing but unactivated account:

```json
{
  "status": 202,
  "message": "Account already exists but not activated. A new activation code has been sent.",
  "data": {
    "expiresInSeconds": 900,
    "resent": true
  }
}
```

Frontend behavior:

- Navigate user to account activation screen.
- Ask user to enter OTP/code from email.
- Use `expiresInSeconds` to show countdown.

## 3. Activate Account

```http
POST /api/auth/activate-account?code=123456
```

Query params:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `code` | string | yes | Activation code from email. |

Success response: `200 OK` at HTTP layer, envelope status is `202`

```json
{
  "status": 202,
  "message": "Account activated."
}
```

Frontend behavior:

- On success, redirect to login.
- On invalid/expired code, show error and allow resend activation code.

## 4. Resend Activation Code

```http
POST /api/auth/resend-activation-code?email=student@example.com
```

Query params:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `email` | string | yes | User email. |

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "A new activation code has been sent to your email.",
  "data": {
    "expiresInSeconds": 900,
    "resent": false
  }
}
```

Frontend behavior:

- Restart OTP countdown with `expiresInSeconds`.

## 5. Refresh Token

```http
POST /api/auth/refresh
```

Request body:

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

Validation:

- `refreshToken`: required, non-blank.
- Must be a valid JWT with `tokenType=REFRESH`.
- Must not be expired.
- Must not have been used before.
- Must not be older than user-level token invalidation marker created by reset password.

Success response: `202 Accepted`

```json
{
  "status": 202,
  "message": "Token refreshed successfully",
  "data": {
    "accessToken": "<new-jwt-access-token>",
    "refreshToken": "<new-jwt-refresh-token>"
  }
}
```

Frontend behavior:

- Replace stored access and refresh tokens immediately.
- Do not retry refresh multiple times with the same refresh token concurrently.
- If refresh fails, clear tokens and redirect to login.

Suggested frontend concurrency behavior:

- Use a single in-flight refresh promise/lock.
- Queue failed protected requests while refresh is in progress.
- Replay queued requests only after storing the new tokens.

Common errors:

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Refresh token has been revoked"
}
```

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid refresh token"
}
```

## 6. Logout

```http
POST /api/auth/logout
```

Request body:

```json
{
  "refreshToken": "<jwt-refresh-token>"
}
```

Optional header:

```http
Authorization: Bearer <jwt-access-token>
```

Validation:

- `refreshToken`: required, non-blank.
- Must be a valid JWT with `tokenType=REFRESH`.
- Must not be expired or already revoked.
- `accessToken` is optional; if present and usable, backend blacklists it.

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "Logout successful"
}
```

Frontend behavior:

- Always clear local tokens after calling logout, even if access token is expired.
- If logout fails because refresh token is already invalid/revoked, clear local tokens anyway.

## 7. Forgot Password

```http
POST /api/auth/forgot-password?email=student@example.com
```

Query params:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `email` | string | yes | User email. |

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "An email has been sent to your email address.",
  "data": {
    "expiresInSeconds": 900,
    "resent": false
  }
}
```

Backend behavior:

- If account is enabled, sends reset password code.
- If account is not enabled, sends activation code instead.

Frontend behavior:

- Show generic success message.
- Navigate to reset-code verification screen if this is password recovery flow.

## 8. Verify Reset Code

```http
POST /api/auth/verify-reset-code
```

Request body:

```json
{
  "email": "student@example.com",
  "code": "123456"
}
```

Validation:

- `email`: required, valid email.
- `code`: required, non-blank.
- Code must exist with purpose `RESET_PASSWORD`.
- Code must belong to the provided email.

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "Code verified successfully"
}
```

Important behavior:

- This endpoint does not consume/delete the reset code.
- The same code must still be sent to `/auth/reset-password`.

Common error:

```json
{
  "status": 400,
  "error": "Invalid Argument",
  "message": "Code is invalid or has expired"
}
```

Frontend behavior:

- If verification succeeds, let user enter new password.
- Keep `email` and `code` in form state for reset password request.

## 9. Resend Reset Code

```http
POST /api/auth/resend-reset-code?email=student@example.com
```

Query params:

| Name | Type | Required | Description |
| --- | --- | --- | --- |
| `email` | string | yes | User email. |

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "A new reset code has been sent to your email.",
  "data": {
    "expiresInSeconds": 900,
    "resent": false
  }
}
```

Frontend behavior:

- Restart reset-code countdown with `expiresInSeconds`.

## 10. Reset Password

```http
POST /api/auth/reset-password
```

Request body:

```json
{
  "email": "student@example.com",
  "code": "123456",
  "newPassword": "new-password123",
  "confirmPassword": "new-password123"
}
```

Validation:

- `email`: required, valid email.
- `code`: required, non-blank.
- `newPassword`: required, non-blank.
- `confirmPassword`: required, non-blank.
- `newPassword` must equal `confirmPassword`.
- Code must exist with purpose `RESET_PASSWORD`.
- Code must belong to the provided email.

Success response: `200 OK`

```json
{
  "status": 200,
  "message": "Password has been reset successfully"
}
```

Security behavior:

- Password is updated.
- Account is set to enabled.
- Reset code is deleted.
- All existing user tokens issued before reset are invalidated.

Frontend behavior:

- Clear any local tokens for this user.
- Redirect to login.

Common errors:

```json
{
  "status": 400,
  "error": "Invalid Argument",
  "message": "Password and confirm password do not match"
}
```

```json
{
  "status": 400,
  "error": "Invalid Argument",
  "message": "Code is invalid or has expired"
}
```

## Protected API Usage Example

For non-auth endpoints:

```http
Authorization: Bearer <accessToken>
```

If protected API returns `401` due to expired/invalid access token:

1. Call `/api/auth/refresh` with current refresh token.
2. If refresh succeeds, store new tokens and retry original request.
3. If refresh fails, clear tokens and redirect to login.

## Endpoint Summary

| Method | Endpoint | Auth Required | Body/Params | Success |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/login` | no | body: email, password | 200 |
| POST | `/api/auth/register` | no | body: email, password, fullName | 202 |
| POST | `/api/auth/activate-account` | no | query: code | envelope status 202 |
| POST | `/api/auth/resend-activation-code` | no | query: email | 200 |
| POST | `/api/auth/refresh` | no | body: refreshToken | 202 |
| POST | `/api/auth/logout` | no | body: refreshToken, optional Authorization | 200 |
| POST | `/api/auth/forgot-password` | no | query: email | 200 |
| POST | `/api/auth/verify-reset-code` | no | body: email, code | 200 |
| POST | `/api/auth/resend-reset-code` | no | query: email | 200 |
| POST | `/api/auth/reset-password` | no | body: email, code, newPassword, confirmPassword | 200 |
