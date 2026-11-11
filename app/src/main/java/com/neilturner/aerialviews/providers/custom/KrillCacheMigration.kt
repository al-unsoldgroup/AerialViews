package com.neilturner.aerialviews.providers.custom

import coil3.disk.DiskCache
import com.neilturner.aerialviews.data.CachedMediaEntity
import com.neilturner.aerialviews.data.PrivateReconciliationCopy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

class CacheCopyScan private constructor(
    val candidateFingerprint: String,
    val sourceDirectory: String,
    val destinationDirectory: String,
    copies: List<PrivateReconciliationCopy>,
) {
    val copies: List<PrivateReconciliationCopy> = copies.toList()

    companion object {
        fun fromCaches(
            source: DiskCache,
            destination: DiskCache,
            keys: Iterable<String>,
            checkpoint: () -> Unit = {},
        ): CacheCopyScan {
            val (fingerprint, manifest) = KrillCacheMigration.scanEntries(source, destination, keys, checkpoint)
            return CacheCopyScan(fingerprint, source.directory.toString(), destination.directory.toString(), manifest)
        }
    }
}

internal class CacheCandidateFingerprint {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(key: String) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        digest.update(
            java.nio.ByteBuffer
                .allocate(4)
                .putInt(bytes.size)
                .array(),
        )
        digest.update(bytes)
    }

    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Copies one known retained variant without changing the legacy cache. */
internal object KrillCacheMigration {
    /** The caller must fence writers and keep the old cache until this completes and the journal is durable. */
    fun copyKnownEntries(
        source: DiskCache,
        destination: DiskCache,
        keys: Iterable<String>,
        checkpoint: () -> Unit = {},
    ): CacheCopyScan = CacheCopyScan.fromCaches(source, destination, keys, checkpoint)

    internal fun scanEntries(
        source: DiskCache,
        destination: DiskCache,
        keys: Iterable<String>,
        checkpoint: () -> Unit = {},
    ): Pair<String, List<PrivateReconciliationCopy>> {
        require(source.directory != destination.directory)
        val copied = mutableSetOf<String>()
        val candidates = CacheCandidateFingerprint()
        for (key in keys) {
            checkpoint()
            candidates.add(key)
            if (key in copied) continue
            if (copyKnownEntry(source, destination, key, checkpoint)) copied.add(key)
        }
        // A bounded destination may evict an earlier copy while later entries are written.
        // Do not authorize an old-cache purge unless all copied entries remain intact.
        val manifest = mutableListOf<PrivateReconciliationCopy>()
        for (key in copied.sorted()) {
            checkpoint()
            val old = source.openSnapshot(key) ?: error("Source cache entry unavailable")
            old.use {
                val new = destination.openSnapshot(key) ?: error("Copied cache entry unavailable")
                new.use {
                    check(
                        digest(old.data.toFile(), checkpoint).contentEquals(digest(new.data.toFile(), checkpoint)),
                    ) { "Cache copy mismatch" }
                    check(
                        digest(old.metadata.toFile(), checkpoint).contentEquals(digest(new.metadata.toFile(), checkpoint)),
                    ) { "Cache metadata copy mismatch" }
                    manifest.add(
                        PrivateReconciliationCopy(
                            cacheKey = key,
                            dataDigest = hex(digest(new.data.toFile(), checkpoint)),
                            metadataDigest = hex(digest(new.metadata.toFile(), checkpoint)),
                        ),
                    )
                }
            }
        }
        return candidates.finish() to manifest
    }

    /** Stable order must match the journal transaction's view of retained rows. */
    fun retainedKnownKeys(
        rows: List<CachedMediaEntity>,
        namespace: String,
        origin: String,
        space: String,
        removed: Set<String>,
    ): Sequence<String> =
        sequence {
            for (row in rows.sortedBy { it.id }) {
                if (row.source != "KRILL" || row.type != "IMAGE" || !KrillPrivateFeed.isSelectedImage(row.uri, origin, space)) continue
                val id =
                    row.uri
                        .toHttpUrlOrNull()
                        ?.pathSegments
                        ?.getOrNull(3) ?: continue
                if (id in removed) continue
                yieldAll(knownKeys(namespace, origin, space, row.uri, row.focusRevision).sorted())
            }
        }

    fun verifyCopies(
        destination: DiskCache,
        manifest: List<PrivateReconciliationCopy>,
        checkpoint: () -> Unit = {},
    ) {
        for (entry in manifest) {
            checkpoint()
            val snapshot = destination.openSnapshot(entry.cacheKey) ?: error("Copied cache entry unavailable")
            snapshot.use {
                check(hex(digest(it.data.toFile(), checkpoint)) == entry.dataDigest) { "Cache copy mismatch" }
                check(hex(digest(it.metadata.toFile(), checkpoint)) == entry.metadataDigest) { "Cache metadata copy mismatch" }
            }
        }
    }

    private fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Enumerate variants still reachable with the saved focus revision; old revisions are intentionally omitted. */
    fun knownKeys(
        namespace: String,
        origin: String,
        space: String,
        imageUrl: String,
        focusRevision: Long?,
    ): Set<String> {
        require(KrillPrivateFeed.isSelectedImage(imageUrl, origin, space))
        val urls = mutableSetOf(imageUrl)
        KrillFeed.privatePanelSizes.forEach { (width, height) ->
            KrillFeed.privatePanelCrop(imageUrl, width, height, focusRevision)?.let(urls::add)
        }
        KrillFeed.privateDisplaySizes.forEach { (width, height) ->
            KrillFeed
                .cropForDimensions(imageUrl, 1, 1, width, height, privateSmartCrop = true, focusRevision = focusRevision)
                ?.let(urls::add)
        }
        return urls.mapTo(mutableSetOf()) { KrillCacheKey.forImage(namespace, it) }
    }

    fun copyKnownEntry(
        source: DiskCache,
        destination: DiskCache,
        key: String,
        checkpoint: () -> Unit = {},
    ): Boolean {
        require(source.directory != destination.directory)
        val snapshot = source.openSnapshot(key) ?: return false
        snapshot.use { old ->
            val expectedData = digest(old.data.toFile(), checkpoint)
            val expectedMetadata = digest(old.metadata.toFile(), checkpoint)
            destination.openSnapshot(key)?.use { existing ->
                check(digest(existing.data.toFile(), checkpoint).contentEquals(expectedData)) { "Conflicting cache entry" }
                check(digest(existing.metadata.toFile(), checkpoint).contentEquals(expectedMetadata)) { "Conflicting cache metadata" }
                return true
            }
            val editor = destination.openEditor(key) ?: error("Cache entry is busy")
            var committed = false
            try {
                copy(old.data.toFile(), editor.data.toFile(), checkpoint)
                copy(old.metadata.toFile(), editor.metadata.toFile(), checkpoint)
                check(digest(editor.data.toFile(), checkpoint).contentEquals(expectedData)) { "Cache copy mismatch" }
                check(digest(editor.metadata.toFile(), checkpoint).contentEquals(expectedMetadata)) { "Cache metadata copy mismatch" }
                editor.commit()
                committed = true
            } finally {
                if (!committed) editor.abort()
            }
            val copied = destination.openSnapshot(key) ?: error("Copied cache entry unavailable")
            copied.use {
                check(digest(it.data.toFile(), checkpoint).contentEquals(expectedData)) { "Committed cache copy mismatch" }
                check(digest(it.metadata.toFile(), checkpoint).contentEquals(expectedMetadata)) { "Committed cache metadata mismatch" }
            }
            return true
        }
    }

    private fun copy(
        source: File,
        destination: File,
        checkpoint: () -> Unit,
    ) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun digest(
        file: File,
        checkpoint: () -> Unit,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
