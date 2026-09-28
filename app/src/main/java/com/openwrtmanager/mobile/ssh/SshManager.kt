package com.openwrtmanager.mobile.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.openwrtmanager.mobile.data.SecureStore
import com.openwrtmanager.mobile.model.RouterProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

class SshManager(private val secureStore: SecureStore) {
    @Volatile private var session: Session? = null
    @Volatile var lastFingerprint: String? = null
        private set

    suspend fun connect(profile: RouterProfile): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()
            val jsch = JSch()
            val s = jsch.getSession(profile.username, profile.host, profile.port)
            s.setPassword(profile.password)
            s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            s.setConfig("StrictHostKeyChecking", "no")
            s.timeout = 12_000
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

    private fun sha256HostKey(base64Key: String): String {
        if (base64Key.isBlank()) return "unknown"
        val bytes = Base64.getDecoder().decode(base64Key)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000): String = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.isConnected } ?: error("SSH 未连接")
        val channel = s.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(null)
        val output = channel.inputStream
        val error = channel.errStream
        channel.connect(5_000)
        val start = System.currentTimeMillis()
        while (!channel.isClosed) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                channel.disconnect()
                error("命令执行超时")
            }
            Thread.sleep(30)
        }
        val stdout = output.bufferedReader().readText()
        val stderr = error?.bufferedReader()?.readText().orEmpty()
        val exit = channel.exitStatus
        channel.disconnect()
        if (exit != 0 && stdout.isBlank()) error(stderr.ifBlank { "命令失败：$exit" })
        stdout.ifBlank { stderr }
    }

    suspend fun upload(content: ByteArray, remotePath: String) = withContext(Dispatchers.IO) {
        val s = session?.takeIf { it.isConnected } ?: error("SSH 未连接")
        val channel = s.openChannel("sftp") as ChannelSftp
        channel.connect(5_000)
        ByteArrayInputStream(content).use { channel.put(it, remotePath) }
        channel.disconnect()
    }

    fun isConnected(): Boolean = session?.isConnected == true

    fun disconnect() {
        runCatching { session?.disconnect() }
        session = null
    }
}
