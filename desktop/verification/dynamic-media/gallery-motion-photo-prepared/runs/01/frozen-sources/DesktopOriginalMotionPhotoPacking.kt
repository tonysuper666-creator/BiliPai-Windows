// GENERATED selected original JPEG APP1/XMP assembly; do not hand-maintain a second packing algorithm.
// Original: app/src/main/java/com/android/purebilibili/feature/dynamic/components/ImagePreviewDialog.kt
// LF SHA-256: 8ab6d642e5085483ffa5fbe684cb962468c6b93daec46c1eb768e98f8b3fe0b0
package com.android.purebilibili.feature.dynamic.components
internal fun desktopOriginalMotionPhotoJpeg(jpegWithExif: ByteArray, videoSize: Long): ByteArray {
            // 4. 构建 Google / Android 官方 Motion Photo 1.0 标准 XMP 元数据（兼容 MicroVideo、小米 MiCamera 与新版 Container 规范）
            // videoSize 已在下载落盘时确定（XMP 的 MicroVideoOffset / Container Length 用）
            val xmpString = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:MiCamera="http://ns.xiaomi.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        Camera:MotionPhoto="1"
        Camera:MotionPhotoVersion="1"
        Camera:MotionPhotoPresentationTimestampUs="0"
        GCamera:MotionPhoto="1"
        GCamera:MotionPhotoVersion="1"
        GCamera:MotionPhotoPresentationTimestampUs="0"
        GCamera:MicroVideo="1"
        GCamera:MicroVideoVersion="1"
        GCamera:MicroVideoOffset="$videoSize"
        GCamera:MicroVideoPresentationTimestampUs="0"
        MiCamera:MotionPhoto="1"
        MiCamera:MotionPhotoVersion="1"
        MiCamera:MotionPhotoPresentationTimestampUs="0">
      <Camera:MotionPhoto>1</Camera:MotionPhoto>
      <Camera:MotionPhotoVersion>1</Camera:MotionPhotoVersion>
      <Camera:MotionPhotoPresentationTimestampUs>0</Camera:MotionPhotoPresentationTimestampUs>
      <GCamera:MotionPhoto>1</GCamera:MotionPhoto>
      <GCamera:MotionPhotoVersion>1</GCamera:MotionPhotoVersion>
      <GCamera:MotionPhotoPresentationTimestampUs>0</GCamera:MotionPhotoPresentationTimestampUs>
      <GCamera:MicroVideo>1</GCamera:MicroVideo>
      <GCamera:MicroVideoVersion>1</GCamera:MicroVideoVersion>
      <GCamera:MicroVideoOffset>$videoSize</GCamera:MicroVideoOffset>
      <GCamera:MicroVideoPresentationTimestampUs>0</GCamera:MicroVideoPresentationTimestampUs>
      <MiCamera:MotionPhoto>1</MiCamera:MotionPhoto>
      <MiCamera:MotionPhotoVersion>1</MiCamera:MotionPhotoVersion>
      <MiCamera:MotionPhotoPresentationTimestampUs>0</MiCamera:MotionPhotoPresentationTimestampUs>
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
                Item:Mime="image/jpeg"
                Item:Semantic="Primary"
                Item:Length="0"
                Item:Padding="0"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
                Item:Mime="video/mp4"
                Item:Semantic="MotionPhoto"
                Item:Length="$videoSize"
                Item:Padding="0"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
""".trimIndent()

            // 5. 打包 JPEG APP1 XMP 数据段
            val xmpNamespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
            val xmpPayload = xmpString.toByteArray(Charsets.UTF_8)
            val app1PayloadLen = xmpNamespace.size + xmpPayload.size
            val app1Len = app1PayloadLen + 2
            val app1Segment = java.io.ByteArrayOutputStream().apply {
                write(0xFF)
                write(0xE1)
                write((app1Len shr 8) and 0xFF)
                write(app1Len and 0xFF)
                write(xmpNamespace)
                write(xmpPayload)
            }.toByteArray()

            // 6. 确定 XMP 插入位置：紧跟在 EXIF APP1 之后，确保 EXIF 永远位于第一个 APP1
            var insertPos = 2
            var offset = 2
            while (offset + 4 < jpegWithExif.size) {
                if ((jpegWithExif[offset].toInt() and 0xFF) != 0xFF) break
                val marker = jpegWithExif[offset + 1].toInt() and 0xFF
                if (marker == 0xDA || marker == 0xD9) break // SOS or EOI
                val segLen = ((jpegWithExif[offset + 2].toInt() and 0xFF) shl 8) or (jpegWithExif[offset + 3].toInt() and 0xFF)
                if (marker == 0xE1 && offset + 8 <= jpegWithExif.size) {
                    val isExif = jpegWithExif[offset + 4] == 'E'.code.toByte() &&
                                 jpegWithExif[offset + 5] == 'x'.code.toByte() &&
                                 jpegWithExif[offset + 6] == 'i'.code.toByte() &&
                                 jpegWithExif[offset + 7] == 'f'.code.toByte()
                    if (isExif) {
                        insertPos = offset + 2 + segLen
                        break
                    }
                }
                offset += 2 + segLen
            }

            // 7. 组装 Motion Photo：JPEG头部 + APP1 XMP + JPEG剩余数据与EOI；
            //    MP4 视频数据不再进堆，写输出时从临时文件流式追加。
            val jpegSegmentBytes = java.io.ByteArrayOutputStream(jpegWithExif.size + app1Segment.size).apply {
                write(jpegWithExif, 0, insertPos)
                write(app1Segment)
                write(jpegWithExif, insertPos, jpegWithExif.size - insertPos)
            }.toByteArray()

    return jpegSegmentBytes
}
