package com.openwrtmanager.mobile.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.openwrtmanager.mobile.data.SecureStore
import com.openwrtmanager.mobile.model.RouterProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

class SshManager(private val secureStore: SecureStore) {
    @Volatile private var session: Session? = null
    @Volatile var lastFingerprint: String? = null
        private set

    /*
     * Dropbear/OpenWrt can reject or destabilize overlapping channels on one SSH
     * connection, especially while opkg is running. All SSH/SFTP work therefore
     * shares one transport mutex. Realtime monitors wait instead of competing
     * with package/configuration operations.
     */
    private val transportMutex = Mutex()

    suspend fun connect(profile: RouterProfile): Result<String> =
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    disconnectInternal()
                    val jsch = JSch()
                    val s = jsch.getSession(profile.username, profile.host, profile.port)
                    s.setPassword(profile.password)
                    s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    s.setConfig("StrictHostKeyChecking", "no")
                    s.timeout = 12_000
                    s.setServerAliveInterval(15_000)
                    s.setServerAliveCountMax(3)
                    s.connect(12_000)

                    val hostKey = s.hostKey?.key ?: ""
                    val fingerprint = sha256HostKey(hostKey)
                    val saved = secureStore.getHostFingerprint(profile.host, profile.port)
                    if (saved != null && saved != fingerprint) {
                        s.disconnect()
                        error("SSH 主机密钥已变化。已保存：$saved\n当前：$fingerprint")
                    }
                    if (saved == null) secureStore.saveHostFingerprint(profile.host, profile.port, fingerprint)
                    lastFingerprint = fingerprint
                    session = s
                    fingerprint
                }
            }
        }

    private fun sha256HostKey(base64Key: String): String {
        if (base64Key.isBlank()) return "unknown"
        val bytes = Base64.getDecoder().decode(base64Key)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000): String =
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                val s = session?.takeIf { it.isConnected } ?: error("SSH 未连接")
                val channel = s.openChannel("exec") as ChannelExec
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()

                try {
                    channel.setCommand(command)
                    channel.setInputStream(null)
                    channel.setOutputStream(stdout)
                    channel.setErrStream(stderr)

                    try {
                        channel.connect(8_000)
                    } catch (t: Throwable) {
                        error("SSH 通道建立失败：${t.message ?: t.javaClass.simpleName}")
                    }

                    val start = System.currentTimeMillis()
                    while (!channel.isClosed) {
                        if (!s.isConnected) error("SSH 连接已中断")
                        if (System.currentTimeMillis() - start > timeoutMs) {
                            error("命令执行超时")
                        }
                        Thread.sleep(30)
                    }

                    val out = stdout.toString(Charsets.UTF_8.name())
                    val err = stderr.toString(Charsets.UTF_8.name())
                    val exit = channel.exitStatus
                    if (exit != 0 && out.isBlank()) {
                        error(err.ifBlank { "命令失败：$exit" })
                    }
                    out.ifBlank { err }
                } finally {
                    runCatching { channel.disconnect() }
                }
            }
        }

    suspend fun upload(content: ByteArray, remotePath: String) =
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                val s = session?.takeIf { it.isConnected } ?: error("SSH 未连接")
                val channel = s.openChannel("sftp") as ChannelSftp
                try {
                    channel.connect(8_000)
                    ByteArrayInputStream(content).use { channel.put(it, remotePath) }
                } finally {
                    runCatching { channel.disconnect() }
                }
            }
        }

    suspend fun download(remotePath: String): ByteArray =
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                val s = session?.takeIf { it.isConnected } ?: error("SSH 未连接")
                val channel = s.openChannel("sftp") as ChannelSftp
                try {
                    channel.connect(8_000)
                    channel.get(remotePath).use { it.readBytes() }
                } finally {
                    runCatching { channel.disconnect() }
                }
            }
        }

    suspend fun openLocalForward(remoteHost: String, remotePort: Int): Int =
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                val s = session?.takeIf { it.isConnected } ?: error("SSH 未连接")
                require(remotePort in 1..65535) { "远端端口无效" }
                s.setPortForwardingL(0, remoteHost, remotePort)
            }
        }

    suspend fun closeLocalForward(localPort: Int) =
        transportMutex.withLock {
            withContext(Dispatchers.IO) {
                val s = session?.takeIf { it.isConnected } ?: return@withContext
                runCatching { s.delPortForwardingL(localPort) }
            }
        }

    fun isConnected(): Boolean = session?.isConnected == true

    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        runCatching { session?.disconnect() }
        session = null
    }
}
