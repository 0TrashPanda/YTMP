package dev.trashpanda.ytmp.protocol.tsgen

import dev.trashpanda.ytmp.protocol.ApiError
import dev.trashpanda.ytmp.protocol.SongCacheSettings
import dev.trashpanda.ytmp.protocol.SongCacheStatus
import dev.trashpanda.ytmp.protocol.ConvertItemPage
import dev.trashpanda.ytmp.protocol.ConvertJob
import dev.trashpanda.ytmp.protocol.ConvertJobList
import dev.trashpanda.ytmp.protocol.ConvertPick
import dev.trashpanda.ytmp.protocol.ConvertPreview
import dev.trashpanda.ytmp.protocol.ConvertStartRequest
import dev.trashpanda.ytmp.protocol.TakeoutPreview
import dev.trashpanda.ytmp.protocol.TakeoutPreviewRequest
import dev.trashpanda.ytmp.protocol.TakeoutRule
import dev.trashpanda.ytmp.protocol.UnmatchedSongs
import dev.trashpanda.ytmp.protocol.BackupInfo
import dev.trashpanda.ytmp.protocol.AuditLogResponse
import dev.trashpanda.ytmp.protocol.DisableAccountRequest
import dev.trashpanda.ytmp.protocol.DownloadItems
import dev.trashpanda.ytmp.protocol.DownloadRequest
import dev.trashpanda.ytmp.protocol.LocalLinkRequest
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.ScreenCastRequest
import dev.trashpanda.ytmp.protocol.ScreenLinkRequest
import dev.trashpanda.ytmp.protocol.ScreenLinked
import dev.trashpanda.ytmp.protocol.ScreenPairing
import dev.trashpanda.ytmp.protocol.ScreenUnlinkRequest
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.RoomListResponse
import dev.trashpanda.ytmp.protocol.AuthServerInfo
import dev.trashpanda.ytmp.protocol.AccountInfo
import dev.trashpanda.ytmp.protocol.LoginRequest
import dev.trashpanda.ytmp.protocol.SignupRequest
import dev.trashpanda.ytmp.protocol.SessionResponse
import dev.trashpanda.ytmp.protocol.ChangePasswordRequest
import dev.trashpanda.ytmp.protocol.HostTokenRequest
import dev.trashpanda.ytmp.protocol.HostTokenResponse
import dev.trashpanda.ytmp.protocol.AccountListResponse
import dev.trashpanda.ytmp.protocol.CreateAccountRequest
import dev.trashpanda.ytmp.protocol.UpdateAccountRequest
import dev.trashpanda.ytmp.protocol.InviteListResponse
import dev.trashpanda.ytmp.protocol.AdminSettings
import dev.trashpanda.ytmp.protocol.ServerSettingOverrides
import dev.trashpanda.ytmp.protocol.AdminRoomList
import dev.trashpanda.ytmp.protocol.ServerAbout
import dev.trashpanda.ytmp.protocol.SetPasswordRequest
import dev.trashpanda.ytmp.protocol.InviteResponse
import dev.trashpanda.ytmp.protocol.LinkAuthServerRequest
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.HistoryPage
import dev.trashpanda.ytmp.protocol.EpisodeProgressList
import dev.trashpanda.ytmp.protocol.MarkEpisodeRequest
import dev.trashpanda.ytmp.protocol.AccountSettings
import dev.trashpanda.ytmp.protocol.GraphBuildStatus
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.GraphMap
import dev.trashpanda.ytmp.protocol.GraphView
import dev.trashpanda.ytmp.protocol.HistoryImport
import dev.trashpanda.ytmp.protocol.HistoryImportList
import dev.trashpanda.ytmp.protocol.RadioCrawlStatus
import dev.trashpanda.ytmp.protocol.RadioOptions
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.LocalLibraries
import dev.trashpanda.ytmp.protocol.LocalLibraryPage
import dev.trashpanda.ytmp.protocol.YoutubeAccountStatus
import dev.trashpanda.ytmp.protocol.YoutubeHistorySetting
import dev.trashpanda.ytmp.protocol.YoutubePersonalizeSetting
import dev.trashpanda.ytmp.protocol.YoutubeSignInRequest
import dev.trashpanda.ytmp.protocol.SyncedRoomsResponse
import dev.trashpanda.ytmp.protocol.MovedRoomResponse
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.PodcastSavedRequest
import dev.trashpanda.ytmp.protocol.MyPlaylists
import dev.trashpanda.ytmp.protocol.SaveSongsRequest
import dev.trashpanda.ytmp.protocol.CreatePlaylistRequest
import dev.trashpanda.ytmp.protocol.PlaylistItemRef
import dev.trashpanda.ytmp.protocol.RemovePlaylistItemsRequest
import dev.trashpanda.ytmp.protocol.MovePlaylistItemRequest
import dev.trashpanda.ytmp.protocol.RenamePlaylistRequest
import dev.trashpanda.ytmp.protocol.RoomPasswordRequest
import dev.trashpanda.ytmp.protocol.CreatePlaylistResponse
import dev.trashpanda.ytmp.protocol.SuggestionsResponse
import dev.trashpanda.ytmp.protocol.Lyrics
import dev.trashpanda.ytmp.protocol.ServerMessage
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonClassDiscriminator
import java.io.File

/**
 * Writes TypeScript declarations for the protocol by walking the kotlinx.serialization
 * descriptors, so the frontend's types always match the host's JSON.
 */
class TsGenerator {
    private val declarations = LinkedHashMap<String, String>()
    private val visiting = HashSet<String>()

    /** TS name per descriptor serial name. Sealed subclasses are named `<Parent><Variant>`. */
    private val names = HashMap<String, String>()
    private val owners = HashMap<String, String>()

    /** Shape per serial name: two different classes with the same @SerialName would be mixed up. */
    private val shapes = HashMap<String, String>()

    private fun checkUnique(d: SerialDescriptor) {
        val shape = "${d.kind}(${(0 until d.elementsCount).joinToString { d.getElementName(it) }})"
        val previous = shapes.putIfAbsent(d.serialName, shape)
        check(previous == null || previous == shape) { "Two different classes are both named '${d.serialName}' ($previous and $shape); give one another @SerialName" }
    }

    fun addRoot(descriptor: SerialDescriptor) {
        typeOf(descriptor)
    }

    fun render(): String = buildString {
        appendLine("// Generated by `./gradlew :protocol:generateTs` from the Kotlin classes in protocol/.")
        appendLine("// Do not edit by hand.")
        appendLine()
        appendLine("export const PROTOCOL_VERSION = $PROTOCOL_VERSION;")
        for (declaration in declarations.values) {
            appendLine()
            append(declaration)
        }
    }

    private fun typeOf(d: SerialDescriptor): String {
        val base = when (val kind = d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
            PrimitiveKind.BOOLEAN -> "boolean"
            is PrimitiveKind -> "number"
            StructureKind.LIST -> "${parenthesize(typeOf(d.getElementDescriptor(0)))}[]"
            StructureKind.MAP -> "Record<${typeOf(d.getElementDescriptor(0))}, ${typeOf(d.getElementDescriptor(1))}>"
            SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                checkUnique(d)
                declare(d)
                nameOf(d)
            }
            else -> error("Unsupported kind $kind for ${d.serialName}")
        }
        return if (d.isNullable) "$base | null" else base
    }

    private fun declare(d: SerialDescriptor, discriminator: Pair<String, String>? = null) {
        val name = nameOf(d)
        if (name in declarations || !visiting.add(name)) return
        val text = when (d.kind) {
            SerialKind.ENUM ->
                "export type $name = ${(0 until d.elementsCount).joinToString(" | ") { "\"${d.getElementName(it)}\"" }};\n"
            PolymorphicKind.SEALED -> declareSealed(d)
            else -> declareInterface(d, discriminator)
        }
        declarations[name] = text
        visiting.remove(name)
    }

    private fun declareInterface(d: SerialDescriptor, discriminator: Pair<String, String>?): String = buildString {
        appendLine("export interface ${nameOf(d)} {")
        if (discriminator != null) appendLine("  ${discriminator.first}: \"${discriminator.second}\";")
        for (i in 0 until d.elementsCount) {
            appendLine("  ${d.getElementName(i)}: ${typeOf(d.getElementDescriptor(i))};")
        }
        appendLine("}")
    }

    private fun declareSealed(d: SerialDescriptor): String {
        val key = d.annotations.filterIsInstance<JsonClassDiscriminator>().firstOrNull()?.discriminator ?: "type"
        // Element 1 of a sealed descriptor holds one element per subclass.
        val subclasses = d.getElementDescriptor(1).elementDescriptors.toList()
        val variantNames = subclasses.map { sub ->
            names[sub.serialName] = nameOf(d) + pascalCase(sub.serialName)
            declare(sub, key to sub.serialName)
            nameOf(sub)
        }
        return "export type ${nameOf(d)} =\n${variantNames.joinToString("\n") { "  | $it" }};\n"
    }

    private fun nameOf(d: SerialDescriptor): String {
        val serialName = d.serialName.removeSuffix("?")
        val name = names[serialName] ?: serialName.substringAfterLast('.')
        val owner = owners.getOrPut(name) { serialName }
        check(owner == serialName) { "TS name clash: '$name' is used by both $owner and $serialName" }
        return name
    }

    private fun pascalCase(serialName: String) =
        serialName.split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    private fun parenthesize(type: String) = if ('|' in type) "($type)" else type
}

fun main(args: Array<String>) {
    val out = File(args.single())
    val generator = TsGenerator()
    generator.addRoot(ClientMessage.serializer().descriptor)
    generator.addRoot(ServerMessage.serializer().descriptor)
    generator.addRoot(CreateRoomRequest.serializer().descriptor)
    generator.addRoot(CreateRoomResponse.serializer().descriptor)
    generator.addRoot(ScreenPairing.serializer().descriptor)
    generator.addRoot(ScreenLinkRequest.serializer().descriptor)
    generator.addRoot(ScreenLinked.serializer().descriptor)
    generator.addRoot(ScreenCastRequest.serializer().descriptor)
    generator.addRoot(ScreenUnlinkRequest.serializer().descriptor)
    generator.addRoot(SearchResponse.serializer().descriptor)
    generator.addRoot(ApiError.serializer().descriptor)
    generator.addRoot(HostInfo.serializer().descriptor)
    generator.addRoot(RoomListResponse.serializer().descriptor)
    generator.addRoot(AuthServerInfo.serializer().descriptor)
    generator.addRoot(AccountInfo.serializer().descriptor)
    generator.addRoot(LoginRequest.serializer().descriptor)
    generator.addRoot(SignupRequest.serializer().descriptor)
    generator.addRoot(SessionResponse.serializer().descriptor)
    generator.addRoot(ChangePasswordRequest.serializer().descriptor)
    generator.addRoot(HostTokenRequest.serializer().descriptor)
    generator.addRoot(HostTokenResponse.serializer().descriptor)
    generator.addRoot(AccountListResponse.serializer().descriptor)
    generator.addRoot(CreateAccountRequest.serializer().descriptor)
    generator.addRoot(SetPasswordRequest.serializer().descriptor)
    generator.addRoot(InviteResponse.serializer().descriptor)
    generator.addRoot(UpdateAccountRequest.serializer().descriptor)
    generator.addRoot(InviteListResponse.serializer().descriptor)
    generator.addRoot(AdminSettings.serializer().descriptor)
    generator.addRoot(ServerSettingOverrides.serializer().descriptor)
    generator.addRoot(AdminRoomList.serializer().descriptor)
    generator.addRoot(ServerAbout.serializer().descriptor)
    generator.addRoot(LinkAuthServerRequest.serializer().descriptor)
    generator.addRoot(RoleTemplate.serializer().descriptor)
    generator.addRoot(HistoryPage.serializer().descriptor)
    generator.addRoot(EpisodeProgressList.serializer().descriptor)
    generator.addRoot(MarkEpisodeRequest.serializer().descriptor)
    generator.addRoot(AccountSettings.serializer().descriptor)
    generator.addRoot(GraphView.serializer().descriptor)
    generator.addRoot(GraphMap.serializer().descriptor)
    generator.addRoot(GraphTuning.serializer().descriptor)
    generator.addRoot(GraphBuildStatus.serializer().descriptor)
    generator.addRoot(HistoryImportList.serializer().descriptor)
    generator.addRoot(HistoryImport.serializer().descriptor)
    generator.addRoot(RadioCrawlStatus.serializer().descriptor)
    generator.addRoot(RadioOptions.serializer().descriptor)
    generator.addRoot(PlayReport.serializer().descriptor)
    generator.addRoot(ArtistPage.serializer().descriptor)
    generator.addRoot(AlbumPage.serializer().descriptor)
    generator.addRoot(PlaylistPage.serializer().descriptor)
    generator.addRoot(SearchPage.serializer().descriptor)
    generator.addRoot(PodcastPage.serializer().descriptor)
    generator.addRoot(HomePage.serializer().descriptor)
    generator.addRoot(LocalLibraries.serializer().descriptor)
    generator.addRoot(LocalLibraryPage.serializer().descriptor)
    generator.addRoot(SongCacheStatus.serializer().descriptor)
    generator.addRoot(SongCacheSettings.serializer().descriptor)
    generator.addRoot(TakeoutPreviewRequest.serializer().descriptor)
    generator.addRoot(TakeoutRule.serializer().descriptor)
    generator.addRoot(TakeoutPreview.serializer().descriptor)
    generator.addRoot(UnmatchedSongs.serializer().descriptor)
    generator.addRoot(ConvertPreview.serializer().descriptor)
    generator.addRoot(ConvertStartRequest.serializer().descriptor)
    generator.addRoot(ConvertJob.serializer().descriptor)
    generator.addRoot(ConvertJobList.serializer().descriptor)
    generator.addRoot(ConvertItemPage.serializer().descriptor)
    generator.addRoot(ConvertPick.serializer().descriptor)
    generator.addRoot(BackupInfo.serializer().descriptor)
    generator.addRoot(AuditLogResponse.serializer().descriptor)
    generator.addRoot(DisableAccountRequest.serializer().descriptor)
    generator.addRoot(DownloadItems.serializer().descriptor)
    generator.addRoot(DownloadRequest.serializer().descriptor)
    generator.addRoot(LocalLinkRequest.serializer().descriptor)
    generator.addRoot(YoutubeAccountStatus.serializer().descriptor)
    generator.addRoot(YoutubeHistorySetting.serializer().descriptor)
    generator.addRoot(YoutubePersonalizeSetting.serializer().descriptor)
    generator.addRoot(YoutubeSignInRequest.serializer().descriptor)
    generator.addRoot(SyncedRoomsResponse.serializer().descriptor)
    generator.addRoot(MovedRoomResponse.serializer().descriptor)
    generator.addRoot(LikeStatus.serializer().descriptor)
    generator.addRoot(PodcastSavedRequest.serializer().descriptor)
    generator.addRoot(MyPlaylists.serializer().descriptor)
    generator.addRoot(SaveSongsRequest.serializer().descriptor)
    generator.addRoot(CreatePlaylistRequest.serializer().descriptor)
    generator.addRoot(PlaylistItemRef.serializer().descriptor)
    generator.addRoot(RemovePlaylistItemsRequest.serializer().descriptor)
    generator.addRoot(MovePlaylistItemRequest.serializer().descriptor)
    generator.addRoot(RenamePlaylistRequest.serializer().descriptor)
    generator.addRoot(RoomPasswordRequest.serializer().descriptor)
    generator.addRoot(CreatePlaylistResponse.serializer().descriptor)
    generator.addRoot(SuggestionsResponse.serializer().descriptor)
    generator.addRoot(Lyrics.serializer().descriptor)
    out.parentFile.mkdirs()
    out.writeText(generator.render())
    println("Wrote ${out.path}")
}
