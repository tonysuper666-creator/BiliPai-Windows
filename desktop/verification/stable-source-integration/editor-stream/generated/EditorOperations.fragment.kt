// GENERATED original editor members; do not hand-maintain a second request algorithm.
// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/DynamicCreateRepository.kt
// LF-normalized SHA-256: 2f85544e1e973dd0e7178077ef5c1f439b798a1d86b46701803adeb2a7c0364f
// ORIGINAL app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt
// LF-normalized SHA-256: 4950b91a708e4d22ddbc6b6f39ef6880879e175dad543ff1fd5b833518b2b820

suspend fun publishDynamic(draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>): Result<String> = result {
    mutate { csrf ->
            val mid = repository.requireAccount().mid
            val pics = draft.imageUris.map { uriString ->
                uploadEditorImage(csrf, imageProvider(uriString))
            }
            val contents = buildDynamicCreateContents(
                text = draft.text,
                voteId = draft.voteId,
                voteTitle = draft.voteTitle,
                mentions = draft.mentions,
                emotes = draft.emotes,
            )
            if (contents.isEmpty() && pics.isEmpty()) {
                error("内容不能为空")
            }
            val request = DynamicCreateFeedRequest(
                dyn_req = DynamicCreateFeedReq(
                    content = DynamicCreateFeedContent(
                        contents = contents.ifEmpty {
                            listOf(DynamicRepostContentItem(raw_text = " ", type = 1, biz_id = ""))
                        },
                        title = draft.title.trim().takeIf { it.isNotEmpty() }
                    ),
                    scene = resolveDynamicCreateScene(pics.isNotEmpty()),
                    pics = pics.takeIf { it.isNotEmpty() },
                    attach_card = resolveEditorReserveAttachCard(draft.reserveId),
                    option = if (draft.private) DynamicCreateOption(private_pub = 1) else null,
                    topic = draft.topic?.takeIf { it.id > 0L }?.let {
                        DynamicCreateTopic(id = it.id, name = it.name)
                    },
                    upload_id = "${mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}"
                )
            )
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.createFeedDynamic(csrf = csrf, body = request)
            if (response.code != 0) {
                error(response.message.ifBlank { "发布失败" })
            }
            resolveCreatedDynamicId(response.data).ifBlank { "ok" }
        
    }
}

suspend fun editDynamic(dynamicId: String, draft: DynamicPublishDraft, imageProvider: suspend (String) -> Triple<String?, String?, okhttp3.RequestBody>): Result<Unit> = result {
    mutate { csrf ->
            if (dynamicId.isBlank()) error("无法识别该动态")
            val pics = draft.imageUris.map { uriString ->
                draft.existingImages.firstOrNull { it.img_src == uriString }
                    ?: uploadEditorImage(csrf, imageProvider(uriString))
            }
            val contents = buildDynamicCreateContents(
                text = draft.text,
                voteId = draft.voteId,
                voteTitle = draft.voteTitle,
                mentions = draft.mentions,
                emotes = draft.emotes,
            )
            if (contents.isEmpty() && pics.isEmpty()) error("内容不能为空")
            val mid = repository.requireAccount().mid
            val uploadId = "${mid}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 10000)}"
            val request = DynamicEditFeedRequest(
                dyn_req = DynamicCreateFeedReq(
                    content = DynamicCreateFeedContent(
                        contents = contents.ifEmpty {
                            listOf(DynamicRepostContentItem(raw_text = " ", type = 1, biz_id = ""))
                        },
                        title = draft.title.trim().takeIf(String::isNotEmpty),
                    ),
                    scene = resolveDynamicCreateScene(pics.isNotEmpty()),
                    pics = pics.takeIf { it.isNotEmpty() },
                    attach_card = resolveEditorReserveAttachCard(draft.reserveId),
                    option = if (draft.private) DynamicCreateOption(private_pub = 1) else null,
                    topic = draft.topic?.takeIf { it.id > 0L }?.let {
                        DynamicCreateTopic(id = it.id, name = it.name)
                    },
                    upload_id = uploadId,
                ),
                dyn_id_str = dynamicId,
            )
            coroutineContext.ensureActive(); assertOwned()
            val query = repository.signWebParams(mapOf(
                    "platform" to "web",
                    "csrf" to csrf,
                    "x-bili-device-req-json" to
                        "{\"platform\":\"web\",\"device\":\"pc\",\"spmid\":\"333.1368\"}",
                    "w_dyn_req.upload_id" to uploadId,
                    "w_dyn_req.meta" to
                        "{\"app_meta\":{\"from\":\"create.dynamic.web\",\"mobi_app\":\"web\"}}",
                ))
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.editFeedDynamic(query = query, body = request)
            if (response.code != 0) error(response.message.ifBlank { "编辑失败" })
        
    }
}

suspend fun createVote(title: String, options: List<String>, description: String, choiceCount: Int, durationDays: Int): Result<DynamicCreatedVote> = result {
    mutate { csrf ->
            val cleanedOptions = options.map { it.trim() }.filter { it.isNotEmpty() }
            if (title.isBlank() || cleanedOptions.size < 2) {
                error("至少填写标题和两个选项")
            }
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.createVote(
                csrf = csrf,
                body = DynamicCreateVoteRequest(
                    vote_info = DynamicCreateVoteInfo(
                        title = title.trim(),
                        desc = description.trim(),
                        choice_cnt = choiceCount.coerceIn(1, cleanedOptions.size),
                        duration = (durationDays * 24 * 60 * 60).coerceAtLeast(60),
                        options = cleanedOptions.map { DynamicCreateVoteOption(opt_desc = it) },
                        vote_publisher = repository.requireAccount().mid
                    )
                )
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "创建投票失败" })
            }
            val voteId = response.data?.vote_id ?: 0L
            if (voteId <= 0L) error("投票创建失败")
            DynamicCreatedVote(voteId = voteId, title = title.trim())
        
    }
}

suspend fun createReserve(title: String, livePlanStartTimeSeconds: Long, subType: Int): Result<DynamicCreatedReserve> = result {
    mutate { csrf ->
            if (title.isBlank()) error("请填写预约标题")
            coroutineContext.ensureActive(); assertOwned()
            val response = dynamic.createReserve(
                subType = subType,
                title = title.trim(),
                livePlanStartTime = livePlanStartTimeSeconds,
                csrf = csrf
            )
            if (response.code != 0) {
                error(response.message.ifBlank { "创建预约失败" })
            }
            val reserveId = response.data?.sid ?: 0L
            if (reserveId <= 0L) error("预约创建失败")
            DynamicCreatedReserve(reserveId = reserveId, title = title.trim())
        
    }
}

suspend fun searchPublishTopics(keyword: String): Result<List<DynamicTopicSearchItem>> = result {
    read {
                val response = api.searchDynamicPublishTopics(
                    keywords = keyword.trim().takeIf(String::isNotBlank),
                )
                if (response.code != 0) error(response.message.ifBlank { "搜索话题失败" })
                response.data?.topic_items.orEmpty()
                    .filter { it.id > 0L && it.name.isNotBlank() }
                    .distinctBy { it.id }
            
    }
}

suspend fun searchMentionUsers(keyword: String): Result<List<MentionSearchUser>> = result {
    read {
            val response = api.searchMentionUsers(keyword.trim().takeIf { it.isNotEmpty() })
            if (response.code == 0) {
                val users = response.data
                    ?.groups
                    .orEmpty()
                    .flatMap { it.items }
                    .filter { it.uid > 0L && it.name.isNotBlank() }
                    .distinctBy { it.uid }
                users
            } else {
                val errorMsg = when (response.code) {
                    -101 -> "请先登录后使用@好友"
                    else -> response.message.ifEmpty { "搜索@好友失败 (${response.code})" }
                }
                throw Exception(errorMsg)
            }
        
    }
}

private suspend fun uploadEditorImage(csrf: String, selected: Triple<String?, String?, okhttp3.RequestBody>): DynamicCreatePic {
        coroutineContext.ensureActive(); assertOwned()
        val mimeType = selected.second ?: "image/jpeg"
        val fileName = selected.first ?: "dyn_${System.currentTimeMillis()}.jpg"
        // 流式上传:空/15MB 校验在 CommentRepository 内基于文件尺寸完成,不再整文件读入内存。
        val uploaded = uploadEditorCommentImageBody(csrf,
            fileName = fileName,
            mimeType = mimeType,
            fileBody = selected.third
        )
        return DynamicCreatePic(
            img_src = uploaded.imgSrc,
            img_width = uploaded.imgWidth,
            img_height = uploaded.imgHeight,
            img_size = uploaded.imgSize
        )
}

private suspend fun uploadEditorCommentImage(csrf: String, fileName: String, mimeType: String, bytes: ByteArray): ReplyPicture {
        coroutineContext.ensureActive(); assertOwned()
        val mediaType = mimeType.toMediaType()
        return uploadEditorCommentImageBody(csrf,
            fileName = fileName.ifBlank { "comment_image.jpg" },
            mimeType = mimeType,
            fileBody = bytes.toRequestBody(mediaType)
        )
    
}

private suspend fun uploadEditorCommentImageBody(csrf: String, fileName: String, mimeType: String, fileBody: okhttp3.RequestBody): ReplyPicture {
        coroutineContext.ensureActive(); assertOwned()
            val part = okhttp3.MultipartBody.Part.createFormData(
                "file_up",
                fileName,
                fileBody
            )
            val textMedia = "text/plain".toMediaType()
            val categoryBody = "daily".toRequestBody(textMedia)
            val bizBody = "new_dyn".toRequestBody(textMedia)
            val csrfBody = csrf.toRequestBody(textMedia)
    
            val response = api.uploadCommentImage(
                fileUp = part,
                category = categoryBody,
                biz = bizBody,
                csrf = csrfBody
            )
    
            coroutineContext.ensureActive(); assertOwned()
            val uploadContext = coroutineContext
            if (!withOwnedEditorImageAdmission { uploadContext.ensureActive() }) throw CancellationException("Dynamic upload account owner retired")
            val data = response.data
            return if (response.code == 0 && data != null) {
                ReplyPicture(
                        imgSrc = data.imageUrl,
                        imgWidth = data.imageWidth,
                        imgHeight = data.imageHeight,
                        imgSize = data.imgSize
                    )
            } else {

                throw Exception(response.message.ifEmpty { "图片上传失败 (${response.code})" })
            }
        
}

    private fun resolveEditorReserveAttachCard(reserveId: Long): JsonObject? {
        if (reserveId <= 0L) return null
        return buildJsonObject {
            put("common_card", buildJsonObject {
                put("type", 14)
                put("biz_id", reserveId)
                put("reserve_source", 0)
                put("reserve_lottery", 0)
            })
        }
    }