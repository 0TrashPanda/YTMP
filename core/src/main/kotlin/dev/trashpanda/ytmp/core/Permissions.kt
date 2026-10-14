package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.MAX_START_WAIT_SECONDS
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.Permission.*
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomSettings

/** Roles a room starts with when its creator has no template. See docs/features/room-management.md. */
object DefaultRoles {
    // Listeners skip by voting when the room votes to skip (and right away when it doesn't); DJs always right away.
    private val listener = listOf(ADD_SONGS, REMOVE_OWN, PLAY_PAUSE, VOTE_SKIP, LISTEN_LOCALLY, CLONE_ROOM)
    private val dj = listener - VOTE_SKIP + listOf(SKIP, PLAY_NOW, REORDER, REMOVE_OTHERS, SEEK, REPEAT, CHANGE_OUTPUTS, OUTPUT_VOLUME, START_RADIO, AUTOPLAY_FROM_HERE)

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
        val s = template.settings
        return RoleTemplate(
            roles,
            RoomSettings(
                defaultGuestRole = s.defaultGuestRole.takeIf { it in ids } ?: fallback,
                defaultAccountRole = s.defaultAccountRole.takeIf { it in ids } ?: fallback,
                autoplay = s.autoplay,
                fairOrder = s.fairOrder,
                voteSkip = s.voteSkip,
                voteSkipPercent = s.voteSkipPercent.coerceIn(1, 100),
                maxSongsPerPerson = s.maxSongsPerPerson.coerceIn(0, MAX_LIMIT),
                maxSongMinutes = s.maxSongMinutes.coerceIn(0, MAX_LIMIT),
                noRepeatMinutes = s.noRepeatMinutes.coerceIn(0, MAX_LIMIT),
                startWaitSeconds = s.startWaitSeconds.coerceIn(0, MAX_START_WAIT_SECONDS),
                karaoke = s.karaoke,
            ),
        )
    }

    /** What rooms and templates saved without a list of known permissions knew: everything before radio. */
    val BEFORE_RADIO: Set<Permission> = setOf(
        ADD_SONGS, PLAY_NOW, REMOVE_OWN, REMOVE_OTHERS, REORDER, PLAY_PAUSE, SKIP, SEEK, LISTEN_LOCALLY,
        CHANGE_OUTPUTS, OUTPUT_VOLUME, KICK, BAN, ASSIGN_ROLES, EDIT_ROLES, CHANGE_SETTINGS,
    )

    /**
     * Gives roles the permissions added since they were saved (when only [known] existed):
     * a role that had every permission (an admin role) gets all new ones, and a default role
     * nobody changed gets what that default role has now. Since *Vote to skip* exists, the
     * default Listener skips by voting instead of right away, and any role that could skip may
     * vote too. Other roles stay as they are.
     */
    fun upgrade(roles: List<Role>, known: Set<Permission>): List<Role> {
        val added = Permission.entries.toSet() - known
        if (added.isEmpty()) return roles
        val defaults = DefaultRoles.template.roles.associateBy { it.id }
        return roles.map { role ->
            val have = role.permissions.toSet()
            val default = defaults[role.id]?.permissions?.toSet()
            val newVoting = VOTE_SKIP in added
            when {
                have.containsAll(known) -> role.copy(permissions = role.permissions + added)
                default != null && have == default intersect known -> role.copy(permissions = role.permissions + (default intersect added))
                default != null && newVoting && OLD_LISTENER intersect known == have -> role.copy(permissions = default.toList())
                newVoting && SKIP in have -> role.copy(permissions = role.permissions + VOTE_SKIP)
                else -> role
            }
        }
    }

    /** The default Listener before *Vote to skip* (it could skip right away). */
    private val OLD_LISTENER = setOf(ADD_SONGS, REMOVE_OWN, PLAY_PAUSE, SKIP, LISTEN_LOCALLY, CLONE_ROOM)

    fun upgrade(template: RoleTemplate): RoleTemplate =
        template.copy(roles = upgrade(template.roles, template.knownPermissions?.toSet() ?: BEFORE_RADIO), knownPermissions = Permission.entries)

    fun validColor(color: String): String = color.trim().lowercase().takeIf { COLOR.matches(it) } ?: "#949ba4"

    /** The highest value of a queue limit (songs or minutes). */
    const val MAX_LIMIT = 1000

    const val MAX_ROLES = 25
    const val MAX_ROLE_NAME = 32
    private val COLOR = Regex("#[0-9a-f]{6}")
}
