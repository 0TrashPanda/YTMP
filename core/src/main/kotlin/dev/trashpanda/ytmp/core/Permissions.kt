package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.Permission.*
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomSettings

/** Roles a room starts with when its creator has no template. See docs/features/room-management.md. */
object DefaultRoles {
    private val listener = listOf(ADD_SONGS, REMOVE_OWN, PLAY_PAUSE, SKIP, LISTEN_LOCALLY)
    private val dj = listener + listOf(PLAY_NOW, REORDER, REMOVE_OTHERS, SEEK, CHANGE_OUTPUTS, OUTPUT_VOLUME, START_RADIO, AUTOPLAY_FROM_HERE)

    val template = RoleTemplate(
        roles = listOf(
            Role("admin", "Admin", "#f23f43", Permission.entries.toList()),
            Role("dj", "DJ", "#5865f2", dj),
            Role("listener", "Listener", "#949ba4", listener),
        ),
        settings = RoomSettings(defaultGuestRole = "listener", defaultAccountRole = "listener"),
    )
}

object Permissions {
    /** What someone can do: their role, plus [allow], minus [deny]. The owner can do everything. */
    fun effective(role: Role?, allow: Collection<Permission>, deny: Collection<Permission>, isOwner: Boolean): Set<Permission> =
        if (isOwner) Permission.entries.toSet() else (role?.permissions.orEmpty().toSet() + allow) - deny.toSet()

    /** A template that can be used as-is: valid colors, unique ids, defaults that exist. */
    fun sanitize(template: RoleTemplate): RoleTemplate {
        val roles = template.roles
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_ROLES)
            .map { it.copy(name = it.name.trim().take(MAX_ROLE_NAME), color = validColor(it.color), permissions = it.permissions.distinct()) }
        if (roles.isEmpty()) return DefaultRoles.template
        val ids = roles.map { it.id }.toSet()
        val fallback = roles.last().id
        return RoleTemplate(
            roles,
            RoomSettings(
                defaultGuestRole = template.settings.defaultGuestRole.takeIf { it in ids } ?: fallback,
                defaultAccountRole = template.settings.defaultAccountRole.takeIf { it in ids } ?: fallback,
                autoplay = template.settings.autoplay,
            ),
        )
    }

    fun validColor(color: String): String = color.trim().lowercase().takeIf { COLOR.matches(it) } ?: "#949ba4"

    const val MAX_ROLES = 25
    const val MAX_ROLE_NAME = 32
    private val COLOR = Regex("#[0-9a-f]{6}")
}
