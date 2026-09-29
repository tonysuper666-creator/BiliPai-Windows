import hashlib
import importlib.util
import json
import subprocess
import shutil
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("sync_upstream", Path(__file__).parents[1] / "sync-upstream.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)
OLD = "a" * 40
NEW = "b" * 40


class ReleasePolicyTests(unittest.TestCase):
    def test_latest_alpha_is_not_filtered_out(self):
        releases = [
            {"id": 1, "tag_name": "v0.2.2", "published_at": "2026-08-01T00:00:00Z", "prerelease": False},
            {"id": 2, "tag_name": "v0.2.3-alpha.10", "published_at": "2026-09-01T00:00:00Z", "prerelease": True},
            {"id": 3, "tag_name": "draft", "published_at": "2026-10-01T00:00:00Z", "draft": True},
        ]
        with patch.object(sync, "request_json", return_value=releases):
            self.assertEqual(sync.latest_release()["tag_name"], "v0.2.3-alpha.10")

    def test_no_published_release_does_not_guess_from_apk_name(self):
        with patch.object(sync, "request_json", return_value=[]):
            with self.assertRaises(sync.UpdateError):
                sync.latest_release()

    def test_annotated_tag_resolves_to_commit_not_tag_object(self):
        with patch.object(sync, "request_json", side_effect=[
            {"object": {"type": "tag", "sha": OLD}}, {"object": {"type": "commit", "sha": NEW}}
        ]):
            self.assertEqual(sync.resolve_tag("v0.2.3-alpha.10"), NEW)

    def test_branch_name_cannot_inject_git_arguments(self):
        branch = sync.branch_name("../../--upload-pack=evil", NEW)
        self.assertTrue(branch.startswith("windows/upstream-"))
        self.assertNotIn("..", branch.split("/")[-1].split("--")[0])
        self.assertNotIn("=", branch)


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        (self.repo / "desktop").mkdir()
        (self.repo / "app").mkdir()
        (self.repo / "app/Api.kt").write_bytes(b"old source")
        self.manifest = {
            "schemaVersion": 1, "upstreamRepository": sync.UPSTREAM,
            "hashNormalization": "lf",
            "upstreamTag": "v0.2.3-alpha.9", "upstreamCommit": OLD,
            "sources": [{"path": "app/Api.kt", "sha256": hashlib.sha256(b"old source").hexdigest(),
                         "features": ["feed", "search"]}],
            "featureCoverage": {"feed": "native", "download": "notPorted"},
        }
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_local_inventory_must_match_declared_pin(self):
        self.assertEqual(sync.read_manifest(self.repo), self.manifest)
        (self.repo / "app/Api.kt").write_bytes(b"unreviewed edit")
        with self.assertRaisesRegex(sync.UpdateError, "differs"):
            sync.read_manifest(self.repo)

    def test_inventory_hashes_ignore_checkout_crlf(self):
        (self.repo / "app/Api.kt").write_bytes(b"source\r\nsecond line\r\n")
        self.manifest["sources"][0]["sha256"] = sync.source_digest(b"source\nsecond line\n")
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.assertEqual(sync.read_manifest(self.repo), self.manifest)
        self.assertNotEqual(sync.source_digest(b"source\nsecond line\n"), sync.source_digest(b"source\nchanged\n"))

    def test_inventory_requires_explicit_lf_hash_contract(self):
        del self.manifest["hashNormalization"]
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaisesRegex(sync.UpdateError, "hashNormalization"):
            sync.read_manifest(self.repo)

    def test_upstream_crlf_only_changes_are_not_feature_changes(self):
        self.manifest["sources"][0]["sha256"] = sync.source_digest(b"source\n")
        with patch.object(sync, "latest_release", return_value={"tag_name": "alpha.10"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value={"tree": [{"path": "app/Api.kt", "type": "blob", "mode": "100644"}]}), \
             patch.object(sync, "source_bytes", return_value=b"source\r\n"):
            report, hashes = sync.check_update(self.repo, self.manifest)
        self.assertEqual(report["changedReusedSources"], [])
        self.assertEqual(hashes["app/Api.kt"], sync.source_digest(b"source\n"))

    def test_rejects_windows_and_posix_path_escapes(self):
        for path in ["../secret", "/secret", "C:/secret", "app\\Api.kt", "desktop/tool.py", ".git/config"]:
            with self.subTest(path=path), self.assertRaises(sync.UpdateError):
                sync.source_path(path)

    def test_changed_reused_sources_flag_only_declared_features(self):
        with patch.object(sync, "latest_release", return_value={"tag_name": "alpha.10", "prerelease": True}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value={"tree": [{"path": "app/Api.kt", "type": "blob", "mode": "100644"}]}), \
             patch.object(sync, "source_bytes", return_value=b"new source"), \
             patch.object(sync, "git") as git:
            report, hashes = sync.check_update(self.repo, self.manifest)
        git.assert_not_called()
        self.assertEqual(report["featuresNeedingReview"], ["feed", "search"])
        self.assertEqual(report["featureCoverage"]["download"], "notPorted")
        self.assertEqual(hashes["app/Api.kt"], hashlib.sha256(b"new source").hexdigest())

    def test_removed_reused_source_requires_manual_adaptation(self):
        with patch.object(sync, "latest_release", return_value={"tag_name": "alpha.10"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value={"tree": []}):
            with self.assertRaisesRegex(sync.UpdateError, "removed or moved"):
                sync.check_update(self.repo, self.manifest)

    def test_symlink_in_upstream_inventory_is_rejected(self):
        with patch.object(sync, "latest_release", return_value={"tag_name": "alpha.10"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value={"tree": [{"path": "app/Api.kt", "type": "blob", "mode": "120000"}]}):
            with self.assertRaises(sync.UpdateError):
                sync.check_update(self.repo, self.manifest)

    def test_moved_existing_tag_is_not_silently_trusted(self):
        with patch.object(sync, "latest_release", return_value={"tag_name": self.manifest["upstreamTag"]}), \
             patch.object(sync, "resolve_tag", return_value=NEW):
            with self.assertRaisesRegex(sync.UpdateError, "tag moved"):
                sync.check_update(self.repo, self.manifest)

    def test_dirty_checkout_is_not_modified_or_fetched(self):
        with patch.object(sync, "git", return_value=" M desktop/App.kt") as git:
            with self.assertRaisesRegex(sync.UpdateError, "dirty checkout"):
                sync.sync_update(self.repo, self.manifest, {"status": "updateAvailable"}, {}, self.repo / "updates", None)
        git.assert_called_once_with(self.repo, "status", "--porcelain")

    def test_inherited_android_workflow_blocks_publication(self):
        workflows = self.repo / ".github/workflows"
        workflows.mkdir(parents=True)
        (workflows / "Build.yml").write_text("on: push\n", encoding="utf-8")
        with self.assertRaisesRegex(sync.UpdateError, "inherited workflows"):
            sync.publication_check(self.repo)

    def test_upstream_origin_blocks_publication(self):
        with patch.object(sync, "git", return_value="https://github.com/jay3-yy/BiliPai.git"):
            with self.assertRaisesRegex(sync.UpdateError, "never the original"):
                sync.publication_check(self.repo)

    def test_login_api_comparison_ignores_unselected_android_singleton_changes(self):
        tools = self.repo / "desktop/tools"
        tools.mkdir()
        shutil.copyfile(Path(__file__).parents[1] / "extract-upstream-api.py", tools / "extract-upstream-api.py")
        source = b'''internal const val FORCE_COOKIE_HEADER = "X-Bili-Cookie"
interface BilibiliApi {
    @GET("nav")
    suspend fun getNavInfo(): ApiResponse<NavData>
}
interface PassportApi {
    @GET("validate")
    suspend fun validateCookieSession(): ApiResponse<LoginData>
    @GET("generate")
    suspend fun generateQrCode(): ApiResponse<QrCodeData>
    @GET("poll")
    suspend fun pollQrCode(): ApiResponse<QrPollData>
}
object AndroidOnlySingleton { val feature = "old" }
'''
        unrelated = source.replace(b'feature = "old"', b'feature = "new"')
        auth_change = source.replace(b'@GET("poll")', b'@GET("changed-poll")')
        self.assertEqual(sync.selected_login_api(self.repo, source), sync.selected_login_api(self.repo, unrelated))
        self.assertEqual(sync.selected_login_api(self.repo, source), sync.selected_login_api(self.repo, source.replace(b"\n", b"\r\n")))
        self.assertNotEqual(sync.selected_login_api(self.repo, source), sync.selected_login_api(self.repo, auth_change))

    def test_github_text_in_another_host_is_not_a_valid_publish_remote(self):
        with patch.object(sync, "git", return_value="https://example.org/github.com/my/fork.git"):
            with self.assertRaisesRegex(sync.UpdateError, "GitHub fork remote"):
                sync.publication_check(self.repo)


class ApiClientRiskTests(unittest.TestCase):
    def setUp(self):
        self.repo = Path(__file__).parents[3]
        self.path = "app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"
        self.source = (self.repo / self.path).read_bytes()
        manifest = json.loads((self.repo / "desktop/upstream-sources.json").read_text(encoding="utf-8"))
        self.manifest = {**manifest, "sources": [next(item for item in manifest["sources"] if item["path"] == self.path)]}

    def report(self, candidate):
        tree = {"tree": [{"path": self.path, "type": "blob", "mode": "100644"}]}
        with patch.object(sync, "latest_release", return_value={"tag_name": "sensitive-test"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value=tree), \
             patch.object(sync, "source_bytes", return_value=candidate):
            return sync.check_update(self.repo, self.manifest)[0]

    def changed(self, before, after):
        self.assertEqual(self.source.count(before), 1, before)
        return self.source.replace(before, after)

    def test_cookie_buvid_authorization_and_visitor_implementation_changes_require_review(self):
        probes = [
            (b'.header("Cookie", forcedCookie)', b'.header("Cookie", forcedCookie + "; changed=1")'),
            (b'.header("buvid", loginBuvid ?: TokenManager.buvid3Cache.orEmpty())', b'.header("buvid", loginBuvid ?: "changed")'),
            (b'.cookieJar(appSessionCookieJar)', b'.cookieJar(PlaybackAccountCookieJar(account))'),
            (b'chain.proceed(applyForcedCookieHeader(chain.request()))', b'chain.proceed(chain.request())'),
            (b'.value(guestBuvid3)', b'.value("changed-visitor")'),
            (b'.baseUrl("https://passport.bilibili.com/")', b'.baseUrl("https://changed.bilibili.com/")'),
            (b'"11111111"', b'"changed-session-id"'),
            (b'val b_4: String = ""', b'val b_4: String? = null'),
            (b'PlaybackAccountCookieJar(account: StoredAccountSession)', b'PlaybackAccountCookieJar(account: StoredAccountSession = defaultAccount())'),
            (b'applyForcedCookieHeader(request: okhttp3.Request): okhttp3.Request', b'applyForcedCookieHeader(request: okhttp3.Request = defaultRequest()): okhttp3.Request'),
        ]
        for before, after in probes:
            with self.subTest(before=before):
                report = self.report(self.changed(before, after))
                self.assertTrue(report["networkBehaviorChanged"])
                self.assertFalse(report["autoPublishEligible"])
                self.assertTrue(report["manualReviewReasons"])
                self.assertIn("login", report["featuresNeedingReview"])

    def test_selected_windows_visitor_route_and_playback_route_require_review(self):
        for before, after in [(b'@GET("x/frontend/finger/spi")', b'@GET("changed/spi")'),
                              (b'@GET("x/player/wbi/playurl")', b'@GET("changed/playurl")')]:
            # The playback endpoint has two declarations; modifying both still changes the selected one.
            with self.subTest(before=before):
                self.assertIn(before, self.source)
                report = self.report(self.source.replace(before, after))
                self.assertTrue(report["windowsApiContractChanged"])
                self.assertFalse(report["autoPublishEligible"])

    def test_unused_android_methods_and_unrelated_singletons_remain_low_risk(self):
        additions = [
            self.changed(b'interface BilibiliApi {', b'interface BilibiliApi {\n    @GET("android-only")\n    suspend fun androidOnly(): String\n'),
            self.changed(b'interface PassportApi {', b'interface PassportApi {\n    @GET("android-only-auth")\n    suspend fun androidOnlyAuth(): String\n'),
            self.source + b'\nobject AndroidOnlySingleton { val cookieName = "unrelated" }\n',
            self.source.replace(b'suspend fun getIpZone(): IpLocationResponse', b'suspend fun getIpZone(): ChangedAndroidResponse'),
        ]
        for candidate in additions:
            with self.subTest(candidateLength=len(candidate)):
                report = self.report(candidate)
                self.assertFalse(report["windowsApiContractChanged"])
                self.assertFalse(report["networkBehaviorChanged"])
                self.assertTrue(report["autoPublishEligible"])
                self.assertEqual(report["manualReviewReasons"], [])

    def test_sensitive_import_changes_are_not_hidden_by_unchanged_bodies(self):
        report = self.report(self.changed(b'import com.android.purebilibili.core.store.TokenManager',
                                         b'import changed.platform.TokenManager'))
        self.assertTrue(report["networkBehaviorChanged"])
        self.assertFalse(report["autoPublishEligible"])
        template = self.changed(b'object NetworkModule {', b'object NetworkModule {\n    val templateOnly = "${TemplateOnlyHelper.value}"\n')
        first = b'import original.platform.TemplateOnlyHelper\n' + template
        second = b'import changed.platform.TemplateOnlyHelper\n' + template
        self.assertNotEqual(sync.selected_network_behavior(first), sync.selected_network_behavior(second))

    def test_operator_and_statement_whitespace_in_sensitive_code_is_not_erased(self):
        prefix = b'object NetworkModule {\n    var probe = 1\n    val result = '
        decrement = self.changed(b'object NetworkModule {', prefix + b'--probe\n')
        negation = self.changed(b'object NetworkModule {', prefix + b'- -probe\n')
        self.assertNotEqual(sync.selected_network_behavior(decrement), sync.selected_network_behavior(negation))

    def test_crlf_is_ignored_but_sensitive_comments_and_templates_are_conservatively_reviewed(self):
        fingerprint = sync.selected_network_behavior(self.source)
        self.assertEqual(fingerprint, sync.selected_network_behavior(sync.normalized_source(self.source).replace(b"\n", b"\r\n")))
        annotated = self.changed(b'object NetworkModule {', b'object NetworkModule {\n/* ignored } /* nested { */ } */\n// ignored } {\n')
        self.assertNotEqual(fingerprint, sync.selected_network_behavior(annotated))
        literal = self.changed(b'object NetworkModule {', b'''object NetworkModule {
    val lexicalProbe = "${listOf("}", "{").joinToString("}")}"
    val rawProbe = """ braces } { ${"}"} """
    val charProbe = '}'
''')
        report = self.report(literal)
        self.assertTrue(report["networkBehaviorChanged"])
        self.assertFalse(report["autoPublishEligible"])

    def test_missing_duplicate_and_unterminated_sensitive_structures_fail_closed(self):
        candidates = [
            self.changed(b'object NetworkModule {', b'object RemovedNetworkModule {'),
            self.source + b'\nobject NetworkModule {}\n',
            self.source + b'\n/* unterminated',
            self.source + b'\nobject Unclosed {',
            self.changed(b'object NetworkModule {', b'object NetworkModule { val broken = "unterminated'),
        ]
        for candidate in candidates:
            with self.subTest(candidateLength=len(candidate)), self.assertRaises(sync.UpdateError):
                self.report(candidate)


class IsolatedWorktreeTests(unittest.TestCase):
    def test_failed_build_preserves_original_branch_and_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repo = root / "repo"
            repo.mkdir()
            sync.git(repo, "init", "-b", "main")
            sync.git(repo, "config", "user.name", "Updater Test")
            sync.git(repo, "config", "user.email", "updater@example.invalid")
            (repo / "app").mkdir()
            (repo / "app/Api.kt").write_bytes(b"old")
            (repo / "app/build.gradle.kts").write_text("versionCode = 406", encoding="utf-8")
            (repo / ".github/workflows").mkdir(parents=True)
            (repo / ".github/workflows/windows-desktop.yml").write_text("name: test", encoding="utf-8")
            sync.git(repo, "add", ".")
            sync.git(repo, "commit", "-m", "Upstream baseline")
            baseline = sync.git(repo, "rev-parse", "HEAD")
            sync.git(repo, "checkout", "-b", "incoming")
            (repo / "app/Api.kt").write_bytes(b"new")
            sync.git(repo, "commit", "-am", "Upstream release")
            incoming = sync.git(repo, "rev-parse", "HEAD")
            sync.git(repo, "checkout", "main")
            (repo / "desktop").mkdir()
            manifest = {
                "schemaVersion": 1, "upstreamRepository": sync.UPSTREAM,
                "hashNormalization": "lf",
                "upstreamTag": "old", "upstreamCommit": baseline,
                "sources": [{"path": "app/Api.kt", "sha256": hashlib.sha256(b"old").hexdigest()}],
            }
            (repo / "desktop/upstream-sources.json").write_text(json.dumps(manifest), encoding="utf-8")
            sync.git(repo, "add", ".")
            sync.git(repo, "commit", "-m", "Windows baseline")
            original_head = sync.git(repo, "rev-parse", "HEAD")
            report = {
                "status": "updateAvailable", "candidateCommit": incoming,
                "candidateBranch": "windows/upstream-new", "candidateTag": "new",
                "changedReusedSources": ["app/Api.kt"], "featuresNeedingReview": [],
            }
            real_run = sync.run

            def simulated_build(command, cwd, **kwargs):
                if command[:2] == ["git", "fetch"] or command[0] == sync.sys.executable:
                    return subprocess.CompletedProcess(command, 0, "", "")
                if command[0] == "pwsh":
                    raise sync.UpdateError("simulated failed Windows build")
                return real_run(command, cwd, **kwargs)

            with patch.object(sync, "run", side_effect=simulated_build):
                with self.assertRaisesRegex(sync.UpdateError, "Original checkout preserved"):
                    sync.sync_update(repo, manifest, report,
                                     {"app/Api.kt": hashlib.sha256(b"new").hexdigest()}, root / "updates", None)
            self.assertEqual(sync.git(repo, "rev-parse", "HEAD"), original_head)
            self.assertEqual(sync.git(repo, "branch", "--show-current"), "main")
            self.assertEqual(sync.git(repo, "status", "--porcelain"), "")
            self.assertEqual((repo / "app/Api.kt").read_bytes(), b"old")
            self.assertTrue(Path(report["candidatePath"]).is_dir())
            self.assertEqual((Path(report["candidatePath"]) / "app/Api.kt").read_bytes(), b"new")
            self.assertEqual(report["status"], "candidateFailed")


if __name__ == "__main__":
    unittest.main()
