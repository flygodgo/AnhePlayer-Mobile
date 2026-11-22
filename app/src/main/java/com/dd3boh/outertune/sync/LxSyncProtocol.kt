package com.dd3boh.outertune.sync

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * lx-server v4 协议工具层。
 * 逐行对齐 lx-music-desktop 的 src/main/modules/sync + AnhePlayer 桌面版的
 * src/infra/lxSync/common/protocol.ts（已通过类型检查与构建验证）。
 *
 * - MD5：消息摘要 hex
 * - AES-128-ECB / PKCS7，key 为 base64 字符串（解码后作为原始字节）
 * - RSA-OAEP(2048, SHA-1)：客户端生成密钥对，SPKI DER base64 上传，私钥解密服务端回包
 * - 消息体：UTF-8 json 超过 1024 字符时 gzip + base64，加前缀 "cg_"
 */
internal object LxSyncProtocol {

    private val hexChars = "0123456789abcdef".toCharArray()

    fun md5(text: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0f]
        }
        return String(out)
    }

    /** key 为 base64 字符串：先 base64 解码成原始字节，再作为 AES key */
    private fun keyBytes(keyBase64: String): ByteArray =
        Base64.decode(keyBase64, Base64.NO_WRAP)

    fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes(key), "AES"))
        return Base64.encodeToString(
            cipher.doFinal(text.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }

    fun aesDecrypt(cipherText: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(key), "AES"))
        return String(
            cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)),
            Charsets.UTF_8,
        )
    }

    /** 授权码派生 key：md5(authCode) 前 16 位当 UTF-8 字符串，再 base64 */
    fun deriveAuthKey(authCode: String): String {
        val seed = md5(authCode).substring(0, 16)
        return Base64.encodeToString(seed.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** 生成 RSA-OAEP 2048 / SHA-1 密钥对（对齐 Web Crypto generateKey） */
    fun generateRsaKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    /** 导出 SPKI DER 的 base64（对应 Web Crypto exportKey('spki')） */
    fun exportSpkiBase64(keyPair: KeyPair): String =
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

    /** RSA-OAEP(SHA-1, MGF1-SHA-1) 解密服务端返回的 base64 密文 */
    fun rsaOaepDecrypt(cipherText: String, keyPair: KeyPair): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, keyPair.private)
        return String(
            cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)),
            Charsets.UTF_8,
        )
    }

    /** 与 lx encodeData 对齐：>1024 字符 gzip + base64 + cg_ 前缀 */
    fun encodeData(data: String): String {
        if (data.length <= 1024) return data
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data.toByteArray(Charsets.UTF_8)) }
        return "cg_" + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    /** 与 lx decodeData 对齐：cg_ 前缀则 base64 解码 + gzip 解压 */
    fun decodeData(data: String): String {
        if (!data.startsWith("cg_")) return data
        val bytes = Base64.decode(data.substring(3), Base64.NO_WRAP)
        val bis = java.io.ByteArrayInputStream(bytes)
        java.util.zip.GZIPInputStream(bis).use { gz ->
            return String(gz.readBytes(), Charsets.UTF_8)
        }
    }
}
