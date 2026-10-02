from pathlib import Path
import hashlib,json,difflib,importlib.util
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def patch(path,before,after):
 original=(C/path).read_bytes();s=original.decode('utf8').replace('\r\n','\n');assert s.count(before)==1,(path,before)
 new=s.replace(before,after,1);dest=P/'prepared'/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(new,encoding='utf8',newline='\n')
 base=P/'baseline'/path;base.parent.mkdir(parents=True,exist_ok=True);base.write_bytes(original)
 (P/(Path(path).stem+'.patch')).write_text(''.join(difflib.unified_diff(s.splitlines(True),new.splitlines(True),fromfile=path,tofile=path)),encoding='utf8',newline='\n')
 return dict(target=path,baseRawSha256=hashlib.sha256(original).hexdigest(),baseLfSha256=hashlib.sha256(s.encode()).hexdigest(),desiredRawSha256=hashlib.sha256(new.encode()).hexdigest(),kind='exact-hunk')
records=[]
path='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt'
original=(C/path).read_text(encoding='utf8').replace('\r\n','\n');before=original[original.index('    internal fun ownedHomeCallFactory'):original.index('\n\n    /** Services',original.index('    internal fun ownedHomeCallFactory'))]
after=before.replace('guest: Boolean = false)', 'guest: Boolean = false, transport: OkHttpClient = client)').replace('            client.newCall(', '            transport.newCall(')
records.append(patch(path,before,after))
path='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopCommunityRepository.kt';before='    private val messageDeviceId = UUID.randomUUID().toString()'
after=before+'''

    /** Complete original message protocol; same Community transport and epoch-tagged Jar.
     * No mutation transport retry or additional client/pool/cache/session owner. */
    internal fun originalMessagePages(owner: com.bilipai.desktop.ui.DesktopMessagePageAdmission):
        com.bilipai.desktop.ui.DesktopMessagePageServices {
        val factory = owner.callFactory(client)
        fun service(base: String) = Retrofit.Builder().baseUrl(base).callFactory(factory)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
        val ownedWeb = service("https://api.bilibili.com/")
        val ownedApi = ownedWeb.create(BilibiliApi::class.java)
        val ownedSpace = ownedWeb.create(SpaceApi::class.java)
        val ownedMessages = service("https://api.vc.bilibili.com/").create(MessageApi::class.java)
        val users = com.android.purebilibili.feature.message.DesktopDynamicMessageUserInfoLoader(
            ownedApi, ownedSpace) { params -> owner.sign(params, ownedApi) }
        return com.bilipai.desktop.ui.DesktopMessagePageServices(
            com.android.purebilibili.data.repository.DesktopOriginalMessageRepository(ownedMessages, ownedApi, owner) { messageDeviceId },
            users::fetch,
            { bvid -> owner.runCatching {
                val response = ownedApi.getVideoInfo(bvid)
                if (response.code != 0) error(response.message)
                requireNotNull(response.data) { "视频信息为空" }
            } })
    }
'''
records.append(patch(path,before,after))
(P/'prospective-hunks.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf8')
spec=importlib.util.spec_from_file_location('message_generator',P/'prepared/desktop/tools/extract-upstream-message-pages.py');g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g);g.generate(C,P/'generated',True)
(P/'source-inventory.json').write_text(json.dumps(g.inventory(C),ensure_ascii=False,indent=2),encoding='utf8')
