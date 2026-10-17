package com.myne23.abyssresolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AbyssCryptoTest {
    @Test fun md5HexUsesLowercaseHex() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", AbyssCrypto.md5Hex(""))
    }

    @Test fun mp4PrefixDecryptsAndLeavesBytesAfterPrefixUntouched() {
        val filename = "sample.mp4"
        val keyHex = AbyssCrypto.md5Hex(filename)
        val key = keyHex.toByteArray(Charsets.US_ASCII)
        val iv = key.copyOfRange(0, 16)
        val clear = ByteArray(65536) { (it % 251).toByte() }
        clear[0] = 0; clear[1] = 0; clear[2] = 0; clear[3] = 24
        clear[4] = 'f'.code.toByte(); clear[5] = 't'.code.toByte()
        clear[6] = 'y'.code.toByte(); clear[7] = 'p'.code.toByte()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(clear) + byteArrayOf(9, 8, 7, 6)
        val untouchedTail = encrypted.copyOfRange(65536, encrypted.size)

        AbyssCrypto.decryptMp4Prefix(encrypted, 65536, filename)

        assertTrue(AbyssCrypto.hasIsoBmffFtyp(encrypted))
        assertTrue(clear.contentEquals(encrypted.copyOfRange(0, 65536)))
        assertTrue(untouchedTail.contentEquals(encrypted.copyOfRange(65536, encrypted.size)))
    }
}
