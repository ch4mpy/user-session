# Experiments Around User Session with Keycloak & Spring

The project used to experiment with user sessions in an SSO environment is structured as follows:
- Keycloak as OpenID Provider, pre-configured as follows:
  * exposed at https://localhost/auth/admin/master/console/#/sample (`admin`/`secret`)
  * a `sample` realm
  * a `ui` confidential client (`secret` as secret) configured for the authorization-code flow
  * a `third-party` confidential client (`secret` as secret) configured for the authorization-code flow
  * two users: `ch4mpy`/`secret` and `josh`/`secret`
  * very short lifespans for access token, SSO Session Idle, and SSO Session Max
- Two very simple Single-Page Applications (`ui` & `third-party`) with log in/out, and displaying some data about the user session on the BFF.
- A Spring Boot backend composed of:
  * two OAuth2 BFFs (one for each SPA) which, in addition to performing their usual job, expose an endpoint returning data about the user session on the BFF.
  * a sample REST API

## 1. Setup

### 1.1. Prerequisites
- Git with bash commandline (on Windows, [GitBash](https://git-scm.com/downloads) offers it)
- JDK 24, ideally GraalVM CE (for instance, using [SDKMAN!](https://sdkman.io/))
```sh
sdk list java
sdk install java 24-graalce
sdk use java 24-graalce
```
- [Docker Desktop](https://www.docker.com/get-started/)
- [NodeJS](https://nodejs.org/en/download) with `npm` (latest LTS version)
- Self-signed certificate for the dev machine. [This repo](https://github.com/ch4mpy/self-signed-certificate-generation) contains scripts to generate certificates and instruction to install it with the dev machine OS & installed JREs
- an IDE (the author uses [Eclipse STS and Visual Studio Code](https://spring.io/tools))

If the dev machine does not have a recent Maven distribution, a maven wrapper is included in the `backend` directory.

### 1.2. Init
```sh
git clone git@github.com:ch4mpy/user-session.git
cd user-session
```
Copy the dev machine self-signed certificates to the `/backend/certs` directory. The files should be named `tls.crt`, `tls.jks`, and `tls.key`.
```sh
sh ./setup.sh
docker compose up -d
cd backend
sh ./mvnw install -Popenapi
cd ../frontend
npm i
```

## 2. Notes

### 2.1. Sessions
An identified user has up to 3 distinct sessions: one on Keycloak and one on each Spring client with `oauth2Login` (BFFs).

**The most important session is Keycloak's one.** It is the reference for the user state (logged in or not). When SSO is enabled, once the user is logged in and as long as the Keycloak session is active, further authorization code flows complete silently: it is possible to rebuild a BFF session without prompting the user for his credentials.

The BFFs sessions hold the OAuth2 tokens (access, refresh, and ID). When such a session is invalidated, the tokens it contain are deleted.

Each session having its own cookie which is pretty sensitive credentials, these cookies should:
- be flagged `HttpOnly`: hidden from the SPA (and dependencies) JavaScript code
- be flagged `Secure`: always encrypted on the network
- be flagged `SameSite`
- have a narrow path: prevent collisions and ensure that they are exposed only to the app that emitted them (with special care to reverse proxies configuration)

For Keycloak, an **"offline session"** allows a client to act on behalf of a resource owner (a user) even if he's logged out: the user might have logged out explicitly, but an app can keep acting in its name. This is a niche use-case that should be used with care because of the security implications and [known limitations](https://github.com/keycloak/keycloak/discussions/36499). So, in most cases, with Keycloak, it is preferable to avoid the `offline_access` scope.

### 2.2. About Paths
The path of the CSRF cookie must be set to what is shared by the BFF and the assets of a web app. When only one app is served by a server, we may keep `/`, the default value. But when more than one BFF is served from the same domain, the path should be unique for each, or tokens will collide. As the CSRF cookie must be accessed by both the frontend and the BFF code, the path for each should share a common part uniquely identifying each app. For instance, for `ui` and `third-party` web apps, we could set the CSRF cookie paths to respectively `/ui` and `/third-party` with a reverse proxy exposing publicly:
- `/ui/bff/**`
- `/ui/web/**`
- `/third-party/bff/**`
- `/third-party/web/**`

Similarly, when several applications with a session (`oauth2Login` in the case of a Spring application secured using OAuth2) are served from the same domain, each app should use a path of its own for its session cookie. In the case of a Spring servlet, setting the `server.servlet.context-path` property is enough.

### 2.3. Logout
OpenID specifies two main logout mechanisms:
- RP-Initiated Logout, where:
  1. the user logs out from the Relying Party (the BFF). Precisely, the user agent (the browser) sends a `POST` request to a logout endpoint. This request being changing the server state (reason for it being a `POST`), it should be protected against CSRF.
  2. the RP ends its session for the user
  3. the RP redirects the user agent to the OpenID Provider `end_session_endpoint` with the ID token as credentials and a post-logout URI
  4. the OP ends its session for the user
  5. the OP redirects the user agent to the provided post-logout URI
- Back-Channel Logout, where the OP notifies each RP for which it has a callback URL that a user session ends. This enables to end almost instantly the session of a user on all RPs after he logged out from one. But as this direct server-to-server communication, it is possible only with clients running on a backend. A special care must be taken with Keycloak which sends Back-Channel Logout events to clients only in the case of an explicit logout, but not in case of a session expiration or revocation using the admin console: [gh-25171]( https://github.com/keycloak/keycloak/issues/25171).

### 2.4. Frontend State
The refresh token `exp` claim contains the instant at which the user's session on Keycloak expires (Epoch seconds). In the companion project, the BFFs expose this value. Front-ends display a popup 30s before the user session expire and, in case of a user interaction, send a `/ping` request to keep the session alive. Otherwise (no user interaction), the front-ends switch to the logged out state.

When the Back-Channel Logout is correctly configured, a BFF session can end before the tokens expire. If tokens are not exposed to the frontend, the access to REST resources is instantly revoked: tokens are not invalidated, but they are deleted with the BFF session.

However, for best user experience in SSO systems, we should use a messaging bus (websockets or whatever) to notify the frontend when a BFF session ended before expiration because of an external event.

### 2.5. `401` interceptor
In the case of an invalid BFF session, missing tokens, etc., the REST request from the frontend fail with a `401`.

The front-ends register a request interceptor to intercept this `Unauthorized` errors and trigger user login. In the case were the user session on Keycloak is still valid, this authorization code flow completes silently (the user is not prompted for credentials).