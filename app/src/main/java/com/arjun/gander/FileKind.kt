package com.arjun.gander

/**
 * What we know how to display, and which bundled HTML page renders it
 * (empty page means a native view handles it).
 */
enum class FileKind(val page: String) {
    IMAGE(""),
    IMAGE_WEB("imgweb.html"),
    PDF("pdf.html"),
    PLAYER(""),
    DOCX("docx.html"),
    XLSX("xlsx.html"),
    PPTX("pptx.html"),
    MD("md.html"),
    TEXT("text.html"),
    UNSUPPORTED("unsupported.html");

    companion object {
        private val imageExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
        private val imageWebExt = setOf("gif", "svg", "avif", "ico")
        private val wordExt = setOf("docx")
        private val sheetExt = setOf("xlsx", "xls", "xlsm", "xlsb", "csv", "ods")
        private val slideExt = setOf("pptx")
        private val mdExt = setOf("md", "markdown")
        private val videoExt = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3g2", "m2ts", "mts", "avi", "flv"
        )
        private val audioExt = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr"
        )
        private val textExt = setOf(
            "txt", "log", "json", "xml", "yaml", "yml",
            "kt", "java", "py", "js", "ts", "html", "htm", "css", "sh", "zsh", "bash",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql", "swift", "dart",
            "gradle", "properties", "toml", "ini", "cfg", "conf", "tex", "r", "csv"
        )

        private const val MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val MIME_ODS = "application/vnd.oasis.opendocument.spreadsheet"

        fun isAudioExt(ext: String) = ext in audioExt

        fun detect(ext: String, mime: String?): FileKind = when {
            // .ts is TypeScript unless the provider says it is a video container
            ext == "ts" && mime?.startsWith("video/") == true -> PLAYER
            ext in imageExt -> IMAGE
            ext in imageWebExt -> IMAGE_WEB
            ext == "pdf" -> PDF
            ext in videoExt || ext in audioExt -> PLAYER
            ext in wordExt -> DOCX
            ext in sheetExt -> XLSX
            ext in slideExt -> PPTX
            ext in mdExt -> MD
            ext in textExt -> TEXT
            mime == "application/pdf" -> PDF
            mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true -> PLAYER
            mime == MIME_DOCX -> DOCX
            mime == MIME_XLSX || mime == "application/vnd.ms-excel" ||
                mime == "text/csv" || mime == MIME_ODS -> XLSX
            mime == MIME_PPTX -> PPTX
            mime?.startsWith("image/") == true -> IMAGE_WEB
            mime?.startsWith("text/") == true -> TEXT
            mime == "application/json" || mime == "application/xml" -> TEXT
            else -> UNSUPPORTED
        }
    }
}
