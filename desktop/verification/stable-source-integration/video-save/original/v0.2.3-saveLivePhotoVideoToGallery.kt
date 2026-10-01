suspend fun saveLivePhotoVideoToGallery(context: android.content.Context, videoUrl: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL(videoUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            connection.connect()

            if (connection.responseCode !in 200..299) {
                Log.e("ImagePreview", "Failed to download live video: ${connection.responseCode}")
                return@withContext false
            }

            val fileName = "BiliPai_Live_${System.currentTimeMillis()}.mp4"
            val mimeType = "video/mp4"

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BiliPai")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: run {
                connection.inputStream.close()
                connection.disconnect()
                return@withContext false
            }

            // 视频直接从下载流写入 MediaStore，避免把整段 MP4 读进 Java 堆。
            connection.inputStream.use { input ->
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    input.copyTo(outputStream, 64 * 1024)
                }
            }
            connection.disconnect()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            Log.d("ImagePreview", "Live photo video saved successfully: $fileName")
            true
        } catch (e: Exception) {
            Log.e("ImagePreview", "Error saving live photo video", e)
            false
        }
    }
}
