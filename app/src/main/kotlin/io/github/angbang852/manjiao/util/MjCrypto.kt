package io.github.angbang852.manjiao.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ★★★ AES-GCM 加密容器（2026-09-30 用户规格 ①）
 *
 * ## 为什么必须是 GCM 而不是 CBC
 *
 * 公共目录 `/sdcard/Download/ManJiao/.sys/` 是 **world-writable** 的（实测属主
 * `u0_a224 media_rw`、mode 660，但任意 App 都能通过 MediaProvider 改写），
 * 真实威胁有三条：
 *  1. **改配置静默关掉过滤** —— 把配置镜像改成「过滤关」；
 *  2. **删证据** —— 把 evidence 日志清掉；
 *  3. **替换池子存档** —— 用一份旧 pool.json 覆盖（去重表回退 ⇒ 重复上屏）。
 *
 * CBC 只加密不校验：攻击者翻转密文位就能让明文变成任意内容（且我们**察觉不到**），
 * 三条威胁一条都防不住。GCM 是 AEAD —— 每个字节都参与认证，改一个字节认证标签
 * 就对不上，解密直接失败。**要的就是「改不动」而不只是「看不到」**。
 *
 * ## 容器格式（自带版本号，为日后升级留路）
 *
 * ```
 * Record := HDR(16) IV(12) CT(ctLen)          // CT 末尾含 16 字节 GCM tag
 * HDR    := MAGIC(4)="MJSE" VERSION(1) FLAGS(1) IVLEN(1)=12 RSV(1)
 *           ctLen(4, big-endian) RSV(4)
 * File   := Record+                          // 一条记录 = 一次 seal
 * ```
 *
 * - **VERSION**：格式升级时递增，旧版本数据仍按旧规则读（[VERSION] 目前只有 1）。
 * - **多记录**：GCM 每条记录都要一个新 IV。日志通道是**追加写**的，若每次追加都
 *   重加密整个文件就是 O(n²)。所以日志按「一批一个记录」追加；而 pool.json 这种
 *   数据文件**只写一条记录**（[seal]），拿到的是**整文件完整性**。
 * - **AAD 绑定文件名**：把逻辑文件名作为附加认证数据（[aadOf]）。这样攻击者
 *   无法把 `evidence.sec` 里一段合法密文搬到 `pool.json` 上冒充存档 —— 换名即失效。
 *
 * ## 失败语义（配合规格 ③ fail-closed）
 *
 * - [openStrict]：**任一记录失败即整体返回 null**。数据文件用它 —— 宁可当「没有存档」
 *   也绝不用半份可疑数据。
 * - [openLenient]：坏记录处截断，返回已成功解出的前缀。**只给追加型日志用** ——
 *   日志最后一条可能是被 kill 打断的半截记录，不能因此把整个日志判废。
 */
object MjCrypto {

    /** 魔数：`MJSE`（ManJiao SEaled）。文件开头不是它 ⇒ 明文旧文件（迁移期要认） */
    private val MAGIC = byteArrayOf(0x4D, 0x4A, 0x53, 0x45)

    /** 容器版本（格式升级时递增；读取端按它分支，旧文件永远读得回来） */
    const val VERSION: Int = 1

    /** GCM 推荐的 96-bit IV（NIST SP 800-38D） */
    const val IV_LEN: Int = 12

    /** 认证标签长度（bit）—— 128 位，抗伪造 */
    const val TAG_BITS: Int = 128

    /** 记录头长度 */
    const val HDR_LEN: Int = 16

    private const val TAG_LEN = TAG_BITS / 8

    /** 单条记录的明文上限（日志批量 flush 用；也用于流式加密的分块） */
    const val CHUNK: Int = 256 * 1024

    private val rng = SecureRandom()

    private const val TRANSFORM = "AES/GCM/NoPadding"

    /**
     * AAD：把「逻辑文件名」绑进认证范围。
     *
     * ★ 防的是**跨文件搬运**：攻击者可以留一份自己掌控的旧密文，想用它替换
     * `pool.json` ⇒ 换名后 AAD 不匹配 ⇒ 解密失败。`.bak` 与正式文件**同名**，
     * 所以刻意省略扩展名之外的后缀（见 [SecureStore]）。
     */
    fun aadOf(logicalName: String): ByteArray =
        ("manjiao|v$VERSION|$logicalName").toByteArray(Charsets.UTF_8)

    /** 这段字节是不是本容器的密文（`false` = 明文旧文件，迁移期按明文解析） */
    fun isSealed(b: ByteArray, off: Int = 0): Boolean {
        if (b.size - off < HDR_LEN) return false
        for (i in MAGIC.indices) if (b[off + i] != MAGIC[i]) return false
        return true
    }

    /**
     * 最近一次流式 IO 失败的**原因**（诊断用；只存异常类名 + message，不含明文/密钥）。
     *
     * ★ 为什么必须有：`sealStream` 把异常吞成 `false`，调用方只看到「失败」，
     *   真机上无法区分「源文件读不动（分区存储拒绝 open）」「目标建不了」「磁盘满」。
     *   2026-09-30 实测 8 个历史明文全部迁移失败却查不出原因，就是因为缺它 ——
     *   最后只能靠「21ms 就失败 + stat 正常」反推出是 open 被拒。
     */
    @Volatile
    var lastIoError: String? = null

    // ---------------------------------------------------------------- 封

    /**
     * 把明文封成**单记录**密文（整文件完整性）。
     *
     * 数据文件（pool.json / audit_mirror.json / adapt_cache.txt）走这条 ——
     * 任何一位被改都解不开，且没有「改了后面一半还能读前半份」的缝。
     */
    fun seal(plain: ByteArray, key: ByteArray, aad: ByteArray): ByteArray =
        sealChunked(plain, key, aad, max(plain.size, 1))

    /**
     * 分块封（多记录）。
     *
     * @param chunk 单记录承载的明文字节数。日志批量 flush 用它控制单条记录大小
     *   （记录越大越省空间，但单次失败丢的也越多）。
     */
    fun sealChunked(plain: ByteArray, key: ByteArray, aad: ByteArray, chunk: Int): ByteArray {
        val step = if (chunk < 1) 1 else chunk
        val out = java.io.ByteArrayOutputStream(plain.size + 64 + plain.size / step * 44)
        var off = 0
        if (plain.isEmpty()) {
            out.write(record(ByteArray(0), key, aad))
            return out.toByteArray()
        }
        while (off < plain.size) {
            val n = minOf(step, plain.size - off)
            out.write(record(plain, off, n, key, aad))
            off += n
        }
        return out.toByteArray()
    }

    private fun record(plain: ByteArray, key: ByteArray, aad: ByteArray): ByteArray =
        record(plain, 0, plain.size, key, aad)

    /** 生成一条记录：HDR + IV + (CT||TAG) */
    private fun record(plain: ByteArray, off: Int, len: Int, key: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN)
        rng.nextBytes(iv)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(aad)
        val ct = c.doFinal(plain, off, len)
        val hdr = ByteArray(HDR_LEN + IV_LEN)
        System.arraycopy(MAGIC, 0, hdr, 0, 4)
        hdr[4] = VERSION.toByte()
        hdr[5] = 0                       // flags 保留（后续可做「记录类型」）
        hdr[6] = IV_LEN.toByte()
        hdr[7] = 0
        putInt(hdr, 8, ct.size)          // ctLen（含 tag）
        // [12..15] 保留 0
        System.arraycopy(iv, 0, hdr, HDR_LEN, IV_LEN)
        val out = ByteArray(hdr.size + ct.size)
        System.arraycopy(hdr, 0, out, 0, hdr.size)
        System.arraycopy(ct, 0, out, hdr.size, ct.size)
        return out
    }

    /**
     * 流式加密（大文件用，避免把整个文件读进内存）。
     *
     * 用途：把**历史明文**的 277MB `evidence.txt` 原地转成密文（见 [SecureStore]）。
     * 逐块读 → 逐块封 → 顺序写，内存占用恒定为 [chunk]。
     *
     * @return true = 全部写完（调用方还需自行验证后才可删明文）
     */
    fun sealStream(src: File, dst: File, key: ByteArray, aad: ByteArray, chunk: Int = CHUNK): Boolean {
        val step = if (chunk < 1) 1 else chunk
        return try {
            FileInputStream(src).use { ins ->
                FileOutputStream(dst).use { outs ->
                    val buf = ByteArray(step)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(record(buf, 0, n, key, aad))
                    }
                    outs.flush()
                }
            }
            true
        } catch (t: Throwable) {
            lastIoError = t.javaClass.name + ": " + (t.message ?: "")
            try { dst.delete() } catch (_: Throwable) {}
            false
        }
    }

    // ---------------------------------------------------------------- 解

    /**
     * **严格**解封：任一记录失败 ⇒ 整体 null（fail-closed）。
     *
     * 数据文件必须用这条 —— 半份可疑数据的后果（去重表不全 ⇒ 重复上屏）比
     * 「当作没有存档」严重得多。
     */
    fun openStrict(blob: ByteArray, key: ByteArray, aad: ByteArray): ByteArray? =
        walk(blob, key, aad, lenient = false)

    /**
     * **宽松**解封：坏记录处截断，返回已解出的前缀。**只给追加型日志用**。
     *
     * 为什么日志不能严格：进程被 kill 时最后一条记录可能只写了一半，
     * 严格模式下整个日志都会被判废 —— 那正好帮攻击者实现了「删证据」。
     */
    fun openLenient(blob: ByteArray, key: ByteArray, aad: ByteArray): ByteArray? =
        walk(blob, key, aad, lenient = true)

    private fun walk(blob: ByteArray, key: ByteArray, aad: ByteArray, lenient: Boolean): ByteArray? {
        var pos = 0
        var any = false
        val out = java.io.ByteArrayOutputStream(blob.size)
        while (pos + HDR_LEN <= blob.size) {
            // 头校验：魔数 / 版本 / IV 长度都必须严丝合缝，否则视为篡改
            if (!isSealed(blob, pos)) break
            if (blob[pos + 4].toInt() != VERSION) break
            if (blob[pos + 6].toInt() != IV_LEN) break
            val ctLen = getInt(blob, pos + 8)
            if (ctLen < TAG_LEN || pos + HDR_LEN + IV_LEN + ctLen > blob.size) break
            val ivOff = pos + HDR_LEN
            val ctOff = ivOff + IV_LEN
            val pt = try {
                val c = Cipher.getInstance(TRANSFORM)
                c.init(
                    Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, blob, ivOff, IV_LEN)
                )
                c.updateAAD(aad)
                c.doFinal(blob, ctOff, ctLen)
            } catch (_: Throwable) {
                null
            }
            if (pt == null) break            // 认证失败 = 被篡改 / 密钥不对
            out.write(pt)
            any = true
            pos = ctOff + ctLen
        }
        if (pos != blob.size && !lenient) return null   // 严格：尾部有残留 ⇒ 整体不可信
        return if (any) out.toByteArray() else null
    }

    // ---------------------------------------------------------------- 流式（大文件迁移用）

    /**
     * 流式解封：逐条记录读出、认证、把明文交给 [sink]。
     *
     * 为什么需要它：历史明文 evidence 文件有 **277MB**（实测），
     * 「整份读进内存 + 解密」会直接 OOM。迁移校验必须能在**恒定内存**下完成，
     * 否则「验证后再删明文」这一步就没法安全落地（见 [SecureStore.encryptLegacy]）。
     *
     * @return true = 整份文件每条记录都通过认证（任何一条失败即 false，不做截断）
     */
    fun openStream(src: File, key: ByteArray, aad: ByteArray, sink: (ByteArray, Int) -> Unit): Boolean {
        return try {
            FileInputStream(src).use { ins ->
                val hdr = ByteArray(HDR_LEN)
                while (true) {
                    var n = 0
                    while (n < HDR_LEN) {
                        val r = ins.read(hdr, n, HDR_LEN - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n == 0) break                       // 干净 EOF
                    if (n < HDR_LEN) return false           // 半截头 ⇒ 不可信
                    if (!isSealed(hdr, 0)) return false
                    if (hdr[4].toInt() != VERSION) return false
                    if (hdr[6].toInt() != IV_LEN) return false
                    val ctLen = getInt(hdr, 8)
                    if (ctLen < TAG_LEN) return false
                    val iv = ByteArray(IV_LEN)
                    if (!readFully(ins, iv, IV_LEN)) return false
                    val ct = ByteArray(ctLen)
                    if (!readFully(ins, ct, ctLen)) return false
                    val c = Cipher.getInstance(TRANSFORM)
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
                    c.updateAAD(aad)
                    val pt = c.doFinal(ct)
                    sink(pt, pt.size)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun readFully(ins: FileInputStream, buf: ByteArray, len: Int): Boolean {
        var n = 0
        while (n < len) {
            val r = ins.read(buf, n, len - n)
            if (r < 0) return false
            n += r
        }
        return true
    }

    /** 明文的 SHA-256（流式，恒定内存）——迁移后用它比对「原文 == 解密结果」 */
    fun digestPlain(src: File): ByteArray? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(src).use { ins ->
            val b = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(b)
                if (n <= 0) break
                md.update(b, 0, n)
            }
        }
        md.digest()
    } catch (_: Throwable) { null }

    /** 密文解出的明文的 SHA-256（流式，恒定内存） */
    fun digestSealed(src: File, key: ByteArray, aad: ByteArray): ByteArray? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var ok = true
        val r = openStream(src, key, aad) { b, n -> md.update(b, 0, n) }
        // openStream 返回前不会失败，但保险起见仍显式判断
        if (!r) ok = false
        if (ok) md.digest() else null
    } catch (_: Throwable) { null }

    // ---------------------------------------------------------------- 小工具

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun max(a: Int, b: Int) = if (a > b) a else b

    /** 生成一把新随机密钥（256 bit）。钥匙只存在于内存/宿主私有目录，永不外泄 */
    fun newKey(): ByteArray {
        val k = ByteArray(32)
        rng.nextBytes(k)
        return k
    }
}
