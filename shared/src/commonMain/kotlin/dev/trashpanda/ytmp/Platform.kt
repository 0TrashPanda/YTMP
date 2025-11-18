package dev.trashpanda.ytmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform