package com.miaomiaotv.aerial.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * ADB 主机身份：一把 RSA-2048 私钥，持久化在应用私有目录。
 *
 * 为什么必须持久化：设备只认已经授权过的那把钥匙。如果每次打开工具都换一把新钥匙，
 * 第一次能连上、之后就一直是「未授权」。这里保证同一个安装包在同一台设备上钥匙固定不变。
 *
 * 排障/联调用：若私有目录下存在 adbkey.pk8（PKCS#8 私钥，PEM 或 DER 均可），
 * 则优先使用它——便于把一台已经授权过的设备的钥匙导入进来复现问题。
 */
class AdbIdentity(dir: File) {

    val privateKey: PrivateKey

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建密钥目录：${dir.absolutePath}")
        }
        privateKey = loadImported(File(dir, IMPORTED_KEY_NAME))
            ?: loadStored(File(dir, STORED_KEY_NAME))
            ?: generateAndStore(File(dir, STORED_KEY_NAME))
    }

    /**
     * 对设备下发的 20 字节随机串做签名。
     *
     * 注意：这是 ADB 的专有做法，不是标准的 RSA-SHA1 签名——
     * 密码学结构上仍是 PKCS#1 v1.5 的 SHA-1 填充，但摘要位置直接放原始 token，
     * 校验端会把解出来的最后 20 字节和 token 直接比对。
     * 这里严格按该约定实现，否则签名会被设备判为无效。
     */
    fun signToken(token: ByteArray): ByteArray {
        val digest = if (token.size == TOKEN_SIZE) {
            token
        } else {
            MessageDigest.getInstance("SHA-1").digest(token)
        }
        val input = SIGNATURE_PADDING + digest
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(input)
    }

    private fun loadImported(file: File): PrivateKey? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { parseKey(file.readBytes()) }.getOrNull()
    }

    private fun loadStored(file: File): PrivateKey? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { parseKey(file.readBytes()) }.getOrNull()
    }

    private fun generateAndStore(file: File): PrivateKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE_BITS)
        val pair = generator.generateKeyPair()
        val ders = pair.private.encoded
        runCatching {
            val encoded = Base64.encodeToString(ders, Base64.DEFAULT)
            file.writeText("$PEM_HEADER\n$encoded$PEM_FOOTER\n")
        }
        return pair.private
    }

    private fun parseKey(bytes: ByteArray): PrivateKey {
        val text = String(bytes, Charsets.US_ASCII)
        val der = if (text.contains(PEM_HEADER)) {
            val body = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("-----") }
                .joinToString("")
            Base64.decode(body, Base64.DEFAULT)
        } else {
            bytes
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /**
     * AOSP 格式的公钥串（base64 + 空格 + 名称 + \0）。
     * 设备上的授权弹窗依赖它来展示指纹并完成入库。
     */
    fun publicKeyBlob(): ByteArray {
        val rsaPrivate = privateKey as java.security.interfaces.RSAPrivateKey
        val crt = privateKey as? java.security.interfaces.RSAPrivateCrtKey
        val exponent = crt?.publicExponent ?: BigInteger.valueOf(65537)
        val spec = java.security.spec.RSAPublicKeySpec(rsaPrivate.modulus, exponent)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(spec) as java.security.interfaces.RSAPublicKey
        return AndroidPubkey.encodeWithName(publicKey, PUBLIC_KEY_NAME)
    }

    companion object {
        private const val STORED_KEY_NAME = "adb_identity.key"
        /** 上报给设备授权弹窗时使用的钥匙名称 */
        private const val PUBLIC_KEY_NAME = "mmaotv-aerial"
        private const val IMPORTED_KEY_NAME = "adbkey.pk8"
        private const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_FOOTER = "-----END PRIVATE KEY-----"

        private const val KEY_SIZE_BITS = 2048
        private const val MODULUS_BYTES = KEY_SIZE_BITS / 8
        private const val TOKEN_SIZE = 20

        /** PKCS#1 v1.5 中标识「SHA-1 摘要」的 DigestInfo 前缀 */
        private val DIGEST_INFO = byteArrayOf(
            0x30.toByte(), 0x21.toByte(),
            0x30.toByte(), 0x09.toByte(), 0x06.toByte(), 0x05.toByte(),
            0x2b.toByte(), 0x0e.toByte(), 0x03.toByte(), 0x02.toByte(), 0x1a.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x04.toByte(), 0x14.toByte()
        )

        /** 固定填充：0x00 0x01 FF…FF 0x00 + DigestInfo，其后接 20 字节摘要正好凑满密钥长度 */
        private val SIGNATURE_PADDING: ByteArray = buildPadding()

        private fun buildPadding(): ByteArray {
            val ffCount = MODULUS_BYTES - 2 - 1 - DIGEST_INFO.size - TOKEN_SIZE
            require(ffCount > 8) { "RSA 密钥长度不足以承载 ADB 签名填充" }
            val out = ByteArray(MODULUS_BYTES - TOKEN_SIZE)
            out[0] = 0x00.toByte()
            out[1] = 0x01.toByte()
            for (i in 0 until ffCount) out[2 + i] = 0xFF.toByte()
            out[2 + ffCount] = 0x00.toByte()
            System.arraycopy(DIGEST_INFO, 0, out, 2 + ffCount + 1, DIGEST_INFO.size)
            return out
        }
    }
}
