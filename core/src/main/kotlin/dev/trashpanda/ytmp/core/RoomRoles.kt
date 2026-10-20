package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.Role

/**
 * A room's roles, ranked highest first, and what people may do with them
 * (docs/features/rooms-and-roles.md): permissions, and Discord-style ranking, where you only
 * manage people and roles below your own. Only used with the room locked.
 */
internal class RoomRoles(initial: List<Role>) {
    private val roles = ArrayList(initial)

    val all: List<Role> get() = roles.toList()

    /** The top role: the owner's, and the admins'. */
    val top: Role get() = roles.first()

    fun has(roleId: String) = roles.any { it.id == roleId }

    fun replaceAll(with: List<Role>) {
        roles.clear()
        roles += with
    }

    fun can(member: Member, permission: Permission): Boolean =
        permission in Permissions.effective(roles.firstOrNull { it.id == member.roleId }, member.allow, member.deny, member.isOwner)

    fun need(member: Member, permission: Permission): ErrorInfo? =
        if (can(member, permission)) null else ErrorInfo(ErrorCode.PERMISSION_DENIED, "Your role doesn't allow this")

    /** Runs the room: the owner, or someone with the top role. */
    fun isAdmin(member: Member) = member.isOwner || member.roleId == roles.firstOrNull()?.id

    /** 0 is the top role; the owner is above every role. */
    private fun rank(member: Member): Int =
        if (member.isOwner) -1 else roles.indexOfFirst { it.id == member.roleId }.let { if (it < 0) roles.size else it }

    /** Like Discord: you can only manage people whose role is below yours. Nobody manages the owner. */
    fun outranks(actor: Member, target: Member) = !target.isOwner && (actor.isOwner || rank(actor) < rank(target))

    private fun canManageRole(actor: Member, roleIndex: Int) = actor.isOwner || roleIndex > rank(actor)

    /** You can't hand out permissions you don't have yourself. */
    fun cannotGrant(actor: Member, permissions: Set<Permission>): ErrorInfo? =
        if (permissions.all { can(actor, it) }) null else ErrorInfo(ErrorCode.PERMISSION_DENIED, "You can't give permissions you don't have")

    /** Gives [target] the role [roleId] (one below [actor]'s). */
    fun assign(actor: Member, target: Member, roleId: String): ErrorInfo? {
        if (!outranks(actor, target)) return outranked()
        val index = roles.indexOfFirst { it.id == roleId }
        if (index < 0) return notFound()
        if (!canManageRole(actor, index)) return denied("You can only give roles below your own")
        target.roleId = roleId
        return null
    }

    /**
     * The role commands: create, change, delete and move a role. [defaults] are the roles new
     * people get (they can't be deleted); [deleted] is told which role went, so people who had it
     * get a default one. Emits [Event.RolesChanged].
     */
    fun handle(actor: Member, command: Command, defaults: Set<String>, emit: (Event) -> Unit, deleted: (String) -> Unit): ErrorInfo? {
        need(actor, Permission.EDIT_ROLES)?.let { return it }
        when (command) {
            is Command.CreateRole -> {
                val name = command.name.trim()
                validName(name)?.let { return it }
                if (roles.size >= Permissions.MAX_ROLES) return invalid("A room can have at most ${Permissions.MAX_ROLES} roles")
                cannotGrant(actor, command.permissions.toSet())?.let { return it }
                roles += Role(Ids.short(), name, Permissions.validColor(command.color), command.permissions.distinct())
            }
            is Command.UpdateRole -> {
                val index = roles.indexOfFirst { it.id == command.role.id }
                if (index < 0) return notFound()
                if (!canManageRole(actor, index)) return denied("You can only change roles below your own")
                val name = command.role.name.trim()
                validName(name)?.let { return it }
                cannotGrant(actor, command.role.permissions.toSet() - roles[index].permissions.toSet())?.let { return it }
                roles[index] = Role(command.role.id, name, Permissions.validColor(command.role.color), command.role.permissions.distinct())
            }
            is Command.DeleteRole -> {
                val index = roles.indexOfFirst { it.id == command.roleId }
                if (index < 0) return notFound()
                if (!canManageRole(actor, index)) return denied("You can only delete roles below your own")
                if (command.roleId in defaults) return invalid("New people get this role. Pick another default role first.")
                roles.removeAt(index)
                emit(Event.RolesChanged(all))
                deleted(command.roleId)
                return null
            }
            is Command.MoveRole -> {
                val from = roles.indexOfFirst { it.id == command.roleId }
                if (from < 0) return notFound()
                val to = command.toIndex.coerceIn(0, roles.size - 1)
                if (!canManageRole(actor, from) || !canManageRole(actor, to)) return denied("You can only move roles below your own")
                roles.add(to, roles.removeAt(from))
            }
            else -> return null
        }
        emit(Event.RolesChanged(all))
        return null
    }

    private fun validName(name: String): ErrorInfo? =
        if (name.isEmpty() || name.length > Permissions.MAX_ROLE_NAME) invalid("Role names are 1–${Permissions.MAX_ROLE_NAME} characters") else null

    private fun invalid(message: String) = ErrorInfo(ErrorCode.INVALID, message)

    private fun denied(message: String) = ErrorInfo(ErrorCode.PERMISSION_DENIED, message)

    companion object {
        fun outranked() = ErrorInfo(ErrorCode.PERMISSION_DENIED, "Their role is not below yours")

        fun notFound() = ErrorInfo(ErrorCode.NOT_FOUND, "No such role")
    }
}
