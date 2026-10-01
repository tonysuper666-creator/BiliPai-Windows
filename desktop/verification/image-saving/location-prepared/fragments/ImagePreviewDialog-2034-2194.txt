suspend fun saveImageToGallery(context: android.content.Context, imageUrl: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            //  检测图片格式
            val isGif = imageUrl.contains(".gif", ignoreCase = true)
            val isWebp = imageUrl.contains(".webp", ignoreCase = true)
            val isPng = imageUrl.contains(".png", ignoreCase = true)
            
            //  对于 GIF/WebP，直接下载原始字节流保留动画
            if (isGif || isWebp) {
                val url = java.net.URL(imageUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("Referer", "https://www.bilibili.com/")
                connection.connect()
                
                if (connection.responseCode != 200) {
                    Log.e("ImagePreview", "Failed to download: ${connection.responseCode}")
                    return@withContext false
                }
                
                // 先把下载流落到临时文件，再分发给保存目标，避免整块字节驻留 Java 堆。
                val tempFile = File.createTempFile("bilipai_save_", ".bin", context.cacheDir)
                try {
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
                connection.disconnect()

                // 生成文件名
                val extension = when {
                    isGif -> "gif"
                    isWebp -> "webp"
                    else -> "jpg"
                }
                val mimeType = when {
                    isGif -> "image/gif"
                    isWebp -> "image/webp"
                    else -> "image/jpeg"
                }
                val fileName = "BiliPai_${System.currentTimeMillis()}.$extension"

                val savedToCustomDirectory = tempFile.inputStream().use { input ->
                    saveStreamToCustomImageSaveDirectory(context, input, fileName, mimeType)
                }
                if (savedToCustomDirectory) {
                    tempFile.delete()
                    Log.d("ImagePreview", "Image saved to custom directory: $fileName")
                    return@withContext true
                }

                // 使用 MediaStore 保存
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, resolveDefaultImageMediaStoreRelativePath())
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                if (uri == null) {
                    tempFile.delete()
                    return@withContext false
                }

                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    tempFile.inputStream().use { input -> input.copyTo(outputStream, 64 * 1024) }
                }
                tempFile.delete()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
                
                Log.d("ImagePreview", "Image saved successfully: $fileName")
                return@withContext true
            }
            
            //  对于 JPEG/PNG 等静态图片，使用 Coil 下载并转换
            val imageLoader = context.imageLoader
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://www.bilibili.com/").build())
                .build()
            
            val result = imageLoader.execute(request)
            if (result !is SuccessResult) {
                Log.e("ImagePreview", "Failed to download image: $imageUrl")
                return@withContext false
            }
            
            val bitmap = (result.image as? coil3.BitmapImage)?.bitmap
            if (bitmap == null) {
                Log.e("ImagePreview", "Failed to convert drawable to bitmap")
                return@withContext false
            }
            
            // 生成文件名
            val extension = if (isPng) "png" else "jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val fileName = "BiliPai_${System.currentTimeMillis()}.$extension"
            val format = if (isPng) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG

            if (
                saveBitmapToCustomImageSaveDirectory(
                    context = context,
                    bitmap = bitmap,
                    fileName = fileName,
                    format = format,
                    quality = 95,
                    mimeType = mimeType
                )
            ) {
                Log.d("ImagePreview", "Image saved to custom directory: $fileName")
                return@withContext true
            }
            
            // 使用 MediaStore 保存图片
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, resolveDefaultImageMediaStoreRelativePath())
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext false
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(format, 95, outputStream)
            }
            
            // 标记保存完成
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }
            
            Log.d("ImagePreview", "Image saved successfully: $fileName")
            true
        } catch (e: Exception) {
            Log.e("ImagePreview", "Error saving image", e)
            false
        }
    }
}
