onBgmClick = { bgm ->
                                    val musicId = bgm.musicId.ifBlank {
                                        (BilibiliNavigationTargetParser.parse(bgm.jumpUrl) as? BilibiliNavigationTarget.Music)?.musicId.orEmpty()
                                    }
                                    if (musicId.isNotBlank()) {
                                        pushNavigation3Key(BiliPaiNavKey.BgmDetail(musicId, cid = videoKey.cid))
                                    } else if (bgm.jumpUrl.isNotBlank()) {
                                        pushNavigation3Key(BiliPaiNavKey.Web(bgm.jumpUrl, "发现音乐"))
                                    }
                                }