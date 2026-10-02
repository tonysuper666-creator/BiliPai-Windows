from pathlib import Path
import hashlib
import json
import subprocess

lane = Path(__file__).resolve().parent
main = lane.parents[2]
candidate = main.parent / 'BiliPai-v023'
path = 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
raw = (candidate / path).read_bytes()
text = raw.replace(b'\r\n', b'\n').decode('utf-8')
before = '''                        BiliPaiNavKey.PlaybackSettings -> settingsNavigator.openDetail(SettingsSearchTarget.PLAYBACK,null)
                        BiliPaiNavKey.HomeSettings -> settingsNavigator.openCategory(SettingsRootCategory.HOME_RECOMMENDATION)
                        BiliPaiNavKey.PermissionSettings -> settingsNavigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
                        BiliPaiNavKey.WebDavBackup -> settingsNavigator.openDetail(SettingsSearchTarget.DATA_BACKUP,null)
'''
after = '''                        BiliPaiNavKey.PlaybackSettings -> settingsNavigator.openDetail(SettingsSearchTarget.PLAYBACK,null)
                        BiliPaiNavKey.AnimationSettings -> settingsNavigator.openDetail(SettingsSearchTarget.ANIMATION,null)
                        BiliPaiNavKey.BottomBarSettings -> settingsNavigator.openDetail(SettingsSearchTarget.BOTTOM_BAR,null)
                        BiliPaiNavKey.SettingsShare -> settingsNavigator.openDetail(SettingsSearchTarget.SETTINGS_SHARE,null)
                        BiliPaiNavKey.MessageNotificationSettings -> settingsNavigator.openDetail(SettingsSearchTarget.MESSAGE_NOTIFICATION,null)
                        BiliPaiNavKey.HomeSettings -> settingsNavigator.openCategory(SettingsRootCategory.HOME_RECOMMENDATION)
                        BiliPaiNavKey.PermissionSettings -> settingsNavigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
                        BiliPaiNavKey.WebDavBackup -> settingsNavigator.openDetail(SettingsSearchTarget.WEBDAV_BACKUP,null)
'''
assert text.count(before) == 1 and after not in text
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=candidate, text=True).strip()
assert head == 'a5ba7265ffcc0157cd9cdab768e0a211fa9e4478'
def sha(value):
    return hashlib.sha256(value).hexdigest()
data = dict(path=path, name='actual-typed-settings-keys-open-their-own-existing-destination',
            before=before, after=after, beforeSha256LF=sha(before.encode()),
            afterSha256LF=sha(after.encode()))
manifest = dict(baseCommit=head, baseFamilySha256LF=sha(text.encode()),
                candidateWritten=False, runtimeAccepted=False, testsExecuted=False,
                hunk=data,
                correctedTargets=['ANIMATION', 'BOTTOM_BAR', 'SETTINGS_SHARE', 'MESSAGE_NOTIFICATION', 'WEBDAV_BACKUP'],
                originalBodiesAdded=False, secondNavigatorCreated=False,
                knownRemaining=['IconSettings still requires its Windows icon implementation.',
                                'Message notification scheduler/permission UI is still incomplete.'],
                reasoning='Actual typed Root keys mapped to SETTINGS but retained the previous detail; WebDavBackup used the category target DATA_BACKUP which the detail consumer did not support.')
target = lane / 'prepared-route-delta.json'
assert not target.exists()
target.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(path=str(target), sha256Bytes=sha(target.read_bytes()), productWrites=0)))
