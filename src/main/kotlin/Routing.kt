package com.agh

import io.ktor.resources.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Test(
    val id: Int,
    val name: String,
)

fun Application.configureRouting() {
    install(Resources)
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        get<Articles> { article ->
            // Get all articles ...
            call.respond(
                Test(
                    id = 1,
                    name = "Sample Article sorted by ${article.sort}",
                ),
            )
        }
    }
}

@Serializable
@Resource("/articles")
class Articles(
    val sort: String? = "new",
)
