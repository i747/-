package io.legado.app.utils

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.legado.app.exception.NoStackTraceException
import splitties.init.appCtx
import splitties.systemservices.downloadManager
import java.io.File
import java.nio.charset.Charset


data class FileDoc(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val uri: Uri
) {

    override fun toString(): String {
        return if (uri.isContentScheme()) uri.toString() else uri.path!!
    }

    val isContentScheme get() = uri.isContentScheme()

    fun readBytes(): ByteArray {
        return uri.readBytes(appCtx)
    }

    companion object {

        fun fromUri(uri: Uri, isDir: Boolean): FileDoc {
            if (uri.isContentScheme()) {
                val doc = if (isDir) {
                    DocumentFile.fromTreeUri(appCtx, uri)!!
                } else if (uri.host == "downloads") {
                    val query = DownloadManager.Query()
                    query.setFilterById(uri.lastPathSegment!!.toLong())
                    downloadManager.query(query).use {
                        if (it.moveToFirst()) {
                            val lUriColum = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val lUri = it.getString(lUriColum)
                            DocumentFile.fromSingleUri(appCtx, Uri.parse(lUri))!!
                        } else {
                            DocumentFile.fromSingleUri(appCtx, uri)!!
                        }
                    }
                } else {
                    DocumentFile.fromSingleUri(appCtx, uri)!!
                }
                return FileDoc(doc.name ?: "", true, doc.length(), doc.lastModified(), doc.uri)
            }
            val file = File(uri.path!!)
            return FileDoc(file.name, isDir, file.length(), file.lastModified(), uri)
        }

        fun fromDocumentFile(doc: DocumentFile): FileDoc {
            return FileDoc(
                name = doc.name ?: "",
                isDir = doc.isDirectory,
                size = doc.length(),
                lastModified = doc.lastModified(),
                uri = doc.uri
            )
        }

        fun fromFile(file: File): FileDoc {
            return FileDoc(
                name = file.name,
                isDir = file.isDirectory,
                size = file.length(),
                lastModified = file.lastModified(),
                uri = Uri.fromFile(file)
            )
        }

    }
}

/**
 * 过滤器
 */
typealias FileDocFilter = (file: FileDoc) -> Boolean

/**
 * 返回子文件列表,如果不是文件夹则返回null
 */
fun FileDoc.list(filter: FileDocFilter? = null): ArrayList<FileDoc>? {
    if (isDir) {
        if (uri.isContentScheme()) {
            /**
             * DocumentFile 的 listFiles() 非常的慢,所以这里直接从数据库查询
             */
            val childrenUri = DocumentsContract
                .buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
            val docList = arrayListOf<FileDoc>()
            var cursor: Cursor? = null
            try {
                cursor = appCtx.contentResolver.query(
                    childrenUri,
                    DocumentUtils.projection,
                    null,
                    null,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )
                cursor?.let {
                    val ici = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val dci = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (cursor.moveToFirst()) {
                        do {
                            val item = FileDoc(
                                name = cursor.getString(nci),
                                isDir = cursor.getString(mci) == DocumentsContract.Document.MIME_TYPE_DIR,
                                size = cursor.getLong(sci),
                                lastModified = cursor.getLong(dci),
                                uri = DocumentsContract.buildDocumentUriUsingTree(
                                    uri,
                                    cursor.getString(ici)
                                )
                            )
                            if (filter == null || filter.invoke(item)) {
                                docList.add(item)
                            }
                        } while (cursor.moveToNext())
                    }
                }
            } finally {
                cursor?.close()
            }
            return docList
        } else {
            return File(uri.path!!).listFileDocs(filter)
        }
    }
    return null
}

fun FileDoc.find(name: String): FileDoc? {
    return list {
        it.name == name
    }?.firstOrNull()
}

/**
 * DocumentFile 的 listFiles() 非常的慢,尽量不要使用
 */
fun DocumentFile.listFileDocs(filter: FileDocFilter? = null): ArrayList<FileDoc>? {
    return FileDoc.fromDocumentFile(this).list(filter)
}

@Throws(Exception::class)
fun DocumentFile.writeText(context: Context, data: String, charset: Charset = Charsets.UTF_8) {
    uri.writeText(context, data, charset)
}

@Throws(Exception::class)
fun DocumentFile.writeBytes(context: Context, data: ByteArray) {
    uri.writeBytes(context, data)
}

@Throws(Exception::class)
fun DocumentFile.readText(context: Context): String {
    return String(readBytes(context))
}

@Throws(Exception::class)
fun DocumentFile.readBytes(context: Context): ByteArray {
    return context.contentResolver.openInputStream(uri)?.let {
        val len: Int = it.available()
        val buffer = ByteArray(len)
        it.read(buffer)
        it.close()
        return buffer
    } ?: throw NoStackTraceException("打开文件失败\n${uri}")
}