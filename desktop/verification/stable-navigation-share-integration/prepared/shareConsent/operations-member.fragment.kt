    // Original MessageRepository sendTextMessage/sendMessage; same Ops message API/epoch.
    private val videoShareMessageDeviceId by lazy { java.util.UUID.randomUUID().toString() }
    private val videoShareMessages by lazy {
        com.android.purebilibili.data.repository.DesktopOriginalVideoShareMessages(messages,
            { assertOwned(); repository.authCookies()["bili_jct"] },
            { assertOwned(); repository.account.value?.mid },
            { assertOwned(); videoShareMessageDeviceId })
    }
    suspend fun sendVideoShareText(receiverId:Long,content:String):Result<SendMessageData> = result {
        mutate { videoShareMessages.sendTextMessage(receiverId,content).getOrThrow() }
    }
    suspend fun downloadVideoShareCover(url:String):ByteArray = read {
        com.bilipai.desktop.ui.DesktopVideoShareImageTransport.download(guestWeb.callFactory(),url,::isOwned)
    }
