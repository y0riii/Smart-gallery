package com.example.gallery.ml

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads bundled ONNX models by **memory-mapping them straight from the APK** — no copy to internal
 * storage, so each model is stored only once (in the APK) and never duplicated on disk.
 *
 * Why not `assets.readBytes()`: that allocates the whole model (~61 MB for the CLIP text model) on the
 * Java heap and needs ~2× that at peak, which OOMs on low-RAM devices (the bug that silently disabled
 * the text encoder and broke semantic search).
 *
 * The returned [MappedByteBuffer] is off-heap, backed by the APK's page cache. ONNX Runtime copies it
 * into the native session at `createSession`, so the buffer can be released afterwards.
 *
 * Requires the `.ort` assets to be stored **uncompressed** in the APK — see
 * `androidResources { noCompress += "ort" }` in app/build.gradle.kts — because `openFd` only works on
 * uncompressed assets. Pass `buffer.duplicate()` to `createSession` so the shared buffer's position
 * isn't consumed (lets the same model back several sessions, e.g. the face encoder's CPU/NNAPI probe).
 */
object ModelAssets {
    fun mappedModel(context: Context, assetName: String): MappedByteBuffer {
        context.assets.openFd(assetName).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                // A mapping stays valid after the channel/fd are closed (POSIX mmap), so returning it
                // from inside `use {}` is safe.
                return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        }
    }
}
