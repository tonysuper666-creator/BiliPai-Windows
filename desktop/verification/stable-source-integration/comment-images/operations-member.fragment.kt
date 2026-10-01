
    // Desktop original comment image streaming binding. Reuse the editor's one original upload body.
    suspend fun uploadCommentImageBody(fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): Result<ReplyPicture> =
        result { mutate { csrf -> uploadEditorCommentImageBody(csrf, fileName, mimeType, fileBody) } }
