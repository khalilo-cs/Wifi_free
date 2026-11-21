package net.wavenet.proto

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteropTest {
    private fun tmp(): File = Files.createTempDirectory("wave-interop").toFile()

    @Test fun kotlinClientTalksToCppNode() {
        if (Interop.bin == null) { println("SKIP: wavenode binary not built"); return }
        val d = tmp()
        val f = File(d, "lesson.txt").apply { writeText("درس تجريبي ".repeat(5000)) }
        val (rc, cidOut) = Interop.run("--data", File(d, "a").path, "publish", f.path, "--title", "Physics lesson", "--keywords", "physics")
        assertEquals(0, rc, cidOut)
        val cid = cidOut.lines().first { it.length == 64 }
        Interop.Serve(File(d, "a")).use { srv ->
            val me = Identity.generate()
            val link = Tls.connect("127.0.0.1", srv.port)
            assertEquals(32, link.channelBinding.size)
            val s = Session.initiate(link, me, 1, TrustPolicy())
            s.send(MsgType.SEARCH_REQUEST, Wire.searchRequest(Wire.Query("physics")), 1)
            val resp = s.recv(5000)
            assertEquals(MsgType.SEARCH_RESPONSE, resp.type)
            val ms = Wire.parseSearchResponse(resp.payload)
            assertEquals(1, ms.size)
            assertEquals(cid, ms[0].contentIdHex)
            assertEquals("Physics lesson", ms[0].title)
            assertTrue(ms[0].size > 0)
            s.close()
        }
    }
}

class InteropTransferTest {
    private fun tmp(): File = Files.createTempDirectory("wave-xfer").toFile()
    private fun randomFile(dir: File, name: String, size: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }) }
    private fun sha(f: File) = Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()))

    @Test fun kotlinDownloadsFromCppWithResume() {
        if (Interop.bin == null) { println("SKIP"); return }
        val d = tmp()
        val f = randomFile(d, "video.bin", 3 * 1024 * 1024 + 777)
        val cid = Interop.run("--data", File(d, "a").path, "publish", f.path, "--title", "Video").second.lines().first { it.length == 64 }
        Interop.Serve(File(d, "a")).use { srv ->
            val ctx = NodeContext(Identity.generate(), ContentStore(File(d, "phone")))
            val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
            val s1 = Downloader.download(ctx, Hex.decode(cid), listOf(PeerAddr("127.0.0.1", srv.port)), cancel = cancel) { done, _, _ -> if (done >= 5) cancel.set(true) }
            assertTrue(!s1.complete && s1.fetched >= 5)
            val ctx2 = NodeContext(ctx.id, ContentStore(File(d, "phone")))   // "app restarted"
            val s2 = Downloader.download(ctx2, Hex.decode(cid), listOf(PeerAddr("127.0.0.1", srv.port)))
            assertTrue(s2.complete, s2.error)
            assertEquals(s1.fetched, s2.alreadyHad)
            assertEquals(s2.totalChunks - s1.fetched, s2.fetched)
            assertEquals(cid, sha(ctx2.store.blob(Hex.decode(cid))))
            assertTrue(ctx2.store.blob(Hex.decode(cid)).readBytes().contentEquals(f.readBytes()))
        }
    }

    @Test fun cppDownloadsFromKotlinServer() {
        if (Interop.bin == null) { println("SKIP"); return }
        val d = tmp()
        val f = randomFile(d, "app.apk", 2 * 1024 * 1024 + 99)
        val ctx = NodeContext(Identity.generate(), ContentStore(File(d, "phone")))
        val m = f.inputStream().use { ctx.store.import(it, "app.apk", ctx.id, title = "My App", category = "apps") }
        val priv = ByteArray(5000).inputStream().use { ctx.store.import(it, "diary.txt", ctx.id, title = "My App private notes", isPrivate = true) }
        NodeServer(ctx, "127.0.0.1", 0).use { srv ->
            val (rcS, outS) = Interop.run("--data", File(d, "pc").path, "search-net", "app", "--peer", "127.0.0.1:${srv.port}")
            assertEquals(0, rcS, outS)
            assertTrue(m.contentIdHex in outS, outS)
            assertTrue(priv.contentIdHex !in outS, "private item leaked")
            val out = File(d, "got.apk")
            val (rc, log) = Interop.run("--data", File(d, "pc").path, "fetch", m.contentIdHex, "--peer", "127.0.0.1:${srv.port}", "--out", out.path)
            assertEquals(0, rc, log)
            assertTrue("verified ${m.contentIdHex}" in log, log)
            assertTrue(out.readBytes().contentEquals(f.readBytes()))
            val (rcP, logP) = Interop.run("--data", File(d, "pc").path, "fetch", priv.contentIdHex, "--peer", "127.0.0.1:${srv.port}")
            assertTrue(rcP != 0, "private content was served: $logP")
            assertTrue(srv.chunksServed.get() >= m.chunkCount)
        }
    }

    @Test fun phoneToPhoneMultiSource() {
        val d = tmp()
        val f = randomFile(d, "book.pdf", 1024 * 1024 + 5)
        val a = NodeContext(Identity.generate(), ContentStore(File(d, "a")))
        val m = f.inputStream().use { a.store.import(it, "book.pdf", a.id, title = "كتاب الفيزياء", keywords = "علوم physics") }
        NodeServer(a, "127.0.0.1", 0).use { sa ->
            val b = NodeContext(Identity.generate(), ContentStore(File(d, "b")))
            PeerClient(PeerAddr("127.0.0.1", sa.port), b).use { pc ->
                val res = pc.search(Wire.Query("الفيزياء"))
                assertEquals(1, res.size); assertEquals("كتاب الفيزياء", res[0].title)
            }
            assertTrue(Downloader.download(b, m.contentId, listOf(PeerAddr("127.0.0.1", sa.port))).complete)
            NodeServer(b, "127.0.0.1", 0).use { sb ->
                val c = NodeContext(Identity.generate(), ContentStore(File(d, "c")))
                val st = Downloader.download(c, m.contentId, listOf(PeerAddr("127.0.0.1", sa.port), PeerAddr("127.0.0.1", sb.port)), window = 2)
                assertTrue(st.complete, st.error)
                assertEquals(2, st.perPeer.size, "both phones should serve chunks: ${st.perPeer}")
                assertTrue(c.store.blob(m.contentId).readBytes().contentEquals(f.readBytes()))
            }
        }
    }

    @Test fun untrustedPhoneRefusedByCppAllowlistNode() {
        if (Interop.bin == null) { println("SKIP"); return }
        val d = tmp()
        val f = randomFile(d, "x.bin", 50_000)
        Interop.run("--data", File(d, "g").path, "trust", "mode", "allowlist")
        val cid = Interop.run("--data", File(d, "g").path, "publish", f.path).second.lines().first { it.length == 64 }
        Interop.Serve(File(d, "g")).use { srv ->
            val ctx = NodeContext(Identity.generate(), ContentStore(File(d, "p")))
            val st = Downloader.download(ctx, Hex.decode(cid), listOf(PeerAddr("127.0.0.1", srv.port)))
            assertTrue(!st.complete && "not trusted" in st.error, st.error)
        }
    }

    @Test fun discoveryBothDirections() {
        if (Interop.bin == null) { println("SKIP"); return }
        val d = tmp()
        val me = Identity.generate()
        Discovery(me, port = 0).use { disc ->
            // C++ node announces to the Kotlin socket (unicast seed)
            Interop.Serve(File(d, "a"), "--seed", "127.0.0.1:${disc.boundPort}", "--name", "linux-node", "--announce-ms", "200").use { srv ->
                disc.poll(3000)
                val p = disc.peers().firstOrNull { it.ann.name == "linux-node" }
                assertTrue(p != null, "Kotlin did not discover the C++ node")
                assertEquals(srv.port, p!!.ann.tcpPort)
            }
            // Kotlin announces to a C++ `peers` listener
            val discPort = 40000 + (System.nanoTime() % 20000).toInt()
            val proc = ProcessBuilder(Interop.bin!!.path, "--data", File(d, "l").path, "peers", "--discover", "3", "--no-multicast", "--disc-port", "$discPort")
                .redirectErrorStream(true).start()
            Thread.sleep(500)
            repeat(8) { disc.announce(Announce(4321, "android-phone"), seeds = listOf(PeerAddr("127.0.0.1", discPort)), multicast = false); Thread.sleep(200) }
            val out = proc.inputStream.bufferedReader().readText(); proc.waitFor()
            assertTrue(me.nodeIdHex in out && "android-phone" in out && "4321" in out, out)
        }
    }
}
