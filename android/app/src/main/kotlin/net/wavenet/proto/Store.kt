package net.wavenet.proto

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

enum class LocalState { PUBLISHED, DOWNLOADED, REMOTE }

class Entry(val manifest: Manifest, val state: LocalState)

/**
 * Content-addressed store + catalog in one directory (phone-scale; same rules as the C++ ContentStore/Catalog):
 *   blobs/<cid>            complete content       partial/<cid>.part   resumable download
 *   manifests/<state>/<cid>-<publisher>.wvm      signed manifests (re-verified when loaded)
 */
class ContentStore(val root: File) {
    private val blobs = File(root, "blobs"); private val partial = File(root, "partial"); private val mdir = File(root, "manifests")
    private val entries = LinkedHashMap<String, Entry>()  // key = cid-publisher

    init {
        blobs.mkdirs(); partial.mkdirs()
        for (st in LocalState.values()) File(mdir, st.name.lowercase()).mkdirs()
        for (st in LocalState.values()) for (f in File(mdir, st.name.lowercase()).listFiles().orEmpty()) {
            try { val m = Manifest.decode(f.readBytes()); put(m, st, persist = false) } catch (e: Exception) { f.delete() }
        }
    }

    private fun key(m: Manifest) = m.contentIdHex + "-" + Hex.encode(m.publisher)
    fun blob(cid: ByteArray) = File(blobs, Hex.encode(cid))
    fun partialFile(cid: ByteArray) = File(partial, Hex.encode(cid) + ".part")
    fun hasComplete(cid: ByteArray) = blob(cid).isFile

    /** Upsert; state never downgrades (PUBLISHED < DOWNLOADED < REMOTE), like the C++ catalog. */
    @Synchronized fun put(m: Manifest, st: LocalState, persist: Boolean = true) {
        val k = key(m)
        val old = entries[k]
        val newState = if (old != null && old.state.ordinal < st.ordinal) old.state else st
        entries[k] = Entry(m, newState)
        if (persist) {
            for (s in LocalState.values()) File(mdir, s.name.lowercase() + "/" + k + ".wvm").delete()
            File(mdir, newState.name.lowercase() + "/" + k + ".wvm").writeBytes(m.encode())
        }
    }

    @Synchronized fun remove(cid: ByteArray, publisher: ByteArray): Boolean {
        val k = Hex.encode(cid) + "-" + Hex.encode(publisher)
        val e = entries.remove(k) ?: return false
        File(mdir, e.state.name.lowercase() + "/" + k + ".wvm").delete()
        return true
    }

    @Synchronized fun get(cid: ByteArray): Entry? = entries.values.filter { it.manifest.contentId.contentEquals(cid) }.minByOrNull { it.state.ordinal }

    @Synchronized fun all(): List<Entry> = entries.values.toList()

    /** AND of terms over title/description/keywords/file name (case-insensitive); publicOnly = what peers may see. */
    @Synchronized fun search(q: Wire.Query, publicOnly: Boolean): List<Entry> {
        val terms = q.text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)
        return entries.values.filter { e ->
            val m = e.manifest
            val hay = (m.title + "\n" + m.description + "\n" + m.keywords + "\n" + m.fileName).lowercase()
            terms.all { it in hay } && (q.category.isEmpty() || m.category == q.category) &&
                (!publicOnly || (!m.isPrivate && e.state != LocalState.REMOTE))
        }.sortedByDescending { it.manifest.createdMs }.drop(q.offset).take(minOf(q.limit, 200))
    }

    /** Hashes and stores content from a stream (e.g. Android Storage Access Framework) and returns a signed manifest. */
    fun import(input: InputStream, fileName: String, publisher: Identity, title: String = "", description: String = "",
               category: String = "", keywords: String = "", mime: String = "", license: String = "",
               isPrivate: Boolean = false, allowRedistribution: Boolean = true, chunkSize: Int = Manifest.DEFAULT_CHUNK): Manifest {
        val tmp = File(partial, "import-" + System.nanoTime())
        val chunks = ArrayList<ByteArray>()
        val whole = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(chunkSize)
                while (true) {
                    var n = 0
                    while (n < chunkSize) { val r = input.read(buf, n, chunkSize - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    chunks.add(MessageDigest.getInstance("SHA-256").apply { update(buf, 0, n) }.digest())
                    whole.update(buf, 0, n); out.write(buf, 0, n); size += n
                    if (size > Manifest.MAX_CONTENT) throw IllegalArgumentException("file too large")
                    if (n < chunkSize) break
                }
            }
            val safeName = fileName.replace('/', '_').replace('\\', '_').replace("\u0000", "").ifEmpty { "file" }.let { if (it == "." || it == "..") "file" else it }
            val m = Manifest(whole.digest(), size, chunkSize, chunks, title.ifEmpty { safeName }, description, category, mime, license,
                             keywords, isPrivate, allowRedistribution, safeName, publisher.publicKey, System.currentTimeMillis()).signedBy(publisher)
            Manifest.validate(m)
            synchronized(this) { if (hasComplete(m.contentId)) tmp.delete() else if (!tmp.renameTo(blob(m.contentId))) throw IllegalStateException("store failed") }
            put(m, LocalState.PUBLISHED)
            return m
        } finally { tmp.delete() }
    }

    fun readChunk(m: Manifest, idx: Int): ByteArray {
        val len = m.chunkLen(idx)
        RandomAccessFile(blob(m.contentId), "r").use { f -> val b = ByteArray(len); f.seek(idx.toLong() * m.chunkSize); f.readFully(b); return b }
    }

    /** Re-verifies whatever is already in the .part file; returns which chunks are good. */
    @Synchronized fun openPartial(m: Manifest): BooleanArray {
        if (hasComplete(m.contentId)) return BooleanArray(m.chunkCount) { true }
        val have = BooleanArray(m.chunkCount)
        RandomAccessFile(partialFile(m.contentId), "rw").use { f ->
            if (f.length() != m.size) f.setLength(m.size)
            val buf = ByteArray(m.chunkSize)
            for (i in 0 until m.chunkCount) {
                val len = m.chunkLen(i)
                f.seek(i.toLong() * m.chunkSize); f.readFully(buf, 0, len)
                have[i] = MessageDigest.getInstance("SHA-256").apply { update(buf, 0, len) }.digest().contentEquals(m.chunks[i])
            }
        }
        return have
    }

    /** Verifies the chunk hash BEFORE writing. Returns false (nothing written) on mismatch. */
    fun writeChunk(m: Manifest, idx: Int, data: ByteArray): Boolean {
        if (idx < 0 || idx >= m.chunkCount || data.size != m.chunkLen(idx)) return false
        if (!Crypto.sha256(data).contentEquals(m.chunks[idx])) return false
        synchronized(this) {
            RandomAccessFile(partialFile(m.contentId), "rw").use { f -> f.seek(idx.toLong() * m.chunkSize); f.write(data); f.fd.sync() }
        }
        return true
    }

    /** Verifies the whole-file SHA-256 and moves the .part into blobs. */
    @Synchronized fun finalize(m: Manifest): Boolean {
        if (hasComplete(m.contentId)) return true
        val p = partialFile(m.contentId)
        val md = MessageDigest.getInstance("SHA-256")
        p.inputStream().buffered().use { val b = ByteArray(1 shl 16); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
        if (!md.digest().contentEquals(m.contentId)) return false
        return p.renameTo(blob(m.contentId))
    }
}
