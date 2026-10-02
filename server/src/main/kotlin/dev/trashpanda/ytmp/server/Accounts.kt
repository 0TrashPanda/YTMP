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
    )

    class AccountException(message: String) : Exception(message)

    fun count(): Int = db.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM accounts").use { it.next(); it.getInt(1) } } }

    fun find(username: String): Account? = db.use { c -> c.findBy("username", username.trim().lowercase()) }

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
        c.findBy("id", accountId)
    }

    fun endSession(token: String) = db.use { c ->
        c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { it.setString(1, sha256(token)); it.executeUpdate() }
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
    )

    private fun checkPassword(password: String) {
        if (password.length < 8) throw AccountException("Passwords need at least 8 characters")
        if (password.length > 256) throw AccountException("That password is too long")
    }

    companion object {
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
