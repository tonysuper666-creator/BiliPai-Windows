@Composable
internal fun DynamicMentionPickerDialog(
    onDismiss: () -> Unit,
    onSelected: (DynamicPublishMention) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<MentionSearchUser>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (loading) return
        loading = true
        message = null
        scope.launch {
            CommentRepository.searchMentionUsers(query).fold(
                onSuccess = {
                    users = it
                    message = if (it.isEmpty()) "没有找到用户" else null
                },
                onFailure = { message = it.message ?: "搜索失败" },
            )
            loading = false
        }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText("@ 用户") },
        text = {
            PublishPickerContent(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索昵称",
                actionLabel = if (loading) "搜索中…" else "搜索",
                onSearch = ::search,
                message = message,
            ) {
                users.take(8).forEach { user ->
                    AppListItem(
                        headlineContent = { AppText(user.name) },
                        supportingContent = { AppText("${user.fans} 粉丝") },
                        leadingContent = {
                            AsyncImage(
                                model = user.face,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(AppSpacingTokens.TripleExtraLarge)
                                    .clip(AppShapes.container(ContainerLevel.Pill)),
                                contentScale = ContentScale.Crop,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(DynamicPublishMention(user.uid, user.name)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { AppDialogAction(onClick = onDismiss) { AppText("取消") } },
    )
}

@Composable
internal fun DynamicTopicPickerDialog(
    onDismiss: () -> Unit,
    onSelected: (DynamicPublishTopic) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var topics by remember { mutableStateOf<List<DynamicTopicSearchItem>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun search() {
        if (loading) return
        loading = true
        message = null
        scope.launch {
            DynamicCreateRepository.searchPublishTopics(query).fold(
                onSuccess = {
                    topics = it
                    message = if (it.isEmpty()) "没有找到话题" else null
                },
                onFailure = { message = it.message ?: "搜索失败" },
            )
            loading = false
        }
    }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText("选择话题") },
        text = {
            PublishPickerContent(
                query = query,
                onQueryChange = { query = it },
                placeholder = "搜索话题",
                actionLabel = if (loading) "搜索中…" else "搜索",
                onSearch = ::search,
                message = message,
            ) {
                topics.take(8).forEach { topic ->
                    AppListItem(
                        headlineContent = { AppText("#${topic.name}#") },
                        supportingContent = topic.stat_desc.takeIf(String::isNotBlank)?.let { stat ->
                            { AppText(stat) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(DynamicPublishTopic(topic.id, topic.name)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { AppDialogAction(onClick = onDismiss) { AppText("取消") } },
    )
}

@Composable
internal fun DynamicEmotePickerDialog(
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    var emotes by remember { mutableStateOf(DynamicEmoteCatalog.snapshot()) }
    val emoteCatalogSessionKey = DynamicEmoteCatalog.currentSessionKey()
    LaunchedEffect(emoteCatalogSessionKey) { emotes = DynamicEmoteCatalog.ensureLoaded() }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { AppText("选择表情") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.ExtraSmall),
            ) {
                emotes.entries.take(40).chunked(4).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (text, url) ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSelected(text) },
                            ) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = text,
                                    modifier = Modifier.size(AppSpacingTokens.TripleExtraLarge),
                                    contentScale = ContentScale.Fit,
                                )
                                AppText(text, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { AppDialogAction(onClick = onDismiss) { AppText("取消") } },
    )
}

@Composable
private fun PublishPickerContent(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    actionLabel: String,
    onSearch: () -> Unit,
    message: String?,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacingTokens.Small)) {
        AppTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            singleLine = true,
        )
        AppTextButton(onClick = onSearch) { AppText(actionLabel) }
        message?.let { AppText(it) }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            content = { content() },
        )
    }
}
