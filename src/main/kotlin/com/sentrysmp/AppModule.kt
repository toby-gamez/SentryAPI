package com.sentrysmp

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.http.*

// Test-friendly configure() used by tests that call configure() inside testApplication.
fun Application.configure() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
