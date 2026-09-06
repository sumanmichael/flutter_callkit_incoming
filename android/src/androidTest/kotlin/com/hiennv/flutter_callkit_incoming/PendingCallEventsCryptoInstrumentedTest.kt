package com.hiennv.flutter_callkit_incoming

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PendingCallEventsCryptoInstrumentedTest {
    @Test
    fun androidKeyStoreGeneratesFreshIvAndProductionCryptoRoundTrips() {
        val alias = "vspphone_history_test_${UUID.randomUUID()}"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            val key = generator.generateKey()
            val firstPlain = "first payload".toByteArray(Charsets.US_ASCII)
            val secondPlain = "second payload".toByteArray(Charsets.US_ASCII)

            val first = PendingCallEventsCrypto.encrypt(firstPlain, key)
            val second = PendingCallEventsCrypto.encrypt(secondPlain, key)

            assertEquals(1, first[0].toInt())
            assertEquals(12, first.copyOfRange(1, 13).size)
            assertEquals(12, second.copyOfRange(1, 13).size)
            assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
            assertArrayEquals(firstPlain, PendingCallEventsCrypto.decrypt(first, key))
            assertArrayEquals(secondPlain, PendingCallEventsCrypto.decrypt(second, key))
        } finally {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun decryptsExistingVersionOneEnvelope() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val nonce = ByteArray(12) { 4 }
        val plain = "legacy payload".toByteArray(Charsets.US_ASCII)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD("vspphone-history-1".toByteArray(Charsets.US_ASCII))
        val envelope = byteArrayOf(1) + nonce + cipher.doFinal(plain)

        assertArrayEquals(plain, PendingCallEventsCrypto.decrypt(envelope, key))
    }
}
