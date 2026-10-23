package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.AccountTokens
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64
import java.util.HexFormat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Accounts, login sessions, invites and the signing key, in the server database. */
class Accounts(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    data class Account(
        val id: String,
        val username: String,
        val displayName: String,
        val passwordHash: String,
        val isAdmin: Boolean,
        /** 0: enabled; -1: disabled until an admin enables it; else disabled until that time (ms). */
        val disabledUntil: Long = 0,
    ) {
        fun disabledAt(now: Long) = disabledUntil == Accounts.FOREVER || disabledUntil > now
    }

    class AccountException(message: String) : Exception(message)

    /**
     * Disables an account until [until] (ms), or until an admin enables it ([FOREVER]); 0 enables
     * it. A disabled account is logged out everywhere and can't log in. Not the only enabled admin.
     */
    fun setDisabledUntil(accountId: String, until: Long) = db.transaction { c ->
        if (until != 0L) {
            val others = c.createStatement().use { s ->
                s.executeQuery("SELECT id, disabled_until FROM accounts WHERE is_admin = TRUE").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getLong(2)) }
                }
            }.filter { (id, disabled) -> id != accountId && !(disabled == FOREVER || disabled > clock()) }
            val isAdmin = c.findBy("id", accountId)?.isAdmin == true
            if (isAdmin && others.isEmpty()) throw AccountException("This is the only server admin that isn't disabled. Make someone else admin first.")
        }
        c.prepareStatement("UPDATE accounts SET disabled_until = ? WHERE id = ?").use { it.setLong(1, until); it.setString(2, accountId); it.executeUpdate() }
        if (until != 0L) c.prepareStatement("DELETE FROM sessions WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
        Unit
    }

    fun count(): Int = db.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM accounts").use { it.next(); it.getInt(1) } } }

    fun find(username: String): Account? = db.use { c -> c.findBy("username", username.trim().lowercase()) }

    fun byId(id: String): Account? = db.use { c -> c.findBy("id", id) }

    fun list(): List<Account> = db.use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT * FROM accounts ORDER BY username").use { rows -> buildList { while (rows.next()) add(rows.toAccount()) } } }
    }

    /**
     * Creates an account. [admin] null means "admin if it's the first account". With an
     * [invite], the invite must be valid and is used up, in the same transaction.
     */
    fun create(username: String, password: String, displayName: String?, admin: Boolean?, invite: String? = null): Account {
        val name = username.trim().lowercase()
        if (!USERNAME.matches(name)) throw AccountException("Usernames are 2–32 characters: letters, digits, '.', '_' or '-'")
        val display = displayName?.trim()?.ifEmpty { null } ?: username.trim()
        if (display.length > 32) throw AccountException("Display names are at most 32 characters")
        checkPassword(password)
        val hash = Passwords.hash(password)
        return db.transaction { c ->
            if (c.findBy("username", name) != null) throw AccountException("That username is taken")
            val first = c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM accounts").use { it.next(); it.getInt(1) == 0 } }
            val account = Account(Ids.random(16), name, display, hash, admin ?: first)
            if (invite != null && !first) c.useInvite(invite, account.id)
            c.prepareStatement("INSERT INTO accounts (id, username, display_name, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?, ?)").use {
                it.setString(1, account.id)
                it.setString(2, account.username)
                it.setString(3, account.displayName)
                it.setString(4, account.passwordHash)
                it.setBoolean(5, account.isAdmin)
                it.setLong(6, clock())
                it.executeUpdate()
            }
            account
        }
    }

    /** The account, if the password is right. */
    fun login(username: String, password: String): Account? {
        val account = find(username)
        // Hash anyway when the account doesn't exist, so timing doesn't tell which usernames exist.
        val ok = Passwords.verify(password, account?.passwordHash ?: DUMMY_HASH)
        return account.takeIf { ok }
    }

    /** Sets a new password and logs the account out everywhere. */
    fun setPassword(accountId: String, password: String) {
        checkPassword(password)
        val hash = Passwords.hash(password)
        db.transaction { c ->
            c.prepareStatement("UPDATE accounts SET password_hash = ? WHERE id = ?").use { it.setString(1, hash); it.setString(2, accountId); it.executeUpdate() }
            c.prepareStatement("DELETE FROM sessions WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
        }
    }

    /** Changes the name others see. */
    fun rename(accountId: String, displayName: String) {
        val display = displayName.trim()
        if (display.isEmpty() || display.length > 32) throw AccountException("Display names are 1–32 characters")
        db.use { c ->
            c.prepareStatement("UPDATE accounts SET display_name = ? WHERE id = ?").use { it.setString(1, display); it.setString(2, accountId); it.executeUpdate() }
        }
    }

    /** Makes an account a server admin, or not. There's always at least one admin. */
    fun setAdmin(accountId: String, admin: Boolean) = db.transaction { c ->
        if (!admin && c.isLastAdmin(accountId)) throw AccountException("This is the only server admin. Make someone else admin first.")
        c.prepareStatement("UPDATE accounts SET is_admin = ? WHERE id = ?").use { it.setBoolean(1, admin); it.setString(2, accountId); it.executeUpdate() }
        Unit
    }

    /**
     * Deletes an account and everything kept for it: logins, settings, listening history,
     * podcast progress, its rooms' copies ([globalId] is `username@issuer`). Not the last admin.
     */
    fun delete(accountId: String, globalId: String) = db.transaction { c ->
        if (c.isLastAdmin(accountId)) throw AccountException("This is the only server admin. Make someone else admin first.")
        for (table in listOf("sessions", "account_data", "plays", "episode_progress", "song_edges")) {
            c.prepareStatement("DELETE FROM $table WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
        }
        c.prepareStatement("DELETE FROM invites WHERE created_by = ? AND used_by IS NULL").use { it.setString(1, accountId); it.executeUpdate() }
        c.prepareStatement("DELETE FROM room_copies WHERE account = ?").use { it.setString(1, globalId); it.executeUpdate() }
        c.prepareStatement("DELETE FROM accounts WHERE id = ?").use { it.setString(1, accountId); it.executeUpdate() }
        Unit
    }

    /** When each account was made, and its logins here: how many, and when one was last used. */
    fun activity(): Map<String, Activity> = db.use { c ->
        val created = c.createStatement().use { s ->
            s.executeQuery("SELECT id, created_at FROM accounts").use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) } }
        }
        val logins = c.createStatement().use { s ->
            s.executeQuery("SELECT account_id, COUNT(*), MAX(last_used) FROM sessions GROUP BY account_id").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2) to rows.getLong(3)) }
            }
        }
        created.mapValues { (id, at) -> Activity(at, logins[id]?.second, logins[id]?.first ?: 0) }
    }

    data class Activity(val createdAt: Long, val lastSeen: Long?, val logins: Int)

    private fun Connection.isLastAdmin(accountId: String): Boolean {
        val admins = createStatement().use { s -> s.executeQuery("SELECT id FROM accounts WHERE is_admin = TRUE").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } } }
        return admins == listOf(accountId)
    }

    // --- sessions ---------------------------------------------------------------------

    /** A new login session; only its hash is stored. */
    fun newSession(accountId: String): String {
        val token = Ids.random(32)
        db.use { c ->
            c.prepareStatement("INSERT INTO sessions (token_hash, account_id, created_at, last_used) VALUES (?, ?, ?, ?)").use {
                it.setString(1, sha256(token))
                it.setString(2, accountId)
                it.setLong(3, clock())
                it.setLong(4, clock())
                it.executeUpdate()
            }
        }
        return token
    }

    fun sessionAccount(token: String): Account? = db.use { c ->
        val accountId = c.prepareStatement("SELECT account_id FROM sessions WHERE token_hash = ?").use {
            it.setString(1, sha256(token))
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return@use null
        c.prepareStatement("UPDATE sessions SET last_used = ? WHERE token_hash = ?").use { it.setLong(1, clock()); it.setString(2, sha256(token)); it.executeUpdate() }
        // A disabled account's sessions are gone already; this is for one disabled for a while that kept one.
        c.findBy("id", accountId)?.takeIf { !it.disabledAt(clock()) }
    }

    fun endSession(token: String) = db.use { c ->
        c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { it.setString(1, sha256(token)); it.executeUpdate() }
        Unit
    }

    /** Logs the account out on every device (its login sessions here). */
    fun endSessions(accountId: String) = db.use { c ->
        c.prepareStatement("DELETE FROM sessions WHERE account_id = ?").use { it.setString(1, accountId); it.executeUpdate() }
        Unit
    }

    // --- invites ----------------------------------------------------------------------

    /** A new invite code (only its hash is stored), valid for [validForMs]. */
    fun newInvite(createdBy: String, validForMs: Long): Pair<String, Long> {
        val code = Ids.random(24)
        val expires = clock() + validForMs
        db.use { c ->
            c.prepareStatement("INSERT INTO invites (code_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?)").use {
                it.setString(1, sha256(code))
                it.setString(2, createdBy)
                it.setLong(3, clock())
                it.setLong(4, expires)
                it.executeUpdate()
            }
        }
        return code to expires
    }

    private fun Connection.useInvite(code: String, accountId: String) {
        val used = prepareStatement("UPDATE invites SET used_by = ? WHERE code_hash = ? AND used_by IS NULL AND expires_at > ?").use {
            it.setString(1, accountId)
            it.setString(2, sha256(code.trim()))
            it.setLong(3, clock())
            it.executeUpdate()
        }
        if (used != 1) throw AccountException("This invite link is invalid, expired or already used")
    }

    /** Invite links not used yet and not expired. Their [Invite.id] is the code's hash, which can't sign up. */
    fun openInvites(): List<Invite> = db.use { c ->
        c.prepareStatement("SELECT code_hash, created_by, created_at, expires_at FROM invites WHERE used_by IS NULL AND expires_at > ? ORDER BY created_at").use {
            it.setLong(1, clock())
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(Invite(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getLong(4))) } }
        }
    }

    data class Invite(val id: String, val createdBy: String, val createdAt: Long, val expiresAt: Long)

    /** Revokes an invite link that wasn't used yet. */
    fun deleteInvite(id: String): Boolean = db.use { c ->
        c.prepareStatement("DELETE FROM invites WHERE code_hash = ? AND used_by IS NULL").use { it.setString(1, id); it.executeUpdate() == 1 }
    }

    // --- account data -----------------------------------------------------------------

    fun data(accountId: String, name: String): String? = db.use { c ->
        c.prepareStatement("SELECT value FROM account_data WHERE account_id = ? AND name = ?").use {
            it.setString(1, accountId)
            it.setString(2, name)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    fun setData(accountId: String, name: String, value: String) = db.use { c ->
        c.prepareStatement(
            "INSERT INTO account_data (account_id, name, value) VALUES (?, ?, ?) ON CONFLICT (account_id, name) DO UPDATE SET value = excluded.value",
        ).use {
            it.setString(1, accountId)
            it.setString(2, name)
            it.setString(3, value)
            it.executeUpdate()
        }
        Unit
    }

    fun deleteData(accountId: String, name: String) = db.use { c ->
        c.prepareStatement("DELETE FROM account_data WHERE account_id = ? AND name = ?").use {
            it.setString(1, accountId)
            it.setString(2, name)
            it.executeUpdate()
        }
        Unit
    }

    // --- settings ---------------------------------------------------------------------

    /** The key account tokens are signed with; made on first start. */
    fun signingKey(): KeyPair {
        val stored = setting("signing_key")
        if (stored != null) {
            val (private, public) = stored.split(':')
            return KeyPair(AccountTokens.decodePublicKey(public), AccountTokens.decodePrivateKey(private))
        }
        val pair = AccountTokens.newKeyPair()
        setSetting("signing_key", AccountTokens.encodeKey(pair.private) + ":" + AccountTokens.encodeKey(pair.public))
        return pair
    }

    fun setting(name: String): String? = db.use { c ->
        c.prepareStatement("SELECT value FROM settings WHERE name = ?").use {
            it.setString(1, name)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    fun setSetting(name: String, value: String) = db.use { c ->
        c.prepareStatement("INSERT INTO settings (name, value) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET value = excluded.value").use {
            it.setString(1, name)
            it.setString(2, value)
            it.executeUpdate()
        }
        Unit
    }

    // --- helpers ----------------------------------------------------------------------

    private fun Connection.findBy(column: String, value: String): Account? =
        prepareStatement("SELECT * FROM accounts WHERE $column = ?").use {
            it.setString(1, value)
            it.executeQuery().use { rows -> if (rows.next()) rows.toAccount() else null }
        }

    private fun ResultSet.toAccount() = Account(
        id = getString("id"),
        username = getString("username"),
        displayName = getString("display_name"),
        passwordHash = getString("password_hash"),
        isAdmin = getBoolean("is_admin"),
        disabledUntil = getLong("disabled_until"),
    )

    private fun checkPassword(password: String) {
        if (password.length < 8) throw AccountException("Passwords need at least 8 characters")
        if (password.length > 256) throw AccountException("That password is too long")
    }

    companion object {
        /** [Account.disabledUntil] of an account disabled until an admin enables it. */
        const val FOREVER = -1L
        private val USERNAME = Regex("[a-z0-9._-]{2,32}")
        private val DUMMY_HASH by lazy { Passwords.hash("not a real password") }

        fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
}

/** PBKDF2-HMAC-SHA256 password hashes, stored as `pbkdf2-sha256$iterations$salt$hash`. JDK only. */
object Passwords {
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256$$ITERATIONS$${b64(salt)}$${b64(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val expected = Base64.getDecoder().decode(parts[3])
        return MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(parts[2]), parts[1].toInt()))
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}

internal object Ids {
    private val random = SecureRandom()

    /** [bytes] random bytes, URL-safe base64. */
    fun random(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
}
