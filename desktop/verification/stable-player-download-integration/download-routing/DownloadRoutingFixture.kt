package com.android.purebilibili.feature.download

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val root = Path.of(args.single()); Files.createDirectories(root)
    val output = root.resolve("owned.mp4"); Files.write(output, byteArrayOf(1,2,3))
    val task = DownloadTask(bvid="BV-fixture",cid=42,title="fixture",cover="",ownerName="",ownerFace="",
        duration=1,quality=80,qualityDesc="1080P",videoUrl="https://cdn.example/fixture",audioUrl="",
        status=DownloadStatus.COMPLETED,filePath=output.toString())
    var assertions=0
    fun expect(value:Boolean) { assertions++;check(value) }
    expect(resolveDownloadTaskClickTarget(task,true)==DownloadTaskClickTarget.OfflinePlayer)
    expect(resolveDownloadTaskClickTarget(task,false)==DownloadTaskClickTarget.OfflinePlayer)
    Files.delete(output)
    expect(resolveDownloadTaskClickTarget(task,true)==DownloadTaskClickTarget.OnlinePlayer)
    expect(resolveDownloadTaskClickTarget(task,false)==null)
    expect(resolveDownloadTaskClickTarget(task.copy(status=DownloadStatus.PAUSED),true)==null)
    expect(resolveDownloadTaskClickTarget(task.copy(filePath=null),true)==DownloadTaskClickTarget.OnlinePlayer)
    expect(task.id != task.copy(quality=64).id)
    expect(task.id != task.copy(isAudioOnly=true).id)
    Files.writeString(root.resolve("result.json"),"""{"passed":true,"cases":2,"assertions":$assertions,"externalHttp":0,"socketDns":0,"wholeStableRuntimeAccepted":false}""")
    println("PASS $assertions original cache routing/quality identity assertions")
}
