"""Synthetic source/package envelopes and nonexecutable binaries; no native loading."""
from pathlib import Path
import copy
import json
import subprocess
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import original_root_ui_packaged_native as native
import test_original_root_ui_runtime_image as fixtures


class PackagedNativeBindingTest(unittest.TestCase):
    def setUp(self):
        self.f=fixtures.RuntimeImageTests();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.repo=self.f.root/'source';self.repo.mkdir()
        self.binary={'libmpv-2.dll':b'nonexecutable fixed MPV bytes','ffmpeg.exe':b'nonexecutable fixed FFmpeg',
                     'ffprobe.exe':b'nonexecutable fixed FFprobe'}
        self.mpv=dict(schemaVersion=1,binary=dict(originalArchive='https://origin.invalid/mpv.7z',archiveSha256='1'*64),
            components={'mpv':dict(repository='mpv-player/mpv')},
            licenseFiles=[dict(path='licenses/mpv-LICENSE',sha256=fixtures.pin(b'mpv notice')['sha256'])])
        self.ffmpeg=dict(schemaVersion=1,binary=dict(variant='lgpl',archiveUrl='https://origin.invalid/ffmpeg.zip',
            archiveSha256='2'*64,ffmpegSha256=fixtures.pin(self.binary['ffmpeg.exe'])['sha256'],
            ffprobeSha256=fixtures.pin(self.binary['ffprobe.exe'])['sha256'],versionOutput='fixed version\nsecond line\n'),
            ffmpegSource=dict(commit='b'*40,archiveSha256='3'*64),buildRecipes=dict(commit='c'*40,archiveSha256='4'*64),
            licenseFiles=[dict(path='licenses/lib+version-LICENSE',sha256=fixtures.pin(b'ffmpeg notice')['sha256'])],
            dependencyLicenseInventory=dict(complete=False,scope='structural fixture'))
        self.sources={native.MPV+'NOTICES.md':b'mpv docs',native.MPV+'licenses/mpv-LICENSE':b'mpv notice',
            native.FFMPEG+'README.md':b'ffmpeg docs',native.FFMPEG+'licenses/lib+version-LICENSE':b'ffmpeg notice',
            native.FIXED:json.dumps(dict(originalDllSha256=fixtures.pin(self.binary['libmpv-2.dll'])['sha256'])).encode(),
            'desktop/tools/fetch-mpv.ps1':b"$downloadUrls = @('https://mirror.invalid/mpv.7z', 'https://origin.invalid/mpv.7z')"}
        self.git('init','--quiet');self.commit_source()
        self.payload={native.NATIVE+name:raw for name,raw in self.binary.items()}
        for src,dest in [(native.MPV+'NOTICES.md','licenses/NOTICES.md'),(native.MPV+'SOURCES.json','licenses/SOURCES.json'),
            (native.MPV+'licenses/mpv-LICENSE','licenses/mpv-LICENSE'),(native.FFMPEG+'README.md','licenses/ffmpeg/README.md'),
            (native.FFMPEG+'SOURCES.json','licenses/ffmpeg/SOURCES.json'),
            (native.FFMPEG+'licenses/lib+version-LICENSE','licenses/ffmpeg/lib+version-LICENSE')]:self.payload[native.NATIVE+dest]=self.sources[src]
        self.mp=dict(sourceUrl=self.mpv['binary']['originalArchive'],mirrors=['https://mirror.invalid/mpv.7z','https://origin.invalid/mpv.7z'],
            sourceProject='https://github.com/shinchiro/mpv-winbuild-cmake',archiveSha256='1'*64,
            dllSha256=fixtures.pin(self.binary['libmpv-2.dll'])['sha256'],architecture='windows-x64',upstreamSource='https://github.com/mpv-player/mpv',
            licenseFiles=['mpv-LICENSE','SOURCES.json','NOTICES.md'])
        self.fp=dict(schemaVersion=1,architecture='windows-x64',variant='lgpl',sourceUrl=self.ffmpeg['binary']['archiveUrl'],
            archiveSha256='2'*64,ffmpegSha256=self.ffmpeg['binary']['ffmpegSha256'],ffprobeSha256=self.ffmpeg['binary']['ffprobeSha256'],
            ffmpegSourceCommit='b'*40,ffmpegSourceArchiveSha256='3'*64,buildRecipesCommit='c'*40,buildRecipesArchiveSha256='4'*64,
            licenseFiles=[r['path'] for r in self.ffmpeg['licenseFiles']],dependencyLicenseInventory=self.ffmpeg['dependencyLicenseInventory'],
            version='fixed version\\r\\nsecond line\\r\\n')
        compiled=native.NATIVE+'bilipai-diagnostic-share.dll';self.payload[compiled]=b'source compiled fixture'
        self.compiled={compiled:fixtures.pin(self.payload[compiled])};self.package_payload()

    def git(self,*args):
        return subprocess.run(['git','-c','core.autocrlf=false','-C',str(self.repo),*args],check=True,capture_output=True).stdout

    def commit_source(self):
        self.sources[native.MPV+'SOURCES.json']=json.dumps(self.mpv).encode()
        self.sources[native.FFMPEG+'SOURCES.json']=json.dumps(self.ffmpeg).encode()
        for name,raw in self.sources.items():
            p=self.repo/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
        self.git('add','.');self.git('-c','user.name=Fixture','-c','user.email=fixture@invalid.local','-c','commit.gpgsign=false',
            'commit','--allow-empty','--quiet','-m','Structural native fixture')
        self.source=self.git('rev-parse','HEAD').decode().strip()
        self.f.run['head_sha']=self.source;self.f.jobs['jobs'][0]['head_sha']=self.source
        self.f.toolchain['sourceSha']=self.source

    def package_payload(self):
        self.payload[native.NATIVE+'provenance.json']=json.dumps(self.mp).encode()
        self.payload[native.NATIVE+'ffmpeg-provenance.json']=json.dumps(self.fp).encode()
        fixtures.packed(self.f.package,{native.image.APP+'app/resources/'+p:raw for p,raw in self.payload.items()})
        self.f.toolchain['candidateZip']=fixtures.pin(self.f.package.read_bytes());self.f.toolchain['sourceSha']=self.source;self.f.reseal()

    def bind(self):
        package=dict(zip=self.f.package,run=self.f.run,jobs=self.f.jobs,log=self.f.log,toolchainRaw=self.f.raw)
        return native.bind_packaged_native(self.repo,self.source,'0.2.427.146',package,self.compiled)

    def test_all_native_binaries_notices_and_provenance_bind_to_exact_git_and_package(self):
        proof=self.bind();self.assertEqual(11,len(proof['resources']));self.assertEqual(8,len(proof['sourceBlobPins']))
        self.assertNotIn(next(iter(self.compiled)),proof['resources']);self.assertFalse(proof['nativeCodeLoaded'])

    def test_consistently_resealed_package_cannot_replace_fixed_mpv_binary(self):
        self.payload[native.NATIVE+'libmpv-2.dll']=b'another binary';self.package_payload()
        with self.assertRaisesRegex(ValueError,'native binary differs'):self.bind()

    def test_consistently_resealed_package_cannot_replace_a_source_notice(self):
        self.payload[native.NATIVE+'licenses/mpv-LICENSE']=b'changed';self.package_payload()
        with self.assertRaisesRegex(ValueError,'native notice differs'):self.bind()

    def test_unknown_native_asset_is_rejected(self):
        self.payload[native.NATIVE+'unknown.dll']=b'unknown';self.package_payload()
        with self.assertRaisesRegex(ValueError,'native inventory differs'):self.bind()

    def test_missing_notice_is_rejected(self):
        del self.payload[native.NATIVE+'licenses/mpv-LICENSE'];self.package_payload()
        with self.assertRaisesRegex(ValueError,'native inventory differs'):self.bind()

    def test_provenance_cannot_name_another_original_archive(self):
        self.mp['archiveSha256']='0'*64;self.package_payload()
        with self.assertRaisesRegex(ValueError,'MPV provenance differs'):self.bind()

    def test_provenance_cannot_drop_mpv_notice_entries(self):
        self.mp['licenseFiles']=[];self.package_payload()
        with self.assertRaisesRegex(ValueError,'MPV notice inventory differs'):self.bind()

    def test_provenance_cannot_replace_ffmpeg_source_revision(self):
        self.fp['ffmpegSourceCommit']='0'*40;self.package_payload()
        with self.assertRaisesRegex(ValueError,'FFmpeg provenance differs'):self.bind()

    def test_version_output_must_match_the_fixed_source_catalog(self):
        self.fp['version']='different version';self.package_payload()
        with self.assertRaisesRegex(ValueError,'version differs'):self.bind()

    def test_source_notice_checksum_mismatch_is_rejected(self):
        self.mpv['licenseFiles'][0]['sha256']='0'*64;self.commit_source();self.package_payload()
        with self.assertRaisesRegex(ValueError,'source notice checksum differs'):self.bind()

    def test_duplicate_source_notice_destination_is_rejected(self):
        self.mpv['licenseFiles'].append(copy.deepcopy(self.mpv['licenseFiles'][0]));self.commit_source();self.package_payload()
        with self.assertRaisesRegex(ValueError,'notice destination collision'):self.bind()

    def test_source_licenses_cannot_escape_the_immutable_source_graph(self):
        self.mpv['licenseFiles'][0]['path']='../escape';self.commit_source();self.package_payload()
        with self.assertRaises(ValueError):self.bind()

    def test_compiled_native_file_cannot_bypass_its_qualified_source_ledger(self):
        self.compiled[next(iter(self.compiled))]['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'Source-compiled native resource differs'):self.bind()

    def test_unknown_git_object_cannot_supply_native_pins(self):
        with self.assertRaisesRegex(ValueError,'Missing fixed native source blob'):
            native.source_inventory(self.repo,'0'*40)


if __name__=='__main__':unittest.main()
