package dev.trashpanda.ytmp.share

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import dev.trashpanda.ytmp.Log

/**
 * What holding another phone against this one shares (docs/features/sharing.md): the link the
 * web app set while a Share sheet is open (a room's, or a song's), only while the app is on
 * screen. Otherwise nothing, and the other phone finds no tag here.
 */
object TapShare {
    @Volatile var link: String? = null
    /** The tag names this app, so a phone with YTMP opens the link in it (the Share sheet's choice). */
    @Volatile var openInApp = true
    @Volatile var onScreen = false

    fun current(): String? = link?.takeIf { onScreen }
}

/** Answers as an NFC tag with [TapShare]'s link (Android's host card emulation). */
class TapShareService : HostApduService() {
    private val tag = Type4Tag(app = { packageName.takeIf { TapShare.openInApp } }, link = TapShare::current)

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val answer = tag.respond(commandApdu)
        if (commandApdu.size > 1 && commandApdu[1] == 0xA4.toByte() && commandApdu[2] == 0x04.toByte() && answer.contentEquals(Type4Tag.OK)) {
            Log.i("YtmpTapShare", "Another phone reads the shared link")
        }
        return answer
    }

    override fun onDeactivated(reason: Int) = tag.reset()
}
