package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request/response bodies of the host's HTTP API (`/api/...`).

/** `POST /api/rooms` */
@Serializable
@SerialName("CreateRoomRequest")
data class CreateRoomRequest(val name: String)

@Serializable
@SerialName("CreateRoomResponse")
data class CreateRoomResponse(val code: String, val ownerToken: String)

/** `GET /api/search?q=` */
@Serializable
@SerialName("SearchResponse")
data class SearchResponse(val items: List<Song>)

/** Error body of every `/api` endpoint. */
@Serializable
@SerialName("ApiError")
data class ApiError(val error: ErrorInfo)
