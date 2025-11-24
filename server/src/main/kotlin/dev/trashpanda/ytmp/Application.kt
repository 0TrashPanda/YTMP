package dev.trashpanda.ytmp

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

// install(ContentNegotiation) {
//     json()
// }

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val duration: String
    )
    
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
   install(ContentNegotiation) {
       json()
   }
    routing {
        // Serve all static files from resources/static
        static("/") {
            resources("static")
            defaultResource("static/index.html")
        }

        // API example
        route("/api") {
            get("/play") {
                val query = call.request.queryParameters["query"] ?: ""
                call.respondText("Playing: $query")
            }

            get("/search") {
                val query = call.request.queryParameters["query"] ?: ""

                // Return some dummy songs
                val dummySongs = listOf(
                    Song("1", "Song One", "Artist A", "3:15"),
                    Song("2", "Song Two", "Artist B", "4:05"),
                    Song("3", "Another Song", "Artist C", "2:58")
                ).filter { it.title.contains(query, ignoreCase = true) }

                call.respond(dummySongs)
            }
        }
    }
}
