package com.zhulao.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class Keyword(val word: String)

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        routing {
            get("/api/health") {
                call.respond(mapOf("status" to "ok"))
            }
            get("/api/keywords") {
                val list = listOf(
                    Keyword("110"), Keyword("120"), Keyword("119"),
                    Keyword("报警"), Keyword("救护"), Keyword("火警"),
                    Keyword("儿子"), Keyword("女儿"), Keyword("闺女")
                )
                call.respond(list)
            }
        }
    }.start(wait = true)
}
