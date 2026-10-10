from v025_source_paths import canonical_source as _desktop_canonical_source, canonical_relative as _desktop_canonical_relative
from pathlib import Path
from v025_playback_settings_platform import player_settings_body as _playback_player_settings_body, control_settings_body as _playback_control_settings_body
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
from v025_source_paths import canonical_source as _desktop_canonical_source
sys.dont_write_bytecode=True
REPO=None;OUTPUT=None;STANDALONE=False
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
COMMENT_THREAD_BLUR_RECIPE={'source': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentThreadNavigationBlur.kt', 'sourceSha256LF': '728a23616566e63a0b93cce99a4b4b1002b3d11449c184a07c6a7bf606019d20', 'target': 'com/android/purebilibili/feature/video/ui/components/CommentThreadNavigationBlur.kt', 'mode': 'policy-extract', 'outputSha256LF': '538fa54e8178e82911627f4e4f48f5fc26f57951fff9c5b603b52763c6260d69', 'edits': [{'start': 0, 'end': 2, 'beforeSha256LF': '50c82c2d06399a1941b4de24431782c48be653b3828e4388c1b0f451f230d35f', 'after': None}, {'start': 2, 'end': 5, 'beforeSha256LF': 'c7fe395df0293ffab24c732257aedc915d9e8bb6e070ac0a006cfaf814c3b7c4', 'after': 'import androidx.compose.ui.graphics.BlurEffect\nimport androidx.compose.ui.graphics.TileMode\n'}, {'start': 5, 'end': 6, 'beforeSha256LF': '017f6c5db3fce6bb4c99154ab563118e8f7e9c7445d7f80b3414b3b09969d527', 'after': None}, {'start': 6, 'end': 7, 'beforeSha256LF': '4c04333861f918c5c9bff52bf6526a24bb40b0fe6745f2b23cfccecf2a96772e', 'after': ''}, {'start': 7, 'end': 15, 'beforeSha256LF': 'a097119266e404dc8045398e90f7cb9a2ac1ef45d65e5caa13ad5216c685437a', 'after': None}, {'start': 15, 'end': 16, 'beforeSha256LF': '6e04324d2451f8e0a4819cbccbea8a195760f880c00a289a2ca1f7d44880db65', 'after': '    run {\n'}, {'start': 16, 'end': 18, 'beforeSha256LF': '4cd023b78a330f3044dbefd38730fdfc90fcdccb4c0310af64d79c432084a06c', 'after': None}, {'start': 18, 'end': 19, 'beforeSha256LF': '5a4ec1233124d2b369db7d10c0dfd0ee3dfb49974bfdbe16aab6c95f34cb0ba7', 'after': '            renderEffect = BlurEffect(\n'}, {'start': 19, 'end': 21, 'beforeSha256LF': 'd61654230531cd8dd7c9c06feee0ac3b5f6f6f843680830e298d7cef9ef58bee', 'after': None}, {'start': 21, 'end': 23, 'beforeSha256LF': '9621ccfbf957af542e7fd2faaf8cbe62a998ed4e954745b5912950ba935bad85', 'after': '                TileMode.Clamp,\n            )\n'}, {'start': 23, 'end': 26, 'beforeSha256LF': 'b4a365a90ec11e90432752cf9376ec5ed36b80ba5e8e6854fcf98970d3044469', 'after': None}], 'fullOriginalInverse': True}

def render_v025_comment_thread_blur():
 r=COMMENT_THREAD_BLUR_RECIPE;raw=read(r['source'][len(BASE):]);lines=raw.splitlines(keepends=True);cursor=0;body=[];inverse=[]
 for e in r['edits']:
  assert e['start']==cursor;cursor=e['end'];before=''.join(lines[e['start']:e['end']]);assert sha(before)==e['beforeSha256LF'];body.append(before if e['after']is None else e['after']);inverse.append(before)
 assert cursor==len(lines)and''.join(inverse)==raw;body=''.join(body);assert sha(body)==r['outputSha256LF'];emit(r['target'].removeprefix('com/android/purebilibili/'),body,r['source'][len(BASE):],'policy-extract')

def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
SOURCE_PINS={'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBar.kt': {'sha256LF': '60dc6bda0a8930d3a99838a0828a9885d6a358991ab25fa0450a7542c3667753', 'gitBlob': '9393faa4417e93a81d356bcadca040522e7550e5'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/TopControlBar.kt': {'sha256LF': '9a85c36745eb9304f8fcdffa6fd373f4c05f234bcb26a94d6cddf6b0b4ba782b', 'gitBlob': 'dce70ec07d4e1fcd75b2d681725abdb88f38efd7'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBarLayoutPolicy.kt': {'sha256LF': 'afa1b26018559c2418e84b8fef08b54a5d1733d674f302ffea39e3066afab5ce', 'gitBlob': '9aa721d49fc7abf96d54cc4883d271f031337c06'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/TopControlBarLayoutPolicy.kt': {'sha256LF': 'caa69fb20cad28f3d923902edf640ce8fccd3a68846685980a6f7b783fdfe6d6', 'gitBlob': 'd68abaa895ab25c98bc2aa8ac8aa9376af6e98b1'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/SubtitleControlModel.kt': {'sha256LF': '44b3f6ff1010e7d4dc9c7d6e206b9991e542db5e946fcb0715203e1b7afcbb0d', 'gitBlob': 'bb6e232d783e39606da1af8b6a6454f83837d747'}, 'app/src/main/java/com/android/purebilibili/feature/video/subtitle/BiliSubtitlePolicy.kt': {'sha256LF': '293fbe38900fa4b3bf1e36be3bec8a0b3a2f8a8383420f4654e2fa79ae244067', 'gitBlob': '3015d6f28ba72c9a65a4174135582b687abc1fdd'}, 'app/src/main/java/com/android/purebilibili/feature/video/progress/PbpProgressPolicy.kt': {'sha256LF': '16a4c6fc54f31d25f0c9bc1dba59556bf2f2dbc4431da13f576bc0fb3a032e85', 'gitBlob': '8c8554b7c27c31ac5e504ce77dae0bcfbc67ae83'}, 'app/src/main/java/com/android/purebilibili/feature/anime4k/Anime4KConfig.kt': {'sha256LF': '2bdfd821d4f0d03f1a7dad1c06779df254d7cfc5763c36e917ea981f7dd712b7', 'gitBlob': '30f1e6cabb93271e829cc300df7ae8a8834b0833'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/NativeDanmakuToggleButton.kt': {'sha256LF': '42de96f46b90175408f1b5d7f7ac5964566e763a3a3c5c529c5f4726775ab2a0', 'gitBlob': '31e063ee1f4d24e497843d78200130df706d295b'}, 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt': {'sha256LF': '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c', 'gitBlob': '5d24281dc2152c1e2113ab3476418f61261cd15a'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoAspectRatio.kt': {'sha256LF': 'e33c633370a8b856e0e1dda058f02c73ec1debd973353a384fba78ed686f1f3e', 'gitBlob': 'e0453569d9d489d56832358df4651415cbf7a76e'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/SeekPreviewBubble.kt': {'sha256LF': 'fa02b7e87634c56e67fa7027de72a5216f54aee96d8a158d1bf70ad61d63be75', 'gitBlob': '0cc8524926ff814d3d929c90c1568de26e0ac6d1'}, 'app/src/main/java/com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayPolicy.kt': {'sha256LF': 'd07d85ceeb8185773f58f7dd2721c4299f12ae3bacd021b7db6e2d5ca29ec666', 'gitBlob': '6c76df45756d29fce100fbd4122cf53defa748ec'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/PlayerMiuixListPopup.kt': {'sha256LF': 'ecf93e20d5f2a197ef9b4f27a8e443f2ae890b2d4d88cd1f270306cfcbd15e34', 'gitBlob': '33ebaa6e81261bc7757cb20e834f9eb13d7bb081'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/AudioQualitySelectionMenu.kt': {'sha256LF': 'a22de4a8ee797a7d707ea60c42962f0635ca9799ce5047d3f23de83d6fd819db', 'gitBlob': '140380e37adc3c3142b34e4294b035e00ec21c56'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/DanmakuComposerPolicy.kt': {'sha256LF': '7cd122dc036b1a7a5102c1bc78ee8c90ac68eed4a16636cde0cc23c0ee83d923', 'gitBlob': 'd516aeebfe224e719c8697020560d7923d633742'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlaybackButtonComponents.kt': {'sha256LF': 'b07b54a20a0f382fc1832a59b3aee209232623bfd590b2bfb48a8dd754016f8a', 'gitBlob': '4f0a5d441d1b76ad96c82acfdd3d52c3dc96682d'}, 'app/src/main/java/com/android/purebilibili/feature/video/playback/policy/PlaybackTransitionPolicy.kt': {'sha256LF': '12174fba9f6f82d0652241e4565d6f6f65d8ecaf59bf7deae272f9a968a20829', 'gitBlob': 'd27ff4c0820380c13d50d8107d1cac2c6b1d6dda'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlayerOverlayModels.kt': {'sha256LF': '21a5b543af28d39668e2728af84ddf2779f4ffc5bfa469d385b096cedb80db21', 'gitBlob': '40027f14dadd19a232b21d8366015630b00fef69'}, 'app/src/main/java/com/android/purebilibili/core/store/player/PlayerSettingsStore.kt': {'sha256LF': 'a3af07012eae8421764464a7b857ac301a2dd9b2e77a049c62239b60779536ed', 'gitBlob': '4b2178864251903c79e5d70b98e2f0a4e10dddac'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/SpeedSelectionPanel.kt': {'sha256LF': 'c1d3395d19f6a4f6ffd6c76cd7aabebd62a2b6a3399b348935235e0a7360b16a', 'gitBlob': '983f97e7219bb7193481730a96cf04c4e8a537b5'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ChapterListPanel.kt': {'sha256LF': '90fa563eb0c9d8b4359cc7d36199277f7529312089717a5a8bf55cf49247b61c', 'gitBlob': '99496ac6359f18223e0841bd82bc7df09ada14d9'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/LandscapeSidePanel.kt': {'sha256LF': 'c22cba7b4b953312f7e0cc6f2dafa7f02626b3ca874efa16a253cf94508b61ec', 'gitBlob': '575911102e9e3609edb85e99438056159dc108cf'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/PagesSelectorLayoutPolicy.kt': {'sha256LF': '9a73e4ade99ef5b8842c1e4110cb7afb0b6c8d8afa643951b559e6ce1a5025bc', 'gitBlob': '02e1491b4e176daf4ab500e0f43087381ce90c33'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/gesture/TwoFingerSpeedGesturePolicy.kt': {'sha256LF': 'fd6ca13d1430fa5fbee2520e1f0f007cc70fa3ad68586df0130d543fdabb07e2', 'gitBlob': '89468057774c215b11fb5618f74d150bf7946b9b'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoSettingsPanelActionPolicy.kt': {'sha256LF': 'fec3bc4195fe564506ff096efab8f0af701ee4ac1f8e996c4a351b3de828c279', 'gitBlob': 'f62f121cbbbbe714c0bdfcb2622e2b0b4450ddb6'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ModalChildScroll.kt': {'sha256LF': '5b70062a25596822ddb0d4144582b0fed424eb195b74a7648252b837d75f4f26', 'gitBlob': '3b706be5f60995c2a6bd8bcefa7967442fa8c35b'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/QualityMenu.kt': {'sha256LF': '22387369f318804f37c873e15c63fff710a09a7df4557592b63d496eac586885', 'gitBlob': 'e21941e7922de36ff3ea53da819ebff9e3006273'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoSettingsPanel.kt': {'sha256LF': 'ce5dcf5d2ab21916a4dd99e6dec25526455dadb01961002e5b928a552ef4b48a', 'gitBlob': 'da1415fe2b1b0b13909de1fe87a16f2ab2cda531'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/PagesSelector.kt': {'sha256LF': '266983619e98b538c4daad21f30314f74be76a67231bc49c072e387af3c21eec', 'gitBlob': '18bb7912d85bbe1dff2904f75b003d47a1b35de6'}, 'app/src/main/java/com/android/purebilibili/core/ui/components/PlaybackSpeedPreferenceControl.kt': {'sha256LF': '3f8db9bfa5dd44d269b59c46917bd2fc437690bb49df391875501f1117e08d84', 'gitBlob': 'a5b12dca89c583c5fdd23bf71c09aabf69f266b1'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayVisualPolicy.kt': {'sha256LF': '97f53347fdc8089128a2c62f582cae699b48bda3e1390f9041a456d7ac561f25', 'gitBlob': 'fbe043740d807b03926dd859941be31cc350452f'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/LandscapeEndDrawerLayoutPolicy.kt': {'sha256LF': '71d09f67f61c962f9831243e5b1e9b100a3fe3e2615d986cd17f02ab38f57bab', 'gitBlob': 'f846c1b2c7d78b5bd9bca710cd7201ff447b5357'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlaybackOrderDisplayPolicy.kt': {'sha256LF': '97d7a1c9d776cc753161b379cf6b3623db2c341cac757fe5faab6b262277626a', 'gitBlob': 'dbb238dbd4d5b5e9b14a3d0627fe7254872297a5'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlaybackOrderSelectionSheetPolicy.kt': {'sha256LF': '6e631e94ec5b1233439428ba63284720f47ec45d181e8528446e7ef0857a49ad', 'gitBlob': '8f6d95257cca37564e9056f3e7b2ef0a302dc84a'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/LandscapeDanmakuComposer.kt': {'sha256LF': 'c6632591993e20fbe60d1c50dfba23d617627509254eb24f127e3335a1b4dd1d', 'gitBlob': '7f2cd51bc93f9a56c6ee4cc8e0e3272be57a616c'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PortraitTopBarLayoutPolicy.kt': {'sha256LF': '92e7034534fa02b26fd93dc1c3af26232f55e8e57fca53d5f7c47653d4b70e08', 'gitBlob': 'd25e96bb94506624457c76faa22918e6b8eeff27'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/ImmersiveStatusBarBackdrop.kt': {'sha256LF': 'b6d0ff92ec86ea261173c32b8dd704615f5732a141f40184ffef926af852f3cd', 'gitBlob': '3da5b94d22a6ebc333cd26d368bab278f53b9966'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PersistentProgressBar.kt': {'sha256LF': '6ad6ecece75468f4e11cd257710c1b8df78e3b695cca2eb26d0e485a5ea41e62', 'gitBlob': '0fb0999aaa522107ab2af9bcf21a4775b7f0dc19'}, 'app/src/main/java/com/android/purebilibili/core/store/PlaybackCompletionBehaviorSyncPolicy.kt': {'sha256LF': '8d42f7cdd80255a46b07399725f103a6e224cdfc86cd11d326ef41d35f2f51cb', 'gitBlob': '25a8fc45b449f00648fa320691d46831daa988a4'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlaybackOrderSelectionSheet.kt': {'sha256LF': '3b3823b94bfc7e27e5d86f9b1dc0e0b114cc154d6491aeef287e3381fcb6c14f', 'gitBlob': '5e6b2515d10aa66761842666390564161eb0484d'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/DanmakuSendDialog.kt': {'sha256LF': 'a5ac32f6b3e1e0810e1c40769bb691dcb1e769080f7d6f454ee9eb778f8c219c', 'gitBlob': 'a81792cd0113e20d0a49b0eb7f05035198be7241'}, 'app/src/main/java/com/android/purebilibili/core/ui/performance/PanelFrameRateOverridePolicy.kt': {'sha256LF': 'fe1ec4e0e6ad0988d91f59d1dc90cfc36bd17d76a1d8e85ad6ca638fb6311dfd', 'gitBlob': 'c6a9fd1dc0f14029ca27a22ddce2a113ab349c01'}, 'app/src/main/java/com/android/purebilibili/feature/video/playback/session/PlaybackUserActionTracker.kt': {'sha256LF': 'b1a7ed25eedc54760015f1e7684541a3bcfb060302bda1def480c8e5fa4c1c40', 'gitBlob': '781efc738b220ab6814a2fdc155f5209741d8358'}, 'app/src/main/java/com/android/purebilibili/feature/video/usecase/VideoPlaybackUseCase.kt': {'sha256LF': 'bb0f36c9d2bb6aa8dfecc2a6bfe7ad70a09d97acc10e2d4928348c0c257ca36c', 'gitBlob': '86f718d2423df6632a73abbb155d50637daf40e8'}, 'app/src/main/java/com/android/purebilibili/core/player/PlaybackMediaCache.kt': {'sha256LF': '9c05458cad086fd2583448883c806af2d610523b9a17f5052346bab2c0fc967a', 'gitBlob': '1d9ef4e20c7eba111c23ebaf2d788f355eb9f966'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlay.kt': {'sha256LF': '84d34c70fd9abc4156f6aa7b7f69f66f1a8689d31a3b8954b26a506292a4cf11', 'gitBlob': '43561ecf90cdf22dfbc08e1545e0f4d9a4093a64'}, 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPlatformPolicy.kt': {'sha256LF': 'de8e0f554e1674b3c64c05b57ac2cf1846d2bf95e94d4c0eff5fa8f11314a636', 'gitBlob': 'bb541ee0d863f14951a2eca810bd948e74d1bbb2'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/PlaybackCdnDiagnostics.kt': {'sha256LF': 'a432a19b4f34e888f4cd512e5925304fceee652586aa7bc8993142d2c98ed0d2', 'gitBlob': 'ad1d5eb868c1cfe9bf5ded68d9744b024fd98b07'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayContracts.kt': {'sha256LF': 'ef20a04275904d50dc49b39fb462b266402a76eb13969e4249fcca935fa18a27', 'gitBlob': '5b9fb619c7050533d6e9385a23c271df22888233'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentThreadNavigationBlur.kt': {'sha256LF': '728a23616566e63a0b93cce99a4b4b1002b3d11449c184a07c6a7bf606019d20', 'gitBlob': '7708650014180ca3c0c31c73626b01a509a61469'}}
SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
ACTUAL={'com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayScaleMode.class'}
EXISTING_DIRECT={'feature/video/subtitle/BiliSubtitlePolicy.kt','feature/anime4k/Anime4KConfig.kt'}

def read(rel):
 path=_desktop_canonical_relative(REPO,BASE+rel)
 if path not in SOURCES:
  selected=_desktop_canonical_source(REPO,path)
  assert not selected.is_symlink() and selected.resolve().is_relative_to(REPO.resolve()),path
  raw=wide(selected).read_bytes().replace(b'\r\n',b'\n');pin=SOURCE_PINS[path]
  assert sha(raw)==pin['sha256LF'],path+' differs from pinned stable source'
  blob=subprocess.check_output(['git','-c','core.longpaths=true','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip()
  actual=subprocess.check_output(['git','-c','core.longpaths=true','hash-object','--path='+_desktop_canonical_relative(REPO,path),str(_desktop_canonical_source(REPO,path))],cwd=REPO,text=True).strip()
  assert blob==actual==pin['gitBlob'],path+' Git identity differs'
  SOURCES[path]=dict(text=raw.decode(),**pin)
 return SOURCES[path]['text']
def emit(rel,t,origin,mode):
 direct=mode=='direct-complete-original'
 if STANDALONE or not direct:write(OUTPUT/'com/android/purebilibili'/rel,t)
 OUTPUTS.append(dict(path='com/android/purebilibili/'+rel,origin=BASE+origin,sha256LF=sha(t),mode=mode,lines=len(t.splitlines()),generated=STANDALONE or not direct))

def adapt(t,before,after,label):
 assert t.count(before)==1,(label,t.count(before));ADAPT.append(dict(label=label,before=before,after=after));return t.replace(before,after,1)
def windows_nvidia_enhancement_leaf(source,record=False):
 """Replace only the legacy enhancement widget; all other original controls remain."""
 start_marker='            item {\n                Column(modifier = Modifier.fillMaxWidth()) {\n                    VideoSettingsSwitchRow(\n                        icon = qualityIcon,\n                        title = "画质增强",'
 end_marker='\n\n            // [New] 资源下载'
 if source.count(start_marker)!=1:raise ValueError('Original video enhancement widget boundary changed')
 start=source.index(start_marker);end=source.index(end_marker,start)
 original=source[start:end]
 for name in ('VideoEnhancementAlgorithmOptions(', 'Anime4KPresetOptions(', 'FsrSharpnessOptions('):
  if original.count(name)!=1:raise ValueError('Original video enhancement choice changed: '+name)
 replacement='            item {\n                com.bilipai.desktop.ui.DesktopWindowsVideoEnhancementSettingsContent(showProcessingQuality = false)\n                SettingsDivider()\n            }'
 if record:ADAPT.append(dict(label='windows-nvidia-only-enhancement-widget',before=original,after=replacement))
 return source[:start]+replacement+source[end:]

def windows_native_audio_settings_ui(source,record=False):
 s=source
 for before,after,label in [('    // 关闭面板\n    onDismiss: () -> Unit\n', '    // 关闭面板\n    onDismiss: () -> Unit,\n    // Optional Windows view; original audio preferences keep their owner.\n    nativeAudioTrackContent: (@Composable () -> Unit)? = null,\n', 'same original settings API optional native track slot'), ('            item {\n                com.bilipai.desktop.ui.DesktopWindowsVideoEnhancementSettingsContent(showProcessingQuality = false)\n', '            if (nativeAudioTrackContent != null) {\n                item { nativeAudioTrackContent() }\n            }\n\n            item {\n                com.bilipai.desktop.ui.DesktopWindowsVideoEnhancementSettingsContent(showProcessingQuality = false)\n', 'same original drawer native track leaf')]:
  assert s.count(before)==1,label
  index=s.index(before)
  if record:ADAPT.append(dict(label=label,before=before,after=after))
  s=s[:index]+after+s[index+len(before):]
 return s

def windows_native_audio_overlay_ui(source,record=False):
 s=source
 for before,after,label in [('            VideoSettingsPanel(\n                sleepTimerMinutes = sleepTimerMinutes,', '            VideoSettingsPanel(\n                nativeAudioTrackContent = {\n                    com.bilipai.desktop.ui.DesktopWindowsNativeAudioTrackMenu(\n                        nativePlayer = player.nativePlayer,\n                        menuEnabled = player.isOwned() && com.bilipai.desktop.ui.LocalDesktopDetailForeground.current,\n                    )\n                },\n                sleepTimerMinutes = sleepTimerMinutes,', 'original overlay binds same owned player to settings leaf')]:
  assert s.count(before)==1,label
  index=s.index(before)
  if record:ADAPT.append(dict(label=label,before=before,after=after))
  s=s[:index]+after+s[index+len(before):]
 return s

def full_direct(rel,className=None):
 t=read(rel)
 if rel in EXISTING_DIRECT:
  REFERENCES.append(dict(source=BASE+rel,existingClass=className,mode='sole-existing-reference'));return
 emit(rel,t,rel,'direct-complete-original')
def function_range(t,name):
    matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|suspend|inline)\s+)*fun\s+(?:[\w.]+\.)?'+re.escape(name)+r'\s*\(',t));assert len(matches)==1,(name,len(matches))
    m=matches[0];tokens=parser.kotlin_tokens(t);i=next(i for i,(_,a,_)in enumerate(tokens) if a>=m.start())
    while tokens[i][0]!='(':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
    while tokens[i][0]!='{':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
    return m.start(),tokens[i][2]

def member_closure(source,seeds):
 tokens=parser.kotlin_tokens(source);depth=parens=brackets=0;starts=[]
 for i,(word,a,b)in enumerate(tokens):
  if depth==parens==brackets==0 and word in ['fun','val','var','class','object','interface']:
   name=tokens[i+1][0];line=source.rfind('\n',0,a)+1
   while line>0:
    prior=source.rfind('\n',0,line-1)+1
    if source[prior:line].strip().startswith('@'):line=prior
    else:break
   starts.append((name,line))
  depth+=(word=='{')-(word=='}');parens+=(word=='(')-(word==')');brackets+=(word=='[')-(word==']')
 chunks={name:source[a:starts[i+1][1]if i+1<len(starts)else len(source)]for i,(name,a)in enumerate(starts)}
 duplicates={name for name,_ in starts if sum(n==name for n,_ in starts)>1}
 assert not set(seeds)&duplicates,(set(seeds)&duplicates)
 chosen=set(seeds);assert chosen<=chunks.keys(),chosen-chunks.keys()
 while True:
  more={word for name in chosen for word,_,_ in parser.kotlin_tokens(chunks[name])if word in chunks and word not in duplicates}
  if more<=chosen:break
  chosen|=more
 return '\n'.join(chunks[name]for name,_ in starts if name in chosen),sorted(chosen)
def main():
 render_v025_comment_thread_blur()
 rel='feature/video/ui/overlay/BottomControlBar.kt';t=read(rel)
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t=t.replace('import androidx.compose.ui.semantics.testTagsAsResourceId\n','')
 t=t.replace('.semantics { testTagsAsResourceId = true }','.semantics { }')
 t=re.sub(r'(?m)^\s*decorFitsSystemWindows = false,?\s*\n','',t)
 t=t.replace('import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset','import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset')
 emit(rel,t,rel,'complete-original-bottom-controls-and-menus-platform-adapt')
 rel='feature/video/ui/overlay/TopControlBar.kt';t=read(rel)
 for name in ['android.content.Context','android.content.Intent','android.content.IntentFilter','android.os.BatteryManager','androidx.compose.ui.platform.LocalContext']:
  t=t.replace('import '+name+'\n','')
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t+='\n'
 t=t.replace('val context = LocalContext.current','val context = LocalDesktopOriginalPlayerControlsPlatform.current')
 t=t.replace('private fun resolveBatteryLevelPercent(context: Context)','private fun resolveBatteryLevelPercent(context: DesktopOriginalPlayerControlsPlatform)')
 a,b=function_range(t,'resolveBatteryLevelPercent')
 old=t[a:b];new='''private fun resolveBatteryLevelPercent(context: DesktopOriginalPlayerControlsPlatform): Int? {
    return context.readBatteryPercent()
}'''
 ADAPT.append(dict(label='Android sticky battery broadcast maps required actual Windows power query',before=old,after=new));t=t[:a]+new+t[b:]
 t=t.replace('import com.android.purebilibili.core.ui.components.AppText\n','import com.android.purebilibili.core.ui.components.AppText\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerControlsPlatform\nimport com.bilipai.desktop.ui.LocalDesktopOriginalPlayerControlsPlatform\n',1)
 emit(rel,t,rel,'complete-original-top-controls-status-platform-adapt')
 for rel,model in [('feature/video/ui/overlay/BottomControlBarLayoutPolicy.kt','BottomControlBarLayoutPolicy'),('feature/video/ui/overlay/TopControlBarLayoutPolicy.kt','TopControlBarLayoutPolicy'),('feature/video/ui/overlay/SubtitleControlModel.kt','SubtitleControlUiState'),('feature/video/subtitle/BiliSubtitlePolicy.kt','SubtitleTrackOption'),('feature/video/progress/PbpProgressPolicy.kt','PbpRidgeSample'),('feature/anime4k/Anime4KConfig.kt','Anime4KConfig'),('feature/video/ui/components/NativeDanmakuToggleButton.kt','NativeDanmakuToggleButtonKt')]:
  full_direct(rel,model)
 rel='core/store/SettingsManager.kt';t=read(rel)
 if 'com/android/purebilibili/core/store/PlayerProgressPlacement.class'not in ACTUAL:
  emit('core/store/DesktopOriginalPlayerProgressPlacement.kt','package com.android.purebilibili.core.store\n'+selector.declarations(parser,t,['PlayerProgressPlacement'])+'\n',rel,'complete-original-enum')
 emit('core/store/DesktopOriginalPlayerProgressBehaviorModels.kt','package com.android.purebilibili.core.store\n'+selector.declarations(parser,t,['BottomProgressBehavior','PlaybackCompletionBehavior'])+'\n',rel,'complete-original-progress-completion-enums')
 rel='feature/video/ui/components/VideoAspectRatio.kt';t=read(rel)
 t=t.replace('import androidx.media3.ui.AspectRatioFrameLayout','import com.bilipai.desktop.ui.DesktopOriginalMediaResizeModes as AspectRatioFrameLayout')
 t=t.replace('@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n','')
 t=t.replace('androidx.media3.ui.PlayerView','com.bilipai.desktop.ui.DesktopOriginalPlayerViewportPort')
 t=adapt(t,'    playerView.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)\n        ?.requestLayout()','    playerView.requestContentLayout()','PlayerView internal content remeasure uses same Windows native surface viewport port')
 t=t.replace('playerView.postOnAnimation {','playerView.postOnFrame {')
 emit(rel,t,rel,'complete-original-aspect-and-menu-platform-integer-adapt')
 rel='feature/video/ui/components/SeekPreviewBubble.kt';t=read(rel)
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration').replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext')
 t=t.replace('import androidx.compose.ui.graphics.asImageBitmap','import androidx.compose.ui.graphics.toComposeImageBitmap').replace('bitmap.asImageBitmap()','bitmap.toComposeImageBitmap()')
 t=t.replace('import androidx.compose.runtime.remember\n','import androidx.compose.runtime.remember\nimport androidx.compose.runtime.DisposableEffect\n')
 t=adapt(t,'''        is AsyncImagePainter.State.Success -> {
            Canvas(modifier = modifier) {
                val bitmap = (painterState.result.image as? coil3.BitmapImage)?.bitmap ?: return@Canvas''','''        is AsyncImagePainter.State.Success -> {
            val bitmap = (painterState.result.image as? coil3.BitmapImage)?.bitmap
            val spriteImage = remember(bitmap) { bitmap?.let(org.jetbrains.skia.Image::makeFromBitmap) }
            DisposableEffect(spriteImage) { onDispose { spriteImage?.close() } }
            Canvas(modifier = modifier) {
                val bitmap = bitmap ?: return@Canvas
                val spriteImage = spriteImage ?: return@Canvas''','Coil Skia bitmap converted to an owned Image; never close Coil shared bitmap')
 t=t.replace('image = bitmap.toComposeImageBitmap(),','image = spriteImage.toComposeImageBitmap(),')
 emit(rel,t,rel,'complete-original-seek-preview-platform-context-adapt')
 rel='feature/anime4k/gl/Anime4KRenderConfig.kt'
 # The native GL source stays reference-only; only its original pure display enum is needed by the menu.
 source='feature/anime4k/gl/Anime4KDisplayPolicy.kt';t=read(source)
 if 'com/android/purebilibili/feature/anime4k/gl/Anime4KDisplayScaleMode.class'not in ACTUAL:
  emit('feature/anime4k/gl/DesktopOriginalAnime4KDisplayScaleMode.kt','package com.android.purebilibili.feature.anime4k.gl\n'+selector.declarations(parser,t,['Anime4KDisplayScaleMode'])+'\n',source,'complete-original-pure-display-enum')
 for rel in ['feature/video/ui/components/PlayerMiuixListPopup.kt','feature/video/ui/components/AudioQualitySelectionMenu.kt','feature/video/ui/components/DanmakuComposerPolicy.kt','feature/video/ui/overlay/PlaybackButtonComponents.kt','feature/video/playback/policy/PlaybackTransitionPolicy.kt']:
  full_direct(rel)
 rel='feature/video/ui/overlay/PlayerOverlayModels.kt';t=read(rel)
 t=t.replace('android.graphics.Color.parseColor','com.bilipai.desktop.plugins.DesktopPluginColor.parseColor')
 t=t.replace('import com.android.purebilibili.core.store.player.PlayerSettingsStore','import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore')
 t=t.replace('androidx.media3.common.Player.STATE_','com.bilipai.desktop.ui.DesktopOriginalPlaybackStates.STATE_')
 emit(rel,t,rel,'complete-original-overlay-display-models-platform-color')
 rel='core/store/player/PlayerSettingsStore.kt';t=read(rel)
 t=t[t.index('{',t.index('object PlayerSettingsStore'))+1:t.rfind('}')]
 # Preserve the complete canonical object, including original speed options,
 # volume rounding, mirror caches and insight migration. Long-press read remains
 # owned by the frozen Offline producer and is forwarded without a second algorithm.
 body=t
 a,b=function_range(body,'getLongPressSpeed')
 body=body[:a]+'''    fun getLongPressSpeed(context: Context): Flow<Float> =
        DesktopOriginalLongPressSpeedSettings.getLongPressSpeed(context.pluginContext)
'''+body[b:]
 body=_playback_player_settings_body(body)
 keys=selector.declarations(parser,read(rel),['defaultAudioQualityPreferenceKey','longPressSpeedPreferenceKey','playbackSpeedOptionsPreferenceKey'])
 # The last selected top-level val would include the following whole object;
 # use its exact single line instead of the generic last-declaration selector.
 keys='\n'.join(line.replace('internal val','private val')for line in read(rel).splitlines()if re.match(r'internal val (defaultAudioQualityPreferenceKey|longPressSpeedPreferenceKey|playbackSpeedOptionsPreferenceKey) =',line))
 adapted='''package com.android.purebilibili.core.store.player
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as Preferences
import com.bilipai.desktop.ui.DesktopOriginalPlayerPreferenceValues as MutablePreferences
import com.bilipai.desktop.ui.playerStringPreferencesKey as stringPreferencesKey
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerFloatPreferencesKey as floatPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.android.purebilibili.core.store.resolvePreferredPlaybackSpeed as resolvePreferredPlaybackSpeedPolicy
import com.android.purebilibili.core.store.DEFAULT_LONG_PRESS_SPEED
import com.android.purebilibili.core.store.nearestPlaybackSpeed
import com.android.purebilibili.core.store.normalizeLongPressSpeed
import com.android.purebilibili.core.store.normalizePlaybackSpeedOptions
import com.android.purebilibili.core.store.resolvePlaybackSpeedOptions
import com.android.purebilibili.core.store.normalizePlaybackSpeed as normalizePlaybackSpeedPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
'''+keys+'''\nobject DesktopOriginalVideoPlayerSettings {
'''+body+'\n}\n'
 emit('core/store/player/DesktopOriginalVideoPlayerSettings.kt',adapted,rel,'complete-original-player-settings-object-canonical-longpress-reference-owned-global-context')
 # Whole menu/sheet declarations; the legacy enhancement widget is a Windows NVIDIA leaf.
 for rel in ['feature/video/ui/components/SpeedSelectionPanel.kt','feature/video/ui/components/ChapterListPanel.kt','feature/video/ui/components/LandscapeSidePanel.kt','feature/video/ui/components/PagesSelectorLayoutPolicy.kt','feature/video/ui/gesture/TwoFingerSpeedGesturePolicy.kt','feature/video/ui/components/VideoSettingsPanelActionPolicy.kt','feature/video/ui/components/ModalChildScroll.kt']:
  full_direct(rel)
 for rel in ['feature/video/ui/components/QualityMenu.kt','feature/video/ui/components/VideoSettingsPanel.kt','feature/video/ui/components/PagesSelector.kt']:
  t=read(rel)
  t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  t=t.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext')
  t=t.replace('androidx.compose.ui.platform.LocalContext.current','com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext.current')
  t=t.replace('androidx.compose.ui.platform.LocalConfiguration.current','com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics.current')
  t=t.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings as SettingsManager')
  t=t.replace('com.android.purebilibili.core.store.SettingsManager','com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings')
  t=t.replace('import android.content.res.Configuration\n','').replace('Configuration.ORIENTATION_LANDSCAPE','2')
  t=t.replace('configuration.orientation','(if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1)')
  if rel=='feature/video/ui/components/VideoSettingsPanel.kt':
   t=windows_nvidia_enhancement_leaf(t,record=True)
   t=windows_native_audio_settings_ui(t,record=True)
  emit(rel,t,rel,'complete-original-menu-sheet-global-context-adapt')
 rel='core/ui/components/PlaybackSpeedPreferenceControl.kt';t=read(rel)
 # formatPlaybackSpeed is already sole-owned by existing actual playback settings.
 if 'com/android/purebilibili/core/ui/components/PlaybackSpeedPreferenceControlKt.class' in ACTUAL:
  REFERENCES.append(dict(source=BASE+rel,existingClass='PlaybackSpeedPreferenceControlKt',mode='sole-existing-reference'))
 else:
  # Existing original format function lives in another Kt wrapper. It must not be emitted twice.
  # Complete original format function is not defined in actual47.
  emit(rel,t,rel,'complete-original-speed-preference-controls-reference-existing-format')
 rel='core/store/SettingsManager.kt';t=read(rel);obj=t[t.index('{',t.index('object SettingsManager'))+1:t.rfind('}')]
 names=['getDoubleTapSeekEnabled','setDoubleTapSeekEnabled','getSeekForwardSeconds','setSeekForwardSeconds','getSeekBackwardSeconds','setSeekBackwardSeconds','getLongPressSpeed','setLongPressSpeed','getLongPressSpeedLockEnabled','setLongPressSpeedLockEnabled','setLongPressSpeedLockHintShown','getTwoFingerVerticalSpeedEnabled','setTwoFingerVerticalSpeedEnabled','getTwoFingerHorizontalSpeedEnabled','setTwoFingerHorizontalSpeedEnabled','getPlaybackSpeedOptions','getDefaultPlaybackSpeed','setDefaultPlaybackSpeed','getRememberLastPlaybackSpeed','setRememberLastPlaybackSpeed','getProgressPeakDanmakuEnabled','setProgressPeakDanmakuEnabled']
 names+=['getShowFullscreenLockButton','getShowFullscreenScreenshotButton','getShowFullscreenBatteryLevel','getShowFullscreenTime','getShowFullscreenActionItems','getShowOnlineCount','getBottomProgressBehavior','getPlayerControlVisibilitySettings','getPlayerProgressPlacement','getPlaybackCompletionBehavior','getPlaybackCompletionBehaviorSync','setPlaybackCompletionBehavior','getHideVideoPageStatusBar','getHideVideoPageStatusBarSync','getCardAnimationEnabled','setLastPlaybackSpeed']
 body,closure=member_closure(obj,names);body=_playback_control_settings_body(body);keys=''
 imports='''package com.android.purebilibili.core.store
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import com.bilipai.desktop.ui.playerBooleanPreferencesKey as booleanPreferencesKey
import com.bilipai.desktop.ui.playerIntPreferencesKey as intPreferencesKey
import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore
import com.android.purebilibili.feature.video.ui.gesture.TwoFingerSpeedToggleState
import com.android.purebilibili.feature.video.ui.gesture.applyHorizontalTwoFingerSpeedToggle
import com.android.purebilibili.feature.video.ui.gesture.applyVerticalTwoFingerSpeedToggle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.distinctUntilChanged
object DesktopOriginalVideoControlSettings {
'''
 emit('core/store/DesktopOriginalVideoControlSettings.kt',imports+keys+'\n'+body+'\n}\n',rel,'original-complete-control-settings-getters-setters-keys-same-global')
 for rel in ['feature/video/ui/overlay/VideoPlayerOverlayVisualPolicy.kt','feature/video/ui/overlay/LandscapeEndDrawerLayoutPolicy.kt','feature/video/ui/overlay/PlaybackOrderDisplayPolicy.kt','feature/video/ui/overlay/PlaybackOrderSelectionSheetPolicy.kt','feature/video/ui/components/LandscapeDanmakuComposer.kt','feature/video/ui/overlay/PortraitTopBarLayoutPolicy.kt','feature/video/ui/overlay/ImmersiveStatusBarBackdrop.kt','feature/video/ui/overlay/PersistentProgressBar.kt','core/store/PlaybackCompletionBehaviorSyncPolicy.kt']:
  full_direct(rel)
 for rel in ['feature/video/ui/overlay/PlaybackOrderSelectionSheet.kt','feature/video/ui/components/DanmakuSendDialog.kt']:
  t=read(rel).replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  t=t.replace('import android.content.res.Configuration\n','').replace('Configuration.ORIENTATION_LANDSCAPE','2').replace('configuration.orientation','(if (configuration.screenWidthDp > configuration.screenHeightDp) 2 else 1)')
  t=re.sub(r'(?m)^\s*decorFitsSystemWindows = false,?\s*\n','',t)
  if rel=='feature/video/ui/components/DanmakuSendDialog.kt':
   t=adapt(t, 'import androidx.compose.ui.window.Dialog\n',
       'import com.bilipai.desktop.ui.DesktopWindowsVideoInteractionDialog as Dialog\n',
       'Whole original composer uses opt-in owned Windows native container')
  emit(rel,t,rel,'complete-original-sheet-input-color-ui-window-config-adapt')
 rel='core/ui/performance/PanelFrameRateOverridePolicy.kt';t=read(rel)
 imports='''package com.android.purebilibili.core.ui.performance
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext
'''
 body=selector.declarations(parser,t,['resolvePanelFrameRateOverrideLabel','PANEL_FRAME_RATE_SAMPLE_INTERVAL_MS','rememberPanelFrameRateLabel'])
 body=body.replace('LocalContext.current.findActivity()','LocalDesktopOriginalPlayerSettingsContext.current.videoOverlay')
 body=body.replace('resolvePanelDisplayRefreshRate(activity)','activity.readPanelRefreshRate() ?: 0f')
 emit('core/ui/performance/DesktopOriginalPlayerPanelFrameRate.kt',imports+body,rel,'original-label-and-500ms-composition-sampler-required-actual-host-monitor-read')
 rel='feature/video/ui/components/PlaybackCdnDiagnostics.kt';t=read(rel)
 t=adapt(t, 'import com.android.purebilibili.core.plugin.PluginManager\n', '', 'Same retained Root CDN panel/Store owner')
 t=adapt(t, '    val plugin = PluginManager.getEnabledPlugins(PlaybackCdnPlugin::class).filterIsInstance<CdnRegionPlugin>().firstOrNull()', '    val owner = com.bilipai.desktop.ui.rememberDesktopCdnTransferPanelBinding()\n    val plugin = owner.plugin', 'Same retained Root CDN panel/Store owner')
 t=adapt(t, '                CdnTransferRuntime.restoreRoutes()\n', '                owner.mutate { CdnTransferRuntime.restoreRoutes() }\n', 'Same retained Root CDN panel/Store owner')
 t=adapt(t, '                best?.let { onSwitchTo(it.index) }', '                owner.assertCurrent()\n                best?.let { onSwitchTo(it.index) }', 'Same retained Root CDN panel/Store owner')
 t=adapt(t, 'plugin?.canUseParallelDownload() != true', '!owner.canUseParallelDownload()', 'Same retained Root CDN panel/Store owner')
 t=adapt(t, 'plugin?.setParallelDownloadEnabled(enabled)', 'owner.setParallelDownloadEnabled(enabled)', 'Same retained Root CDN panel/Store owner')
 t=adapt(t, '            onAvoid = CdnTransferRuntime::avoid,\n            onRestore = CdnTransferRuntime::restoreRoutes,', '            onAvoid = { host -> owner.mutate { CdnTransferRuntime.avoid(host) } },\n            onRestore = { owner.mutate { CdnTransferRuntime.restoreRoutes() } },', 'Same retained Root CDN panel/Store owner')
 emit(rel,t,rel,'complete-original-cdn-player-diagnostics-same-root-platform-adapt')
 # Full diagnostic action model/tracker: one original weak-key tracker over the actual MPV facade.
 rel='feature/video/playback/session/PlaybackUserActionTracker.kt';t=read(rel)
 t=t.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player')
 t=t.replace('import com.android.purebilibili.core.store.PlayerSettingsCache\n','')
 t=t.replace('PlayerSettingsCache.isPlayerDiagnosticLoggingEnabled()','player.diagnosticLoggingEnabled')
 emit(rel,t,rel,'complete-original-pending-user-action-model-tracker-real-mpv-facade')
 rel='feature/video/usecase/VideoPlaybackUseCase.kt';t=read(rel)
 names=['shouldPreparePlayerBeforeExplicitPlay','playPlayerFromUserAction','pausePlayerFromUserAction','applyPlaybackButtonUserAction','playPlayerForUserIntent','shouldResumePlaybackAfterUserSeek','seekPlayerFromUserAction','togglePlayerPlaybackFromUserAction','shouldPauseForPlaybackToggle']
 pieces=selector.declarations(parser,t,names)
 pieces=adapt(pieces,'PlaybackMediaCache.logSeek(','player.logSeek(','Original seek diagnostic keeps all four position fields; Android SimpleCache byte counters require an explicit Windows capability boundary, no parallel cache')
 read('core/player/PlaybackMediaCache.kt')
 emit('feature/video/usecase/DesktopOriginalOverlayPlaybackActions.kt','''package com.android.purebilibili.feature.video.usecase
import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player
import com.android.purebilibili.core.util.Logger
import com.android.purebilibili.feature.video.playback.session.PlaybackUserActionTracker
import com.android.purebilibili.feature.video.ui.overlay.PlaybackUserActionType
'''+pieces,rel,'complete-original-explicit-user-play-pause-seek-actions-mpv-facade')
 rel='feature/video/ui/overlay/VideoPlayerOverlayContracts.kt';t=read(rel)
 t=adapt(t,'import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player','Original overlay DTO retains same required native owner')
 t=adapt(t,'import com.android.purebilibili.core.store.player.PlayerSettingsStore','import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore','Original DTO uses same global player settings authority')
 t=adapt(t,'(String, android.os.Bundle?) -> Unit = { _, _ -> }','(String, Long) -> Unit = { _, _ -> }','Original overlay action carries real target CID through typed Root route')
 emit(rel,t,rel,'complete-original-overlay-state-and-actions-required-native-owner-alias')
 rel='feature/video/ui/overlay/VideoPlayerOverlay.kt';t=read(rel)
 for name in ['android.content.ClipData','android.content.Context','android.os.Build','com.android.purebilibili.data.repository.VideoRepository','com.android.purebilibili.feature.cast.LocalProxyServer','com.android.purebilibili.core.util.NetworkUtils']:
  t=t.replace('import '+name+'\n','')
 t=t.replace('import androidx.media3.common.Player','import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player')
 t=t.replace('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.ui.LocalDesktopOriginalPlayerSettingsContext as LocalContext\nimport com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context')
 t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
 t=t.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoControlSettings as SettingsManager')
 t=t.replace('import com.android.purebilibili.core.store.player.PlayerSettingsStore','import com.android.purebilibili.core.store.player.DesktopOriginalVideoPlayerSettings as PlayerSettingsStore')
 t=t.replace('import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset','import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset')
 t=t.replace('import com.android.purebilibili.feature.cast.DeviceListDialog','import com.bilipai.desktop.ui.DesktopOriginalPlayerDeviceListDialog as DeviceListDialog')
 t=t.replace('import com.android.purebilibili.feature.video.share.VideoShareSheetHost','import com.bilipai.desktop.ui.DesktopOriginalPlayerVideoShareSheetHost as VideoShareSheetHost')
 t=t.replace('player.playbackParameters.speed','player.playbackSpeed')
 t=t.replace('onDrawerVideoClick(target.nextBvid, null)','onDrawerVideoClick(target.nextBvid, 0L)')
 t=t.replace('import com.android.purebilibili.core.ui.blur.shouldAllowRuntimeShaderBackedHazeEffect\n','')
 t=t.replace('shouldAllowRuntimeShaderBackedHazeEffect(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()')
 t=t.replace('''Logger.exportPlayerDiagnostic(
                                    context = context,
                                    content = exportDiagnosticReport(signal)
                                )''','context.videoOverlay.exportPlayerDiagnostic(exportDiagnosticReport(signal))')
 t=t.replace('NetworkUtils.getNetworkTypeLabel(context)','context.videoOverlay.networkTypeLabel()')
 t=t.replace('android.net.Uri.parse(currentVideoUrl).host.orEmpty()','java.net.URI(currentVideoUrl).host.orEmpty()')
 t=t.replace('VideoRepository.getTvCastPlayData','context.videoOverlay.getTvCastPlayData')
 t=t.replace('LocalProxyServer.getProxyUrl(context, ','context.videoOverlay.castProxyUrl(')
 t=t.replace('LocalProxyServer.registerDashManifest(context, ','context.videoOverlay.registerCastDashManifest(')
 t=t.replace('LocalProxyServer.DASH_CONTENT_TYPE','context.videoOverlay.dashContentType').replace('LocalProxyServer.ensureStarted()','context.videoOverlay.ensureCastProxyStarted()')
 t=t.replace('plugin.cast(context, route,','plugin.cast(context.pluginContext, route,')
 resolverA,resolverB=function_range(t,'resolveCastPlayUrl')
 resolver=t[resolverA:resolverB]
 resolver=adapt(resolver,'withContext(Dispatchers.IO) {','''withContext(Dispatchers.IO) {
    context.videoOverlay.withCastSource(currentAid, cid) {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    context.requireCurrent()''','Reject canceled/retired cast resolution and keep one captured native source before existing transport')
 resolver=resolver.replace('return@withContext CastMediaResolution', 'return@withCastSource CastMediaResolution')
 assert resolver.endswith('}');resolver=resolver[:-1]+'    }\n}'
 assert resolver.count('}.getOrNull()')==2
 resolver=resolver.replace('}.getOrNull()','''}.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()''')
 resolver=adapt(resolver,'    if (tvData != null) {','''    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    context.requireCurrent()
    if (tvData != null) {''','Reject canceled/retired result before cast proxy or direct URL publication')
 ADAPT.append(dict(label='Cancellation never converts to cast fallback',before=t[resolverA:resolverB],after=resolver))
 t=t[:resolverA]+resolver+t[resolverB:]
 t=t.replace('import kotlinx.coroutines.Dispatchers\n','import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.ensureActive\n')
 t=t.replace('android.widget.Toast','com.bilipai.desktop.ui.DesktopOriginalPlayerFeedback')
 t=t.replace('(String, android.os.Bundle?) -> Unit','(String, Long) -> Unit').replace('onVideoClick(video.bvid, null)','onVideoClick(video.bvid, video.cid)')
 t=adapt(t,'''com.android.purebilibili.feature.video.screen.buildVideoNavigationOptions(
                                                        targetCid = episode.cid
                                                    )''','episode.cid','Original drawer target CID transferred through typed existing Root navigation port')
 t=adapt(t,'''player.playerError?.let { error ->
                listOf(error.errorCodeName, error.message.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
            }''','player.playerErrorMessage','MPV actual error text replaces Media3-specific error enum')
 t=adapt(t,'''player.playerError?.let { error ->
                    listOf(error.errorCodeName, error.message.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString(": ")
                }''','player.playerErrorMessage','MPV actual live error text in unchanged diagnostic report')
 t=t.replace('player.playerError','player.playerErrorMessage')
 # Undo the generic receiver replacement on the two already-adapted scalar reads.
 t=t.replace('player.playerErrorMessageMessage','player.playerErrorMessage')
 before='''    DisposableEffect(player) {
        currentSpeed = player.playbackSpeed
        val speedListener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                currentSpeed = playbackParameters.speed
            }
        }
        player.addListener(speedListener)
        onDispose { player.removeListener(speedListener) }
    }'''
 t=adapt(t,before,'''    LaunchedEffect(player) {
        player.state.collect { if (player.isOwned()) currentSpeed = player.playbackSpeed }
    }''','Original speed listener consumes same actual MPV StateFlow; collection canceled with original composition')
 a=t.index('                        val clipboard = context.getSystemService');b=t.index('                        com.bilipai.desktop.ui.DesktopOriginalPlayerFeedback.makeText',a)
 old=t[a:b];new='''                        context.videoOverlay.copyText("BiliPai Player Diagnostics", exportDiagnosticReport(null))
''';ADAPT.append(dict(label='Same Root actual clipboard ownership',before=old,after=new));t=t[:a]+new+t[b:]
 copyA=t.index('                onCopyReport =')
 copyB=t.index('                onDismiss =',copyA)
 copy=t[copyA:copyB]
 copy=adapt(copy,'context.videoOverlay.copyText("BiliPai Player Diagnostics", exportDiagnosticReport(null))','if (context.videoOverlay.copyText("BiliPai Player Diagnostics", exportDiagnosticReport(null))) {','Real Windows clipboard acknowledgment gates original success feedback')
 copy=adapt(copy,'                        ).show()\n                    }','                        ).show()\n                        }\n                    }','Close real clipboard success branch without changing original feedback body')
 t=t[:copyA]+copy+t[copyB:]
 t=windows_native_audio_overlay_ui(t,record=True)
 emit(rel,t,rel,'complete-original-overlay-all-menus-cast-reload-diagnostic-drawer-real-mpv-platform-adapt')
 rel='feature/video/screen/VideoDetailPlatformPolicy.kt';t=read(rel)
 emit('feature/video/screen/DesktopOriginalPlayerSystemBarInsetPolicy.kt','package com.android.purebilibili.feature.video.screen\n'+selector.declarations(parser,t,['shouldApplyStatusBarPaddingToVideoPlayerChrome','VideoDetailSystemBarsVisibilityPolicy','resolveVideoDetailSystemBarsVisibilityPolicy'])+'\n',rel,'complete-original-inset-policy')
def generate(repo,output,standalone=False):
 global REPO,OUTPUT,STANDALONE,parser,selector,SOURCES,OUTPUTS,ADAPT,REFERENCES
 REPO=Path(repo).resolve();OUTPUT=Path(output).resolve();STANDALONE=standalone
 SOURCES={};OUTPUTS=[];ADAPT=[];REFERENCES=[]
 manifest=json.loads(wide(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
 assert manifest['upstreamCommit']==COMMIT
 registry={r['path']:r['sha256']for r in manifest['sources']}
 for path,pin in SOURCE_PINS.items():
  if path in registry:assert registry[path]==pin['sha256LF'],path+' existing registry identity differs'
 parser=module('controls_tokens',REPO/'desktop/tools/sync-upstream.py')
 selector=module('controls_decls',REPO/'desktop/tools/extract-appearance-platform.py')
 main()
if __name__=='__main__':generate(Path(sys.argv[1]),Path(sys.argv[2]),'--standalone' in sys.argv[3:])
