package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.protocol.FriendGroup
import dev.trashpanda.ytmp.protocol.FriendsOverview
import dev.trashpanda.ytmp.protocol.Person
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.ReceivedShare
import dev.trashpanda.ytmp.protocol.ShareRequest
import dev.trashpanda.ytmp.protocol.ShareResult
import dev.trashpanda.ytmp.protocol.Song
import java.sql.Connection

/**
 * Friends, groups of friends and the songs shared with them (docs/features/accounts/friends.md), between
 * accounts of this server. Friends are both ways: one asks, the other says yes. A group has an
 * owner who picks its people from their friends; anyone in it shares with all of it. A shared song
 * shows on Home of everyone it went to, until they put it away.
 */
class Social(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    class SocialException(message: String, val notFound: Boolean = false) : Exception(message)

    fun overview(accountId: String): FriendsOverview = db.use { c ->
        FriendsOverview(
            me = c.people("SELECT username, display_name FROM accounts WHERE id = ?", accountId).single(),
            friends = c.people("SELECT a.username, a.display_name FROM friends f JOIN accounts a ON a.id = f.friend_id WHERE f.account_id = ?", accountId),
            incoming = c.people("SELECT a.username, a.display_name FROM friend_requests r JOIN accounts a ON a.id = r.from_id WHERE r.to_id = ? ORDER BY r.at DESC", accountId),
            outgoing = c.people("SELECT a.username, a.display_name FROM friend_requests r JOIN accounts a ON a.id = r.to_id WHERE r.from_id = ? ORDER BY r.at DESC", accountId),
            groups = c.groupIds(accountId).mapNotNull { c.group(it) }.sortedBy { it.name.lowercase() },
            hiddenFriends = c.people(
                "SELECT a.username, a.display_name FROM share_home_hidden h JOIN accounts a ON a.id = h.target_id WHERE h.account_id = ? AND h.kind = 'friend'",
                accountId,
            ).map { it.username }.sorted(),
            hiddenGroups = c.ids("SELECT target_id FROM share_home_hidden WHERE account_id = ? AND kind = 'group'", accountId).sorted(),
        ).let { it.copy(friends = it.friends.sortedBy { p -> p.displayName.lowercase() }) }
    }

    /**
     * [accountId] asks [username] to be friends, or says yes when they asked first. True: you're
     * friends now; false: they still have to say yes.
     */
    fun request(accountId: String, username: String): Boolean = db.transaction { c ->
        val other = c.accountId(username) ?: throw SocialException("There's no $username on this server", notFound = true)
        if (other == accountId) throw SocialException("That's you")
        if (c.exists("SELECT 1 FROM friends WHERE account_id = ? AND friend_id = ?", accountId, other)) return@transaction true
        if (c.exists("SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ?", other, accountId)) {
            c.update("DELETE FROM friend_requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)", other, accountId, accountId, other)
            val now = clock()
            for ((a, b) in listOf(accountId to other, other to accountId)) {
                c.prepareStatement("INSERT INTO friends (account_id, friend_id, since) VALUES (?, ?, ?)").use { it.setString(1, a); it.setString(2, b); it.setLong(3, now); it.executeUpdate() }
            }
            return@transaction true
        }
        if (c.count("SELECT COUNT(*) FROM friend_requests WHERE from_id = ?", accountId) >= MAX_REQUESTS) throw SocialException("You've asked a lot of people already")
        if (!c.exists("SELECT 1 FROM friend_requests WHERE from_id = ? AND to_id = ?", accountId, other)) {
            c.prepareStatement("INSERT INTO friend_requests (from_id, to_id, at) VALUES (?, ?, ?)").use { it.setString(1, accountId); it.setString(2, other); it.setLong(3, clock()); it.executeUpdate() }
        }
        false
    }

    /** No longer friends with [username], or their request (or yours) is gone. */
    fun remove(accountId: String, username: String) = db.transaction { c ->
        val other = c.accountId(username) ?: throw SocialException("There's no $username on this server", notFound = true)
        c.update("DELETE FROM friends WHERE (account_id = ? AND friend_id = ?) OR (account_id = ? AND friend_id = ?)", accountId, other, other, accountId)
        c.update("DELETE FROM friend_requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)", accountId, other, other, accountId)
        c.update("DELETE FROM share_home_hidden WHERE kind = 'friend' AND ((account_id = ? AND target_id = ?) OR (account_id = ? AND target_id = ?))", accountId, other, other, accountId)
    }

    /** Whether what friend [username] shares with [accountId] shows on their Home (it stays on the friend's page). */
    fun showFriendOnHome(accountId: String, username: String, show: Boolean) = db.transaction { c ->
        val other = c.accountId(username)?.takeIf { it in c.friendIds(accountId) } ?: throw SocialException("$username isn't your friend", notFound = true)
        c.setHidden(accountId, "friend", other, !show)
    }

    /** Whether what's shared through [groupId] shows on [accountId]'s Home (it stays on the group's page). */
    fun showGroupOnHome(accountId: String, groupId: String, show: Boolean) = db.transaction { c ->
        if (groupId !in c.groupIds(accountId)) throw SocialException("You're not in that group", notFound = true)
        c.setHidden(accountId, "group", groupId, !show)
    }

    /** A new group of [accountId] with some of their friends ([members], usernames). */
    fun createGroup(accountId: String, name: String, members: List<String>): FriendGroup = db.transaction { c ->
        val clean = groupName(name)
        if (c.count("SELECT COUNT(*) FROM friend_groups WHERE owner_id = ?", accountId) >= MAX_GROUPS) throw SocialException("You have a lot of groups already")
        val people = c.members(accountId, members, allowed = c.friendIds(accountId))
        val id = Ids.random(12)
        val now = clock()
        c.prepareStatement("INSERT INTO friend_groups (id, name, owner_id, created_at) VALUES (?, ?, ?, ?)").use {
            it.setString(1, id); it.setString(2, clean); it.setString(3, accountId); it.setLong(4, now); it.executeUpdate()
        }
        for (member in people + accountId) c.addMember(id, member, now)
        c.group(id)!!
    }

    /** The owner renames [groupId] and sets who's in it: their friends, and anyone already in it. */
    fun updateGroup(accountId: String, groupId: String, name: String, members: List<String>): FriendGroup = db.transaction { c ->
        val clean = groupName(name)
        val owner = c.owner(groupId) ?: throw SocialException("That group is gone", notFound = true)
        if (owner != accountId) throw SocialException("Only the one who made the group can change it")
        val current = c.memberIds(groupId)
        val people = c.members(accountId, members, allowed = c.friendIds(accountId) + current) + accountId
        c.update("UPDATE friend_groups SET name = ? WHERE id = ?", clean, groupId)
        for (gone in current - people) c.update("DELETE FROM friend_group_members WHERE group_id = ? AND account_id = ?", groupId, gone)
        val now = clock()
        for (added in people - current) c.addMember(groupId, added, now)
        c.group(groupId)!!
    }

    /** The owner deletes [groupId]; anyone else leaves it. */
    fun leaveGroup(accountId: String, groupId: String) = db.transaction { c ->
        val owner = c.owner(groupId) ?: throw SocialException("That group is gone", notFound = true)
        if (owner == accountId) c.deleteGroup(groupId)
        else if (!c.exists("SELECT 1 FROM friend_group_members WHERE group_id = ? AND account_id = ?", groupId, accountId)) throw SocialException("You're not in that group", notFound = true)
        else {
            c.update("DELETE FROM friend_group_members WHERE group_id = ? AND account_id = ?", groupId, accountId)
            c.update("DELETE FROM share_home_hidden WHERE account_id = ? AND kind = 'group' AND target_id = ?", accountId, groupId)
        }
    }

    /** [accountId] shares a song with some of their friends and groups they're in. */
    fun share(accountId: String, request: ShareRequest): ShareResult = db.transaction { c ->
        val friends = c.friendIds(accountId)
        val direct = request.friends.distinct().map { username ->
            c.accountId(username)?.takeIf { it in friends } ?: throw SocialException("$username isn't your friend")
        }
        val mine = c.groupIds(accountId).toSet()
        val viaGroups = request.groups.distinct().flatMap { groupId ->
            if (groupId !in mine) throw SocialException("You're not in that group", notFound = true)
            c.memberIds(groupId).map { it to groupId }
        }
        // Each person gets it once: from you directly if you picked them, else through the first group.
        val recipients = LinkedHashMap<String, String>()
        for (id in direct) recipients.putIfAbsent(id, "")
        for ((id, groupId) in viaGroups) recipients.putIfAbsent(id, groupId)
        recipients.remove(accountId)
        if (recipients.isEmpty()) throw SocialException("Pick who gets it")
        val id = Ids.random(12)
        c.prepareStatement("INSERT INTO shares (id, from_id, song, note, at) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, id)
            it.setString(2, accountId)
            it.setString(3, ProtocolJson.encodeToString(Song.serializer(), request.song))
            it.setString(4, request.note.trim().take(MAX_NOTE))
            it.setLong(5, clock())
            it.executeUpdate()
        }
        c.prepareStatement("INSERT INTO share_recipients (share_id, account_id, group_id, dismissed) VALUES (?, ?, ?, FALSE)").use { insert ->
            for ((recipient, groupId) in recipients) {
                insert.setString(1, id); insert.setString(2, recipient); insert.setString(3, groupId); insert.addBatch()
            }
            insert.executeBatch()
        }
        ShareResult(id, recipients.size)
    }

    /**
     * Songs shared with [accountId] for their Home, newest first: not put away, and not from a
     * friend or through a group they took off Home.
     */
    fun received(accountId: String, limit: Int = 50): List<ReceivedShare> = db.use { c ->
        c.shares(
            """r.dismissed = FALSE
               AND NOT EXISTS (SELECT 1 FROM share_home_hidden h WHERE h.account_id = r.account_id AND h.kind = 'friend' AND h.target_id = s.from_id)
               AND NOT EXISTS (SELECT 1 FROM share_home_hidden h WHERE h.account_id = r.account_id AND h.kind = 'group' AND h.target_id = r.group_id)""",
            accountId, null, limit,
        )
    }

    /** Everything friend [username] shared with [accountId] (directly and through groups), also what they put away, newest first. */
    fun receivedFrom(accountId: String, username: String, limit: Int = MAX_HISTORY): List<ReceivedShare> = db.use { c ->
        val other = c.accountId(username) ?: throw SocialException("There's no $username on this server", notFound = true)
        c.shares("s.from_id = ?", accountId, other, limit)
    }

    /** Everything shared with [accountId] through [groupId], also what they put away, newest first. */
    fun receivedThrough(accountId: String, groupId: String, limit: Int = MAX_HISTORY): List<ReceivedShare> = db.use { c ->
        c.shares("r.group_id = ?", accountId, groupId, limit)
    }

    /** A song [accountId] put away is back on their Home (Undo). */
    fun restore(accountId: String, shareId: String) = db.transaction { c ->
        if (c.update("UPDATE share_recipients SET dismissed = FALSE WHERE share_id = ? AND account_id = ?", shareId, accountId) == 0) {
            throw SocialException("That song isn't shared with you", notFound = true)
        }
    }

    /** Songs shared with [accountId] matching [where] (with [arg] for its one `?`, if any), newest first. */
    private fun Connection.shares(where: String, accountId: String, arg: String?, limit: Int): List<ReceivedShare> =
        prepareStatement(
            """SELECT s.id, s.song, a.username, a.display_name, g.name, s.note, s.at
               FROM share_recipients r JOIN shares s ON s.id = r.share_id JOIN accounts a ON a.id = s.from_id
               LEFT JOIN friend_groups g ON g.id = r.group_id
               WHERE r.account_id = ? AND ($where) ORDER BY s.at DESC LIMIT ?""",
        ).use {
            var i = 1
            it.setString(i++, accountId)
            if (arg != null) it.setString(i++, arg)
            it.setInt(i, limit)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val song = runCatching { ProtocolJson.decodeFromString(Song.serializer(), rows.getString(2)) }.getOrNull() ?: continue
                        add(ReceivedShare(rows.getString(1), song, Person(rows.getString(3), rows.getString(4)), rows.getString(5), rows.getString(6), rows.getLong(7)))
                    }
                }
            }
        }

    private fun Connection.setHidden(accountId: String, kind: String, targetId: String, hidden: Boolean) {
        update("DELETE FROM share_home_hidden WHERE account_id = ? AND kind = ? AND target_id = ?", accountId, kind, targetId)
        if (hidden) update("INSERT INTO share_home_hidden (account_id, kind, target_id) VALUES (?, ?, ?)", accountId, kind, targetId)
    }

    /** A song shared with [accountId] is put away; one they shared is taken back from everyone. */
    fun dismiss(accountId: String, shareId: String) = db.transaction { c ->
        if (c.exists("SELECT 1 FROM shares WHERE id = ? AND from_id = ?", shareId, accountId)) {
            c.update("DELETE FROM share_recipients WHERE share_id = ?", shareId)
            c.update("DELETE FROM shares WHERE id = ?", shareId)
        } else if (c.update("UPDATE share_recipients SET dismissed = TRUE WHERE share_id = ? AND account_id = ?", shareId, accountId) == 0) {
            throw SocialException("That song isn't shared with you", notFound = true)
        }
    }

    private fun groupName(name: String): String =
        name.trim().take(MAX_GROUP_NAME).ifEmpty { throw SocialException("Give the group a name") }

    private fun Connection.members(accountId: String, usernames: List<String>, allowed: Set<String>): Set<String> {
        if (usernames.size > MAX_MEMBERS) throw SocialException("A group has at most $MAX_MEMBERS people")
        return usernames.distinct().mapNotNull { username ->
            val id = accountId(username) ?: throw SocialException("There's no $username on this server")
            if (id == accountId) null else id.takeIf { it in allowed } ?: throw SocialException("$username isn't your friend")
        }.toSet()
    }

    private fun Connection.addMember(groupId: String, accountId: String, at: Long) =
        prepareStatement("INSERT INTO friend_group_members (group_id, account_id, joined_at) VALUES (?, ?, ?)").use {
            it.setString(1, groupId); it.setString(2, accountId); it.setLong(3, at); it.executeUpdate()
        }

    private fun Connection.group(groupId: String): FriendGroup? {
        val (name, ownerId) = prepareStatement("SELECT name, owner_id FROM friend_groups WHERE id = ?").use {
            it.setString(1, groupId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getString(2) else null }
        } ?: return null
        val members = prepareStatement(
            "SELECT a.id, a.username, a.display_name FROM friend_group_members m JOIN accounts a ON a.id = m.account_id WHERE m.group_id = ? ORDER BY m.joined_at, a.username",
        ).use {
            it.setString(1, groupId)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to Person(rows.getString(2), rows.getString(3))) } }
        }
        val owner = members.firstOrNull { it.first == ownerId }?.second ?: return null
        return FriendGroup(groupId, name, owner, members.map { it.second })
    }

    private fun Connection.owner(groupId: String): String? =
        prepareStatement("SELECT owner_id FROM friend_groups WHERE id = ?").use {
            it.setString(1, groupId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun Connection.groupIds(accountId: String): List<String> = ids("SELECT group_id FROM friend_group_members WHERE account_id = ?", accountId)

    private fun Connection.memberIds(groupId: String): Set<String> = ids("SELECT account_id FROM friend_group_members WHERE group_id = ?", groupId).toSet()

    private fun Connection.friendIds(accountId: String): Set<String> = ids("SELECT friend_id FROM friends WHERE account_id = ?", accountId).toSet()

    private fun Connection.accountId(username: String): String? =
        prepareStatement("SELECT id FROM accounts WHERE username = ?").use {
            it.setString(1, username.trim().lowercase().removeSuffix("@").substringBefore('@'))
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun Connection.people(sql: String, accountId: String): List<Person> = prepareStatement(sql).use {
        it.setString(1, accountId)
        it.executeQuery().use { rows -> buildList { while (rows.next()) add(Person(rows.getString(1), rows.getString(2))) } }
    }

    companion object {
        const val MAX_NOTE = 200
        const val MAX_GROUP_NAME = 64
        const val MAX_MEMBERS = 100
        const val MAX_GROUPS = 50
        const val MAX_REQUESTS = 200
        /** Songs on a friend's or group's page. */
        const val MAX_HISTORY = 200

        /** Everything of [accountId] (an account being deleted): friends, requests, groups it made or is in, shares both ways. */
        fun forget(c: Connection, accountId: String) {
            c.update("DELETE FROM friends WHERE account_id = ? OR friend_id = ?", accountId, accountId)
            c.update("DELETE FROM friend_requests WHERE from_id = ? OR to_id = ?", accountId, accountId)
            for (group in c.ids("SELECT id FROM friend_groups WHERE owner_id = ?", accountId)) c.deleteGroup(group)
            c.update("DELETE FROM friend_group_members WHERE account_id = ?", accountId)
            c.update("DELETE FROM share_recipients WHERE account_id = ? OR share_id IN (SELECT id FROM shares WHERE from_id = ?)", accountId, accountId)
            c.update("DELETE FROM shares WHERE from_id = ?", accountId)
            c.update("DELETE FROM share_home_hidden WHERE account_id = ? OR (kind = 'friend' AND target_id = ?)", accountId, accountId)
        }

        /** The group and who's in it; songs shared through it stay, without its name. */
        private fun Connection.deleteGroup(groupId: String) {
            update("DELETE FROM friend_group_members WHERE group_id = ?", groupId)
            update("DELETE FROM friend_groups WHERE id = ?", groupId)
            update("DELETE FROM share_home_hidden WHERE kind = 'group' AND target_id = ?", groupId)
        }

        private fun Connection.update(sql: String, vararg args: String): Int = prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setString(i + 1, a) }
            s.executeUpdate()
        }

        private fun Connection.ids(sql: String, arg: String): List<String> = prepareStatement(sql).use {
            it.setString(1, arg)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

        private fun Connection.exists(sql: String, vararg args: String): Boolean = prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setString(i + 1, a) }
            s.executeQuery().use { it.next() }
        }

        private fun Connection.count(sql: String, arg: String): Int = prepareStatement(sql).use {
            it.setString(1, arg)
            it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
