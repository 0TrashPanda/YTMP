package dev.trashpanda.ytmp.routes

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.*
import io.ktor.client.*
import io.ktor.client.call.*

fun Route.searchRoutes(http: HttpClient) {

    route("/api/queue") {
        get("/playnext") {
            val query = call.request.queryParameters["q"] ?: ""
            if (query.isBlank()) {
                call.respond(emptyList<Any>())
                return@get
            }

            val result = http.get("http://localhost:8000/search") {
                parameter("q", query)
                header("x-api-key", "devkey") // your API key
            }.bodyAsText()
            call.respondText(result, ContentType.Application.Json)
        }