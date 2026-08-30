# ktor-template

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|
| [Content Negotiation](https://start.ktor.io/p/io.ktor/server-content-negotiation) | Provides automatic content conversion according to Content-Type and Accept headers |
| [Koog](https://start.ktor.io/p/org.jetbrains/server-koog) | Integrate LLMs and build AI Agents with Koog framework |
| [Koin](https://start.ktor.io/p/io.insert-koin/server-koin) | Provides dependency injection |
| [Jackson](https://start.ktor.io/p/io.ktor/server-jackson) | Handles JSON serialization using Jackson library |
| [Call Logging](https://start.ktor.io/p/io.ktor/server-call-logging) | Logs client requests |
| [Webjars](https://start.ktor.io/p/io.ktor/server-webjars) | Bundles static assets into your built JAR file |
| [Authentication](https://start.ktor.io/p/io.ktor/server-auth) | Provides extension point for handling the Authorization header |
| [Authentication Basic](https://start.ktor.io/p/io.ktor/server-auth-basic) | Handles 'Basic' username / password authentication scheme |
| [Forwarded Headers](https://start.ktor.io/p/io.ktor/server-forwarded-header-support) | Allows handling proxied headers (X-Forwarded-*) |
| [Default Headers](https://start.ktor.io/p/io.ktor/server-default-headers) | Adds a default set of headers to HTTP responses |
| [Compression](https://start.ktor.io/p/io.ktor/server-compression) | Compresses responses using encoding algorithms like GZIP |
| [CORS](https://start.ktor.io/p/io.ktor/server-cors) | Enables Cross-Origin Resource Sharing (CORS) |


## Building & Running
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```
