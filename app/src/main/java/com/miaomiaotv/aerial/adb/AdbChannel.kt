package com.miaomiaotv.aerial.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 设备没有为本工具的钥匙放行：无法继续处理。 */
class UnauthorizedException(message: String) : IOException(message)

internal data class Message(val cmd: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

/**
 * 极简 ADB 客户端（纯 Java Socket，零第三方依赖）。
 *
 * 只做本工具真正需要的事：连接本机调试端口、完成密钥认证、执行 shell 命令。
 * 设备上要安装的桌面安装包本来就随本工具一起装到设备上了，
 * 因此不需要文件推送通道。
 *
 * 刻意不声明 shell_v2 等扩展特性：命令回传即为原始字节流，
 * 对老版本安卓盒子的兼容性最好。
 */
class AdbChannel {

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var nextStreamId = 0
    private var identity: AdbIdentity? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    fun attachIdentity(identity: AdbIdentity) {
        this.identity = identity
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 4000) {
        disconnect()
        val created = Socket()
        created.connect(InetSocketAddress(host, port), timeoutMs)
        created.soTimeout = IO_TIMEOUT_MS
        socket = created
        output = created.getOutputStream()
        input = created.getInputStream()
        handshake()
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        output = null
        input = null
    }

    /** 设备要求出示公钥（触发授权弹窗）时回调，用于界面提示用户 */
    var onNeedAuthorization: (() -> Unit)? = null

    /**
     * 握手（含密钥认证），流程与手机端 kadb 的 AdbConnection 一致：
     *
     *  1. 设备下发随机串（AUTH 类型1）→ 本工具回签名（AUTH 类型2）
     *  2. 设备验签失败 → 要求出示公钥（AUTH 类型3 或再次下发随机串）
     *  3. 本工具回 AOSP 格式公钥 → 设备弹出授权窗口
     *  4. 用户在设备上点「允许」→ 设备重新下发随机串 → 签名通过 → 握手完成
     *
     * 之前只做了第 1、2 步，第 3 步直接抛异常，导致授权窗口永远弹不出来。
     */
    private fun handshake() {
        // 等待用户在设备上确认授权可能需要较长时间，临时放宽读取超时
        socket?.soTimeout = AUTH_WAIT_MS
        try {
            write(CMD_CNXN, CONNECT_VERSION, CONNECT_MAXDATA, CONNECT_PAYLOAD)

            var sentSignature = false
            val deadline = System.currentTimeMillis() + AUTH_WAIT_MS

            while (System.currentTimeMillis() < deadline) {
                val message = try {
                    read()
                } catch (e: IOException) {
                    throw UnauthorizedException("等待设备授权超时，请在设备上确认授权后重试")
                }
                when (message.cmd) {
                    CMD_CNXN -> return
                    CMD_AUTH -> when (message.arg0) {
                        AUTH_TOKEN -> {
                            val key = identity ?: throw UnauthorizedException("缺少签名密钥")
                            if (!sentSignature) {
                                write(CMD_AUTH, AUTH_SIGNATURE, 0, key.signToken(message.payload))
                                sentSignature = true
                            } else {
                                // 验签没通过：出示公钥，让设备弹出授权窗口
                                requestAuthorization(key)
                            }
                        }
                        AUTH_RSA_PUBLIC -> {
                            // 设备主动索要公钥
                            requestAuthorization(identity ?: throw UnauthorizedException("缺少签名密钥"))
                        }
                        else -> throw UnauthorizedException("设备要求不受支持的认证方式")
                    }
                    else -> throw IOException("握手失败：收到意外响应")
                }
            }
            throw UnauthorizedException("等待设备授权超时，请在设备上确认授权后重试")
        } finally {
            socket?.soTimeout = IO_TIMEOUT_MS
        }
    }

    private fun requestAuthorization(key: AdbIdentity) {
        write(CMD_AUTH, AUTH_RSA_PUBLIC, 0, key.publicKeyBlob())
        onNeedAuthorization?.invoke()
    }

    /**
     * 执行一条 shell 命令并返回回显内容。
     */
    fun shell(command: String): String {
        if (!isConnected) throw IOException("尚未连接")

        val localId = ++nextStreamId
        write(CMD_OPEN, localId, 0, "shell:$command".toByteArray() + byteArrayOf(0))

        val reply = waitForOpenReply(localId)
        val remoteId = reply.arg0

        val result = ByteArrayOutputStream()
        try {
            while (true) {
                val message = read()
                val forUs = message.arg0 == localId || message.arg1 == localId
                if (!forUs) continue
                when (message.cmd) {
                    CMD_WRTE -> {
                        result.write(message.payload)
                        write(CMD_OKAY, localId, remoteId)
                    }
                    // 设备已输出完毕并完成关闭，此时不应再回 CLSE，
                    // 否则设备会多补一个关闭包，污染下一条命令的读取。
                    CMD_CLSE -> return String(result.toByteArray())
                    CMD_OKAY -> Unit
                }
            }
        } catch (e: IOException) {
            runCatching { write(CMD_CLSE, localId, remoteId) }
            throw e
        }
    }

    /**
     * 等待打开命令通道的应答。
     *
     * 设备常常会在下一条命令的 OPEN 之后才补发上一条流的关闭包（实测 arg1 指向上一条命令），
     * 直接判定失败会让后续命令莫名其妙全部失效。这里只认属于当前流的报文，
     * 其余一律当作上一条命令的残留跳过。
     */
    private fun waitForOpenReply(localId: Int): Message {
        while (true) {
            val reply = read()
            if (reply.arg1 != localId) continue
            when (reply.cmd) {
                CMD_OKAY -> return reply
                CMD_CLSE -> throw IOException("命令执行通道被设备关闭")
                else -> continue
            }
        }
    }

    private fun requireOutput(): OutputStream = output ?: throw IOException("尚未连接")

    private fun write(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray? = null) {
        val out = requireOutput()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + (payload?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(cmd)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        if (payload == null) {
            buffer.putInt(0)
            buffer.putInt(0)
        } else {
            buffer.putInt(payload.size)
            buffer.putInt(payload.checksum())
        }
        buffer.putInt(cmd.inv())
        payload?.let { buffer.put(it) }
        out.write(buffer.array())
        out.flush()
    }

    private fun read(): Message {
        val header = readExact(HEADER_SIZE) ?: throw IOException("设备已断开连接")
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        buffer.int
        val magic = buffer.int
        if (magic != cmd.inv()) throw IOException("收到的报文格式不正确")
        val payload = if (length > 0) {
            readExact(length) ?: throw IOException("设备已断开连接")
        } else {
            EMPTY
        }
        return Message(cmd, arg0, arg1, payload)
    }

    private fun readExact(length: Int): ByteArray? {
        val stream = input ?: throw IOException("尚未连接")
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = stream.read(buffer, filled, length - filled)
            if (read < 0) return null
            filled += read
        }
        return buffer
    }

    private fun ByteArray.checksum(): Int {
        var sum = 0
        for (byte in this) sum += byte.toInt() and 0xFF
        return sum
    }

    companion object {
        private const val HEADER_SIZE = 24
        private val EMPTY = ByteArray(0)

        private const val CMD_AUTH = 0x48545541
        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLSE = 0x45534c43
        private const val CMD_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSA_PUBLIC = 3

        /** 不使用「跳过校验和」的新版本，最大程度兼容老设备 */
        private const val CONNECT_VERSION = 0x01000000
        private const val CONNECT_MAXDATA = 1024 * 1024
        private val CONNECT_PAYLOAD = "host::".toByteArray()

        /** 等待用户在设备上确认授权的最长时间 */
        private const val AUTH_WAIT_MS = 90_000

        /**
         * 单条命令的读取超时。
         * 本工具会执行 `pm install`（20MB 左右的安装包）和 `cmd package compile`
         * （整包 AOT 编译，慢的电视要一分钟以上），比其他工具的命令更耗时，
         * 因此放宽到 180 秒。
         */
        private const val IO_TIMEOUT_MS = 180_000
    }
}
