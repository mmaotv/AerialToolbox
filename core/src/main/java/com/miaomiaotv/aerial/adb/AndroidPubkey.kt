package com.miaomiaotv.aerial.adb

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

/**
 * AOSP 的 RSA 公钥编码格式。
 *
 * 与手机端 kadb 模块的 AndroidPubkey 保持一致（逻辑原样移植）：
 * 设备授权弹窗展示的公钥指纹，就来自按此格式上报的公钥串。
 */
internal object AndroidPubkey {

    /** RSA 模数长度（字节） */
    private const val MODULUS_SIZE = 2048 / 8

    /** 编码后的结构总长：len + n0inv + n + rr + exponent */
    private const val ENCODED_SIZE = 3 * 4 + 2 * MODULUS_SIZE

    private const val MODULUS_SIZE_WORDS = MODULUS_SIZE / 4

    /**
     * 编码为 AOSP 二进制格式，并追加「 base64 + 空格 + 名称 + \0」组成公钥串，
     * 对应 adb 客户端的 `adb pubkey` 输出格式。
     */
    fun encodeWithName(publicKey: RSAPublicKey, name: String): ByteArray {
        val base64 = Base64.encodeToString(encode(publicKey), Base64.NO_WRAP)
        return (base64 + " " + name + "\u0000").toByteArray(Charsets.UTF_8)
    }

    fun encode(publicKey: RSAPublicKey): ByteArray {
        val keyStruct = ByteBuffer.allocate(ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        // 模数长度（字数）
        keyStruct.putInt(MODULUS_SIZE_WORDS)

        // n0inv = 2^32 - (N mod 2^32)^-1 mod 2^32
        val r32 = BigInteger.ZERO.setBit(32)
        var n0inv = publicKey.modulus.mod(r32)
        n0inv = n0inv.modInverse(r32)
        n0inv = r32.subtract(n0inv)
        keyStruct.putInt(n0inv.toInt())

        // 模数（小端字序）
        keyStruct.put(igbEndianToLittleEndianPadded(MODULUS_SIZE, publicKey.modulus))

        // rr = (2^(模数位数))^2 mod N
        var rr = BigInteger.ZERO.setBit(MODULUS_SIZE * 8)
        rr = rr.modPow(BigInteger.valueOf(2), publicKey.modulus)
        keyStruct.put(igbEndianToLittleEndianPadded(MODULUS_SIZE, rr))

        // 公钥指数
        keyStruct.putInt(publicKey.publicExponent.toInt())

        return keyStruct.array()
    }

    /** 大整数转「小端字序」的字节数组，并补齐到指定长度 */
    private fun igbEndianToLittleEndianPadded(len: Int, value: BigInteger): ByteArray {
        val out = ByteArray(len)
        val bytes = swapEndianness(value.toByteArray())
        val numBytes = minOf(bytes.size, len)
        System.arraycopy(bytes, 0, out, 0, numBytes)
        return out
    }

    private fun swapEndianness(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = bytes[bytes.size - i - 1]
        return out
    }
}
