package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Radio choices and the song graph, see docs/features/history-and-recommendations.md#radio--mix
// and #song-graph.

/** Where a radio's songs come from. */
@Serializable
@SerialName("RadioFrom")
enum class RadioFrom {
    /** YouTube Music's radio with the song graph's songs mixed in. */
    @SerialName("mix") MIX,

    /** Only YouTube Music's radio. */
    @SerialName("ytm") YTM,

    /** Only the song graph: listening (yours, everyone's) and the stored radios. */
    @SerialName("graph") GRAPH,

    /** Only songs that play without internet here: local music and saved songs. */
    @SerialName("device") DEVICE,
}

/**
 * How a radio is made (Start radio, Autoplay from here, Find similar, the autoplay queue). The
 * page sends the whole of it: the defaults of *Settings → Radio* with what was picked when
 * holding *Start radio*. The room keeps it for the radio's autoplay.
 */
@Serializable
@SerialName("RadioOptions")
data class RadioOptions(
    val from: RadioFrom = RadioFrom.MIX,
    /** -1: songs you know; 1: songs you haven't heard; 0: no preference. */
    val discover: Double = 0.0,
    /** YouTube Music's radio as the room owner's account (their taste); false: without an account; null: their setting. */
    val withAccount: Boolean? = null,
    /** The room owner's own listening counts; false: only everyone's on their server. */
    val myTaste: Boolean = true,
    /** At most this many songs per artist (0: any). */
    val perArtist: Int = 0,
    /** Leave out songs the owner played in the last this many hours (0: only what the room just played). */
    val recentHours: Int = 0,
    /** Leave out songs the owner skips more often than they hear them. */
    val leaveOutSkipped: Boolean = false,
    /** One song from the song graph after every this many of YouTube Music's ([RadioFrom.MIX]). */
    val mixEvery: Int = 2,
    /** What a YouTube Music radio becomes when YouTube Music doesn't answer: [RadioFrom.GRAPH] or [RadioFrom.DEVICE]. */
    val offline: RadioFrom = RadioFrom.GRAPH,
    /** Songs never put in a radio (their IDs; Dislike's *never in radios*). */
    val blockedSongs: List<String> = emptyList(),
    /** Artists never put in a radio (their names, Dislike's *don't play this artist*). */
    val blockedArtists: List<String> = emptyList(),
)

/**
 * How much each part of the song graph counts. Kept per account (`GET`/`PUT
 * /api/account/graph-tuning`); the graph view's sliders change it.
 */
@Serializable
@SerialName("GraphTuning")
data class GraphTuning(
    /** Your own listening in YTMP. */
    val personal: Double = 2.0,
    /** Everyone's listening on the server. */
    val server: Double = 1.0,
    /** Plays imported from Google Takeout (yours and everyone's). */
    val imported: Double = 1.0,
    /** YouTube Music's radios made without an account. */
    val ytm: Double = 1.5,
    /** YouTube Music's radios made with your account. */
    val ytmAccount: Double = 0.5,
    /** Songs close together in a YouTube Music radio (not the seed: each other). */
    val radioNear: Double = 0.5,
    /** A song 2 or 3 songs after another in your listening (right after is [personal]). */
    val listenNear: Double = 0.3,
    /** A Takeout link that happened only once and no radio confirms (a jump to other music, often). */
    val oneOff: Double = 0.3,
    /** A play in a party, against one alone. */
    val party: Double = 0.6,
    /** How much a skip takes off. */
    val skip: Double = 1.0,
    /** A song played before the seed, against one after it. */
    val reverse: Double = 0.5,
    /** Neighbours of the seed's neighbours, and further along (each step this much of the one before). */
    val secondHop: Double = 0.35,
    /** Extra for songs in the seed's group (songs often heard together, much like a genre): 1 counts them double. */
    val sameGroup: Double = 1.0,
    /** After this many days an edge counts half. */
    val halfLifeDays: Double = 180.0,
    /** How far down a YouTube Music radio still counts (1–50). */
    val radioDepth: Int = 50,
)

/** `POST /api/auth/radio-hints` (a host, with the account's host token): what the song graph says about a radio. */
@Serializable
@SerialName("RadioHintsRequest")
data class RadioHintsRequest(
    val seed: String,
    /** The radio's songs so far (YouTube Music's): which of them [RadioHints.heard] and [RadioHints.avoid] are about. */
    val candidates: List<String> = emptyList(),
    val options: RadioOptions = RadioOptions(),
    /** How many songs from the graph. */
    val limit: Int = 50,
)

@Serializable
@SerialName("RadioHints")
data class RadioHints(
    /** Songs that go with the seed in the song graph, best first. */
    val similar: List<Song>,
    /** Of the candidates and [similar]: the ones the account has heard. */
    val heard: List<String> = emptyList(),
    /** Of the candidates and [similar]: the ones to leave out (skipped a lot, played lately). */
    val avoid: List<String> = emptyList(),
)

/**
 * `GET /api/auth/graph` (a phone, with the account's host token): the whole song graph as the
 * account sees it, kept on the phone for radios without the server (the phone matches its own
 * songs to it, and ranks like the server, `core/GraphRank`). Compact: links are rows of numbers.
 */
@Serializable
@SerialName("GraphFull")
data class GraphFull(
    val now: Long,
    val tuning: GraphTuning,
    val use: HistoryRadio,
    /** The songs; links point to them by their place in this list. */
    val nodes: List<GraphFullNode>,
    /**
     * The links, each `[from, to, kind, solo, shared, skips, seen, bestRank (-1: none),
     * rankSum, updatedAt, confirmed (0/1)]`, kind as [EdgeKind]'s place in its list.
     */
    val edges: List<List<Long>>,
    /** Each song's group (as radios on the server use them; -1: none), in [nodes]' order. */
    val groups: List<Int> = emptyList(),
    /** What it's made from and how it's weighed: asked again with `?known=`, the server says when it's still the same (304). */
    val version: String = "",
)

/** A song of [GraphFull]: its versions' IDs, and its title-and-artist key and length, to match songs that aren't one of them. */
@Serializable
@SerialName("GraphFullNode")
data class GraphFullNode(val sources: List<String>, val key: String, val durationMs: Long)

/** How a stored radio was fetched. */
@Serializable
@SerialName("RadioWay")
enum class RadioWay {
    /** Played in a room (Start radio, autoplay). */
    @SerialName("room") ROOM,

    /** Fetched to learn from (the radio crawl). */
    @SerialName("crawl") CRAWL,

    /** Find similar, Related. */
    @SerialName("similar") SIMILAR,
}

/** `POST /api/auth/radio-fetch` (a phone, with the account's host token): a YouTube Music radio it fetched, to learn from. */
@Serializable
@SerialName("RadioFetchReport")
data class RadioFetchReport(
    val seed: String,
    /** The radio as YouTube Music gave it, in order. */
    val songs: List<Song>,
    /** Made as the account (its YouTube Music sign-in); false: without an account. */
    val withAccount: Boolean,
    val way: RadioWay = RadioWay.ROOM,
)

// --- the graph view -------------------------------------------------------------------

/** Where an edge comes from. */
@Serializable
@SerialName("EdgeKind")
enum class EdgeKind {
    @SerialName("personal") PERSONAL,
    @SerialName("server") SERVER,
    @SerialName("imported") IMPORTED,
    @SerialName("server_imported") SERVER_IMPORTED,
    @SerialName("ytm") YTM,
    @SerialName("ytm_account") YTM_ACCOUNT,
    @SerialName("radio_near") RADIO_NEAR,
    @SerialName("personal_near") PERSONAL_NEAR,
    @SerialName("imported_near") IMPORTED_NEAR,
}

/**
 * A song in the graph view. [plays]: how often you played it (with imported plays). [sources]:
 * all its versions' IDs (`ytm:…`, `local:…`): the page shows a cover its own host has (a music
 * file's, a saved song's) before YouTube's. [group]: its group in the whole graph (songs often
 * heard together, much like a genre), as radios use it; null while it isn't known yet.
 */
@Serializable
@SerialName("GraphNode")
data class GraphNode(val id: String, val song: Song, val plays: Int = 0, val local: Boolean = false, val sources: List<String> = emptyList(), val group: Int? = null)

/** An edge with its raw counts: the page weighs it with the sliders ([GraphTuning]). */
@Serializable
@SerialName("GraphEdge")
data class GraphEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val solo: Int = 0,
    val shared: Int = 0,
    val skips: Int = 0,
    /**
     * YouTube Music edges: in how many fetches of the seed's radio, the best place, and the
     * places added up. Close together in a radio: in how many, the best place, the distances added up.
     */
    val seen: Int = 0,
    val bestRank: Int? = null,
    val rankSum: Int = 0,
    val updatedAt: Long,
    /** Imported links: a radio also puts the two songs together (a one-off then counts fully). */
    val confirmed: Boolean = false,
)

/** `GET /api/account/graph?seed=` (around one song) or without a seed (your most played songs). */
@Serializable
@SerialName("GraphView")
data class GraphView(
    /** The seed's node, or null for the overview. */
    val seed: String?,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val tuning: GraphTuning,
    /** Server time, for how old edges are. */
    val now: Long,
)

/** A song in the whole-graph view, where the server laid it out. */
@Serializable
@SerialName("GraphMapNode")
data class GraphMapNode(
    /** A song ID of it (YouTube Music's when it has one), for *Show around this song*. */
    val id: String,
    val title: String,
    val artist: String,
    /** How often you played it (not skipped), and when first. */
    val plays: Int = 0,
    val firstAt: Long? = null,
    val x: Double,
    val y: Double,
    /** Its place in [GraphMap.groups]. */
    val group: Int,
    /** Its place in [GraphMap.areas]: the coarser groups the map is laid out by (a group's songs are mostly in one). */
    val area: Int = 0,
)

/** A group of songs often heard together (much like a genre), named after its most played artists. */
@Serializable
@SerialName("GraphMapGroup")
data class GraphMapGroup(val songs: Int, val artists: List<String>)

/**
 * `GET /api/account/graph/map`: the whole graph as you see it, laid out and in groups by the
 * server, from all of it at once (old plays count as much as new ones). Each link says how much
 * it counts from each part, weighed with your [GraphTuning]: the page shows any of the parts
 * together without asking again, and the songs stay where they are.
 */
@Serializable
@SerialName("GraphMap")
data class GraphMap(
    val nodes: List<GraphMapNode>,
    /** The links: [from] and [to] are places in [nodes]. */
    val from: List<Int>,
    val to: List<Int>,
    /** How much each link counts from your own listening (0: none). */
    val mine: List<Double>,
    /** From everyone else's listening on the server (their own, not yours). */
    val others: List<Double>,
    /** From YouTube Music's radios (the radio crawl's too). */
    val radios: List<Double>,
    /** Biggest first. */
    val groups: List<GraphMapGroup>,
    /** Coarser groups (about a dozen big ones), biggest first: the map's areas, a colour each. */
    val areas: List<GraphMapGroup> = emptyList(),
    val madeAt: Long,
)

/** `GET /api/account/graph/status`: the song graph being built again (after an import, a deletion, a new version). */
@Serializable
@SerialName("GraphBuildStatus")
data class GraphBuildStatus(
    val building: Boolean,
    /** A rebuild is asked for and starts in a moment. */
    val waiting: Boolean = false,
    /** What it does now ("Reading your listening", …). */
    val step: String? = null,
    /** How far it got in all, 0–1. */
    val fraction: Double = 0.0,
    val startedAt: Long? = null,
    /** When the last rebuild ended (since the server started), or null. */
    val builtAt: Long? = null,
)

// --- Takeout and the radio crawl ------------------------------------------------------

/** One Google Takeout import into your history. */
@Serializable
@SerialName("HistoryImport")
data class HistoryImport(
    val id: String,
    val at: Long,
    /** Plays added (the ones already in your history, from YTMP itself, are left out). */
    val plays: Int,
    /** Plays left out because your history already had them. */
    val duplicates: Int,
    val firstAt: Long,
    val lastAt: Long,
)

/** `GET /api/account/imports` */
@Serializable
@SerialName("HistoryImportList")
data class HistoryImportList(val imports: List<HistoryImport>)

/** `GET`/`PUT /api/admin/radio-crawl`: learning YouTube Music's radios of the songs here, slowly. */
@Serializable
@SerialName("RadioCrawlStatus")
data class RadioCrawlStatus(
    val enabled: Boolean,
    /** Seconds between two radios. */
    val paceSeconds: Int,
    /** A song's radio is fetched again after this many days. */
    val refreshDays: Int,
    /** Songs with a stored radio (younger than [refreshDays]). */
    val done: Int = 0,
    /** Songs to crawl: local music with a YouTube Music ID and saved songs. */
    val total: Int = 0,
    /** Why it waits, or null ("YouTube asked to confirm you're not a bot", …). */
    val waiting: String? = null,
    /** When it waits until, or null. */
    val until: Long? = null,
    val lastAt: Long? = null,
    /** Also in the background while the phone charges on Wi-Fi (overnight); null: this host can't (the server always runs). */
    val overnight: Boolean? = null,
)
