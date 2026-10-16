import hashlib
import importlib.util
import json
import subprocess
import shutil
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from v025_source_paths import canonical_source

spec = importlib.util.spec_from_file_location("sync_upstream", Path(__file__).parents[1] / "sync-upstream.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)
OLD = "a" * 40
NEW = "b" * 40


def tree_blob(path, contents, mode="100644"):
    return {"path": path, "type": "blob", "mode": mode, "sha": sync.blob_oid(contents)}


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

    @staticmethod
    def release_page(first_id, count=100, published_at="2026-08-01T00:00:00Z"):
        return [{"id": release_id, "tag_name": f"v-fixture-{release_id}",
                 "published_at": published_at, "prerelease": False}
                for release_id in range(first_id, first_id + count)]

    def test_scans_all_four_release_pages_before_selecting_latest_alpha(self):
        pages = [self.release_page(1), self.release_page(101), self.release_page(201), self.release_page(301, 35)]
        candidate = pages[3][0]
        candidate.update(tag_name="v0.3.3-alpha.1", published_at="2026-10-08T15:00:00Z", prerelease=True)
        pages[3][-1].update(published_at="2026-10-09T15:00:00Z", draft=True)
        with patch.object(sync, "request_json", side_effect=pages) as fetch:
            self.assertIs(sync.latest_release(), candidate)
        self.assertEqual([entry.args[0] for entry in fetch.call_args_list],
                         [f"releases?per_page=100&page={page}" for page in range(1, 5)])

    def test_full_final_release_page_requires_the_empty_terminal_page(self):
        first = self.release_page(1)
        with patch.object(sync, "request_json", side_effect=[first, []]) as fetch:
            self.assertIs(sync.latest_release(), first[-1])
        self.assertEqual([entry.args[0] for entry in fetch.call_args_list],
                         ["releases?per_page=100&page=1", "releases?per_page=100&page=2"])

    def test_offset_and_fractional_publication_times_compare_by_instant_then_id(self):
        releases = [
            {"id": 99, "tag_name": "earlier-fraction-high-id", "published_at": "2026-10-09T11:30:00.1000001+02:00"},
            {"id": 2, "tag_name": "later-fraction", "published_at": "2026-10-09T09:30:00.1000002Z"},
            {"id": 3, "tag_name": "same-instant-higher-id", "published_at": "2026-10-09T04:30:00.100000200-05:00"},
        ]
        with patch.object(sync, "request_json", return_value=releases):
            self.assertIs(sync.latest_release(), releases[2])

    def test_invalid_later_page_never_returns_the_first_page_candidate(self):
        valid = {"id": 101, "tag_name": "later", "published_at": "2026-10-09T10:00:00Z"}
        invalid = [
            {"message": "not a release page"}, [dict(valid)] * 101, [None],
            [{**valid, "id": True}], [{**valid, "id": 0}],
            [{**valid, "draft": "false"}], [{**valid, "prerelease": "true"}],
            [{**valid, "tag_name": 17}], [{**valid, "published_at": []}],
            [{**valid, "published_at": "2026-13-01T10:00:00Z"}],
            [{**valid, "published_at": "2026-10-09T10:00:00"}],
            [{**valid, "published_at": "2026-10-09T10:00:00+00:60"}],
            [{**valid, "published_at": "2026-10-09T10:00:00+24:00"}],
        ]
        for bad_page in invalid:
            with self.subTest(bad_page=bad_page), patch.object(sync, "request_json", side_effect=[self.release_page(1), bad_page]):
                with self.assertRaises(sync.UpdateError):
                    sync.latest_release()

    def test_repeated_full_release_page_is_not_a_complete_scan(self):
        first = self.release_page(1)
        with patch.object(sync, "request_json", side_effect=[first, first]) as fetch:
            with self.assertRaisesRegex(sync.UpdateError, "repeated release ID"):
                sync.latest_release()
        self.assertEqual(fetch.call_count, 2)

    def test_overlapping_release_id_on_short_later_page_fails(self):
        first = self.release_page(1)
        overlap = {**first[-1], "published_at": "2026-10-09T10:00:00Z"}
        with patch.object(sync, "request_json", side_effect=[first, [overlap]]):
            with self.assertRaisesRegex(sync.UpdateError, "repeated release ID"):
                sync.latest_release()

    def test_full_pages_exhausting_budget_never_claim_a_complete_scan(self):
        pages = [self.release_page(1), self.release_page(101)]
        with patch.object(sync, "MAX_RELEASE_PAGES", 2), patch.object(sync, "request_json", side_effect=pages) as fetch:
            with self.assertRaisesRegex(sync.UpdateError, "page budget"):
                sync.latest_release()
        self.assertEqual(fetch.call_count, 2)

    def test_later_request_failure_never_returns_partial_candidates(self):
        failure = sync.UpdateError("fixture release page request failed")
        with patch.object(sync, "request_json", side_effect=[self.release_page(1), failure]) as fetch:
            with self.assertRaisesRegex(sync.UpdateError, "fixture release page request failed"):
                sync.latest_release()
        self.assertEqual(fetch.call_count, 2)

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

    def populate_inventory(self, count):
        self.manifest["sources"] = []
        for index in range(count):
            relative = f"app/Api{index}.kt"
            contents = f"source {index}\n".encode("utf-8")
            (self.repo / relative).write_bytes(contents)
            self.manifest["sources"].append({"path": relative, "sha256": sync.source_digest(contents)})
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_inventory_accepts_complete_sources_above_legacy_count_limit(self):
        for count in (201, 1327):
            with self.subTest(count=count):
                self.populate_inventory(count)
                self.assertEqual(sync.read_manifest(self.repo), self.manifest)

    def test_large_inventory_still_verifies_the_final_source(self):
        self.populate_inventory(1327)
        (self.repo / self.manifest["sources"][-1]["path"]).write_bytes(b"unreviewed final source")
        with self.assertRaisesRegex(sync.UpdateError, "differs"):
            sync.read_manifest(self.repo)

    def test_large_inventory_still_rejects_duplicate_paths(self):
        self.populate_inventory(1327)
        self.manifest["sources"][-1] = dict(self.manifest["sources"][0])
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaisesRegex(sync.UpdateError, "Duplicate source"):
            sync.read_manifest(self.repo)

    def test_oversized_inventory_is_rejected_before_source_validation(self):
        self.manifest["padding"] = "x" * sync.MAX_MANIFEST_BYTES
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaisesRegex(sync.UpdateError, "configuration input budget"):
            sync.read_manifest(self.repo)

    def test_raw_resource_hash_does_not_normalize_png_bytes(self):
        contents = b"\x89PNG\r\n\x1a\nfixture\r\n"
        (self.repo / "app/image.png").write_bytes(contents)
        self.manifest["resources"] = [{"path": "app/image.png", "sha256": hashlib.sha256(contents).hexdigest(),
                                       "hashNormalization": "raw"}]
        manifest_file = self.repo / "desktop/upstream-sources.json"
        manifest_file.write_text(json.dumps(self.manifest), encoding="utf-8")
        self.assertEqual(sync.read_manifest(self.repo), self.manifest)
        self.manifest["resources"][0]["sha256"] = sync.source_digest(contents)
        manifest_file.write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaisesRegex(sync.UpdateError, "differs"):
            sync.read_manifest(self.repo)

    def test_source_and_resource_duplicate_identity_is_rejected(self):
        self.manifest["resources"] = [dict(self.manifest["sources"][0])]
        (self.repo / "desktop/upstream-sources.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with self.assertRaisesRegex(sync.UpdateError, "Duplicate source/resource"):
            sync.read_manifest(self.repo)

    def test_unchanged_large_inventory_skips_all_raw_downloads_without_inventing_compatibility(self):
        self.populate_inventory(1328)
        tree = {"tree": [tree_blob(item["path"], (self.repo / item["path"]).read_bytes())
                         for item in self.manifest["sources"]]}
        with patch.object(sync, "latest_release", return_value={"tag_name": "new"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value=tree), \
             patch.object(sync, "source_bytes") as fetch:
            report, hashes = sync.check_update(self.repo, self.manifest)
        fetch.assert_not_called()
        self.assertEqual(len(hashes), 1328)
        self.assertEqual(report["unchangedBlobInputs"], 1328)
        self.assertEqual(report["sourceDownloads"], 0)
        self.assertEqual(report["status"], "updateAvailable")
        self.assertFalse(report["autoBuildEligible"])
        self.assertFalse(report["autoPublishEligible"])
        self.assertEqual(report["buildCompatibility"]["status"], "manualAdaptationRequired")
        self.assertEqual(report["buildCompatibility"]["reasons"][0]["code"], "canonicalBaselineUnavailable")

    def test_invalid_tree_structure_never_uses_unverified_blob_ids(self):
        invalid = [
            {"tree": [{}]}, {"tree": "not a tree"}, {"tree": [], "truncated": True},
            {"tree": [tree_blob("app/Api.kt", b"old source"), tree_blob("app/Api.kt", b"old source")]},
        ]
        for tree in invalid:
            with self.subTest(tree=tree), \
                 patch.object(sync, "latest_release", return_value={"tag_name": "new"}), \
                 patch.object(sync, "resolve_tag", return_value=NEW), \
                 patch.object(sync, "request_json", return_value=tree), \
                 patch.object(sync, "source_bytes") as fetch:
                with self.assertRaises(sync.UpdateError):
                    sync.check_update(self.repo, self.manifest)
                fetch.assert_not_called()

    def test_fixed_commit_download_must_match_the_returned_tree_blob(self):
        tree = {"tree": [tree_blob("app/Api.kt", b"verified incoming") ]}
        with patch.object(sync, "latest_release", return_value={"tag_name": "new"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value=tree), \
             patch.object(sync, "source_bytes", return_value=b"different incoming") as fetch:
            with self.assertRaisesRegex(sync.UpdateError, "blob identity"):
                sync.check_update(self.repo, self.manifest)
        fetch.assert_called_once_with(NEW, "app/Api.kt")

    def test_unknown_catalog_is_fatal_instead_of_an_unproven_ready_baseline(self):
        catalog = self.repo / sync.CANONICAL_CATALOG_PATH
        catalog.parent.mkdir(parents=True)
        catalog.write_text("{}", encoding="utf-8")
        with patch.object(sync, "latest_release", return_value={"tag_name": "new"}), \
             patch.object(sync, "resolve_tag", return_value=NEW):
            with self.assertRaisesRegex(sync.UpdateError, "Unknown canonical source catalog"):
                sync.check_update(self.repo, self.manifest)

    def test_compatibility_rejection_keeps_dirty_first_and_never_fetches(self):
        report = {"status": "updateAvailable", "autoBuildEligible": False,
                  "buildCompatibility": {"status": "manualAdaptationRequired"}}
        with patch.object(sync, "git", return_value="") as git:
            with self.assertRaisesRegex(sync.UpdateError, "compatibility preflight"):
                sync.sync_update(self.repo, self.manifest, report, {}, self.repo / "updates", None)
        git.assert_called_once_with(self.repo, "status", "--porcelain")

    def test_candidate_worktree_cannot_write_inside_the_original_checkout(self):
        report = {"status": "updateAvailable", "autoBuildEligible": True,
                  "buildCompatibility": {"status": "ready"}}
        for root in (self.repo, self.repo / "updates"):
            with self.subTest(root=root), patch.object(sync, "git", return_value="") as git:
                with self.assertRaisesRegex(sync.UpdateError, "outside the original checkout"):
                    sync.sync_update(self.repo, self.manifest, report, {}, root, None)
                git.assert_called_once_with(self.repo, "status", "--porcelain")
        self.assertFalse((self.repo / "updates").exists())

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
        (self.repo / "app/Api.kt").write_bytes(b"source\n")
        self.manifest["sources"][0]["sha256"] = sync.source_digest(b"source\n")
        with patch.object(sync, "latest_release", return_value={"tag_name": "alpha.10"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value={"tree": [tree_blob("app/Api.kt", b"source\r\n")]}), \
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
             patch.object(sync, "request_json", return_value={"tree": [tree_blob("app/Api.kt", b"new source")]}), \
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
            report, _ = sync.check_update(self.repo, self.manifest)
        self.assertEqual(report["status"], "updateAvailable")
        self.assertFalse(report["autoBuildEligible"])
        self.assertIn({"code": "inputRemovedOrMoved", "path": "app/Api.kt"},
                      [{"code": item["code"], "path": item["path"]} for item in report["buildCompatibility"]["reasons"]])

    def test_symlink_in_upstream_inventory_is_rejected(self):
        with patch.object(sync, "latest_release", return_value={"tag_name": "alpha.10"}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value={"tree": [tree_blob("app/Api.kt", b"target", mode="120000")]}):
            report, _ = sync.check_update(self.repo, self.manifest)
        self.assertFalse(report["autoBuildEligible"])
        self.assertFalse(report["autoPublishEligible"])
        self.assertIn("inputNotRegularFile", [item["code"] for item in report["buildCompatibility"]["reasons"]])

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


class CanonicalCompatibilityTests(unittest.TestCase):
    """Canonical v025 checks only; adapted multi-version coverage is tested below."""

    def setUp(self):
        adapted = patch.object(sync, "adapted_compatibility_inputs", return_value=[])
        adapted.start()
        self.addCleanup(adapted.stop)
        self.repo = Path(__file__).parents[3]
        self.manifest = sync.read_manifest(self.repo)
        self.catalog = sync.canonical_baseline(self.repo, self.manifest)
        self.entries = sync.compatibility_inputs(self.repo, self.manifest, self.catalog)
        self.tree = {"tree": [{"path": item["path"], "type": "blob", "mode": "100644",
                               "sha": sync.blob_oid(sync.normalized_source((self.repo / item["path"]).read_bytes()))
                               if item["path"] == sync.BUILD_CONFIGURATION_PATH
                               else self.catalog["paths"][item["path"]]["blob"]} for item in self.entries]}

    def report(self, changed=None):
        changed = changed or {}
        tree = {"tree": [tree_blob(item["path"], changed[item["path"]]) if item["path"] in changed else item
                         for item in self.tree["tree"]]}

        def fetch(commit, path):
            self.assertEqual(commit, NEW)
            self.assertIn(path, changed)
            return changed[path]

        with patch.object(sync, "latest_release", return_value={"tag_name": "fixed-next", "prerelease": True}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value=tree), \
             patch.object(sync, "source_bytes", side_effect=fetch) as downloaded:
            report, hashes = sync.check_update(self.repo, self.manifest)
        self.assertEqual(downloaded.call_count, len(changed))
        return report, hashes

    def test_unchanged_canonical_scope_skips_downloads_and_allows_build_only(self):
        report, hashes = self.report()
        self.assertEqual(report["status"], "updateAvailable")
        self.assertTrue(report["autoBuildEligible"])
        self.assertTrue(report["autoPublishEligible"])
        self.assertEqual(report["buildCompatibility"]["status"], "ready")
        self.assertEqual(report["buildCompatibility"]["reasons"], [])
        self.assertEqual(report["buildCompatibility"]["checkedInputCount"], len(self.entries))
        self.assertGreater(len(self.entries), 1327)
        self.assertEqual(len(hashes), len(self.entries))
        self.assertEqual(report["unchangedBlobInputs"], len(self.entries))
        self.assertEqual(report["sourceDownloads"], 0)

    def test_changed_canonical_source_is_reported_without_rewriting_any_pin(self):
        row = self.manifest["sources"][0]
        before = json.dumps(self.manifest, sort_keys=True)
        contents = sync.normalized_source((self.repo / row["path"]).read_bytes()) + b"\n// changed upstream\n"
        report, hashes = self.report({row["path"]: contents})
        self.assertFalse(report["autoBuildEligible"])
        self.assertFalse(report["autoPublishEligible"])
        self.assertEqual(report["changedReusedSources"], [row["path"]])
        self.assertEqual(hashes[row["path"]], sync.source_digest(contents))
        self.assertEqual(json.dumps(self.manifest, sort_keys=True), before)
        reason = report["buildCompatibility"]["reasons"][0]
        self.assertEqual((reason["code"], reason["path"], reason["oldSha256"]),
                         ("canonicalInputChanged", row["path"], row["sha256"]))

    def test_raw_png_change_uses_raw_digest_and_is_an_explicit_resource_adaptation(self):
        row = next(item for item in self.manifest["resources"]
                   if item.get("hashNormalization") == "raw" and item["path"].endswith(".png"))
        contents = (self.repo / row["path"]).read_bytes() + b"\r\nupstream resource change"
        report, hashes = self.report({row["path"]: contents})
        self.assertEqual(report["changedResources"], [row["path"]])
        self.assertEqual(report["changedReusedSources"], [])
        self.assertFalse(report["autoBuildEligible"])
        self.assertEqual(hashes[row["path"]], hashlib.sha256(contents).hexdigest())
        self.assertNotEqual(hashes[row["path"]], sync.source_digest(contents))
        self.assertEqual(report["buildCompatibility"]["reasons"][0]["hashNormalization"], "raw")

    def test_version_build_configuration_change_blocks_even_when_declared_sources_do_not_change(self):
        path = "app/build.gradle.kts"
        contents = sync.normalized_source((self.repo / path).read_bytes()) + b"\n// upstream build change\n"
        report, _ = self.report({path: contents})
        self.assertFalse(report["autoBuildEligible"])
        self.assertEqual(report["changedReusedSources"], [])
        self.assertEqual(report["changedResources"], [])
        reason = report["buildCompatibility"]["reasons"][0]
        self.assertEqual((reason["path"], reason["kind"]), (path, "buildConfiguration"))

    def test_crlf_only_change_preserves_lf_contract_after_exact_blob_verification(self):
        row = self.manifest["sources"][0]
        contents = sync.normalized_source((self.repo / row["path"]).read_bytes()).replace(b"\n", b"\r\n")
        report, _ = self.report({row["path"]: contents})
        self.assertEqual(report["sourceDownloads"], 1)
        self.assertTrue(report["autoBuildEligible"])
        self.assertEqual(report["changedReusedSources"], [])


class VersionMetadataCompatibilityTests(unittest.TestCase):
    """Whole canonical originals and a complete offline check_update report.

    The temporary source-only fixture keeps every current canonical manifest
    input and the actual trusted catalog/baseline. It deliberately has no
    adapted desktop build; the separate real multi-version inventory tests
    continue to cover those inputs. No generator/build/publish result is faked.
    """

    BASELINE_SHA256 = "683162c801ebca4ba7507dd217f255549b99a5ad787807b236e4de1231d2a3d6"
    BASELINE_BLOB = "9d2a852b20f7d5016ec4b397cc2005d117f5b307"
    ORIGINAL_BODY_SHA256 = "a522c22a39492ba84684c673fc7e55a35734b143c087929c0d0f2394c141d79b"
    B209_COMMIT = "b2091b1de636e499df80af7e700796b4f52b7f92"
    B209_SHA256 = "d633a056def7f81830143e5d9115ec848057c3f372bfc87ae0a9de5bde8d1bcf"
    B209_BLOB = "c64da8400b47a3fbf5bebea0a6738a15075a9e70"

    @staticmethod
    def digest(contents, normalization="lf"):
        if normalization == "lf":
            contents = contents.replace(b"\r\n", b"\n")
        return hashlib.sha256(contents).hexdigest()

    @staticmethod
    def blob(contents):
        return hashlib.sha1(b"blob " + str(len(contents)).encode("ascii") + b"\0" + contents).hexdigest()

    @classmethod
    def setUpClass(cls):
        cls.original_repo = Path(__file__).parents[3]
        cls.catalog_bytes = (cls.original_repo / sync.CANONICAL_CATALOG_PATH).read_bytes()
        cls.catalog = json.loads(cls.catalog_bytes)
        cls.baseline = (cls.original_repo / sync.CANONICAL_BUILD_CONFIGURATION_PATH).read_bytes().replace(b"\r\n", b"\n")
        if (hashlib.sha256(cls.catalog_bytes).hexdigest() != sync.CANONICAL_CATALOG_SHA256 or
                cls.digest(cls.baseline) != cls.BASELINE_SHA256 or cls.blob(cls.baseline) != cls.BASELINE_BLOB):
            raise AssertionError("The metadata fixture requires the actual complete trusted original and catalog.")
        manifest = json.loads((cls.original_repo / "desktop/upstream-sources.json").read_bytes())
        build_rows = [item for item in manifest["sources"] if item["path"] == sync.BUILD_CONFIGURATION_PATH]
        if len(build_rows) != 1:
            raise AssertionError("The real manifest must have exactly one original build input.")
        # A later accepted production pin must not change the fixed first-release
        # scenario. Only this fixture's build row is restored to the trusted v025.
        build_rows[0]["sha256"] = cls.BASELINE_SHA256
        manifest["upstreamTag"] = "v0.2.5"
        manifest["upstreamCommit"] = sync.CANONICAL_BASELINE_COMMIT
        cls.manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        cls.fixture_temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.fixture_temp.cleanup)
        cls.fixture_repo = Path(cls.fixture_temp.name)
        for row in manifest["sources"] + manifest.get("resources", []):
            destination = cls.fixture_repo / row["path"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((cls.original_repo / row["path"]).read_bytes())
        # A normal Windows checkout may use CRLF; reconstruct the complete
        # upstream LF blob and verify both of its independently fixed identities.
        cls.b209 = (Path(__file__).parent / "fixtures/upstream-version-metadata/b209-app-build.gradle.kts").read_bytes().replace(b"\r\n", b"\n")
        if cls.digest(cls.b209, "raw") != cls.B209_SHA256 or cls.blob(cls.b209) != cls.B209_BLOB:
            raise AssertionError("The retained b209 fixture must be the complete exact upstream blob.")

    def setUp(self):
        # Fail closed even if a future check accidentally bypasses the injected
        # GitHub responses or tries to turn a metadata assertion into a build.
        for owner, attribute in ((sync.urllib.request, "urlopen"), (sync, "run"), (sync, "git"),
                                 (sync.subprocess, "run"), (sync.subprocess, "Popen"),
                                 (sync, "sync_update"), (sync, "publication_check")):
            blocked = patch.object(owner, attribute, side_effect=AssertionError(
                f"Offline metadata regression cannot call {attribute}."))
            mocked = blocked.start()
            self.addCleanup(blocked.stop)
            self.addCleanup(mocked.assert_not_called)
        self.repo = self.fixture_repo
        self.manifest_file = self.repo / "desktop/upstream-sources.json"
        self.catalog_file = self.repo / sync.CANONICAL_CATALOG_PATH
        self.baseline_file = self.repo / sync.CANONICAL_BUILD_CONFIGURATION_PATH
        self.build_file = self.repo / sync.BUILD_CONFIGURATION_PATH
        for destination, contents in ((self.manifest_file, self.manifest_bytes),
                                      (self.catalog_file, self.catalog_bytes),
                                      (self.baseline_file, self.baseline), (self.build_file, self.baseline)):
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(contents)
        self.manifest = sync.read_manifest(self.repo)
        self.assertGreater(len(self.manifest["sources"]) + len(self.manifest["resources"]), 1327)
        self.assertFalse((self.repo / "desktop/build.gradle.kts").exists())
        self.assertFalse((self.repo / sync.COMPATIBILITY_CATALOG_PATH).exists())
        self.offline_api_calls = []
        self.offline_blob_calls = []

    def version(self, code, name):
        self.assertEqual(self.baseline.count(b"versionCode = 427"), 1)
        self.assertEqual(self.baseline.count(b'versionName = "0.2.5"'), 1)
        return self.baseline.replace(b"versionCode = 427", f"versionCode = {code}".encode("ascii"), 1).replace(
            b'versionName = "0.2.5"', f'versionName = "{name}"'.encode("ascii"), 1)

    def accept_fixture_pin(self, code, name, *, commit=None, tag=None, crlf=False):
        # This prepares a later local pin; it does not call sync_update or claim
        # that a candidate was compiled, merged, published or installed.
        contents = self.version(code, name)
        if crlf:
            contents = contents.replace(b"\n", b"\r\n")
        self.build_file.write_bytes(contents)
        row = next(item for item in self.manifest["sources"] if item["path"] == sync.BUILD_CONFIGURATION_PATH)
        row["sha256"] = self.digest(contents)
        if commit is not None:
            self.manifest["upstreamCommit"] = commit
        if tag is not None:
            self.manifest["upstreamTag"] = tag
        self.manifest_file.write_bytes((json.dumps(self.manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        self.manifest = sync.read_manifest(self.repo)
        return contents

    def report(self, changed=None, *, commit=NEW, tag="v0.2.5-alpha.metadata-fixture", download_overrides=None):
        changed = changed or {}
        download_overrides = download_overrides or {}
        rows = self.manifest["sources"] + self.manifest.get("resources", [])
        tree = {"truncated": False, "tree": [
            {"path": item["path"], "type": "blob", "mode": "100644",
             "sha": self.blob(changed[item["path"]]) if item["path"] in changed else (
                 self.blob(self.build_file.read_bytes().replace(b"\r\n", b"\n"))
                 if item["path"] == sync.BUILD_CONFIGURATION_PATH
                 else self.catalog["paths"][item["path"]]["blob"])}
            for item in rows]}
        release = {"id": 701, "tag_name": tag, "prerelease": True, "draft": False,
                   "published_at": "2026-10-11T12:00:00Z",
                   "html_url": f"https://github.com/{sync.UPSTREAM}/releases/tag/{tag}"}
        self.offline_api_calls = []
        self.offline_blob_calls = []

        def api(endpoint):
            self.offline_api_calls.append(endpoint)
            if endpoint == "releases?per_page=100&page=1":
                return [release]
            if endpoint == f"git/ref/tags/{sync.urllib.parse.quote(tag, safe='')}":
                return {"object": {"type": "commit", "sha": commit}}
            if endpoint == f"git/trees/{commit}?recursive=1":
                return tree
            raise AssertionError(f"Unexpected offline GitHub endpoint: {endpoint}")

        def fetch(request_commit, path):
            self.assertEqual(request_commit, commit, "Every downloaded original must use the full resolved commit.")
            self.assertIn(path, changed, "Only changed complete tree blobs may be requested.")
            self.offline_blob_calls.append((request_commit, path))
            return download_overrides.get(path, changed[path])

        controls = (self.manifest_file, self.catalog_file, self.baseline_file, self.build_file)
        before = {path: path.read_bytes() if path.exists() else None for path in controls}
        manifest_before = json.dumps(self.manifest, sort_keys=True)
        try:
            with patch.object(sync, "request_json", side_effect=api), \
                 patch.object(sync, "source_bytes", side_effect=fetch):
                result = sync.check_update(self.repo, self.manifest)
            self.assertEqual(self.offline_api_calls, [
                "releases?per_page=100&page=1",
                f"git/ref/tags/{sync.urllib.parse.quote(tag, safe='')}",
                f"git/trees/{commit}?recursive=1"])
            self.assertEqual(len(self.offline_blob_calls), len(changed))
            self.assertEqual(len(set(self.offline_blob_calls)), len(changed))
            return result
        finally:
            self.assertEqual(json.dumps(self.manifest, sort_keys=True), manifest_before)
            self.assertEqual({path: path.read_bytes() if path.exists() else None for path in controls}, before,
                             "Read-only checks must preserve the complete original, manifest and fixed catalog.")

    def assert_complete_report(self, report, hashes, changed, *, ready, commit=NEW, tag="v0.2.5-alpha.metadata-fixture"):
        rows = self.manifest["sources"] + self.manifest.get("resources", [])
        expected_hashes = {item["path"]: self.digest(changed[item["path"]], item.get("hashNormalization", "lf"))
                           if item["path"] in changed else item["sha256"] for item in rows}
        self.assertEqual(hashes, expected_hashes)
        self.assertEqual(report["upstreamRepository"], sync.UPSTREAM)
        self.assertEqual(report["currentTag"], self.manifest["upstreamTag"])
        self.assertEqual(report["currentCommit"], self.manifest["upstreamCommit"])
        self.assertEqual(report["candidateTag"], tag)
        self.assertEqual(report["candidateCommit"], commit)
        self.assertEqual(report["candidateBranch"], f"windows/upstream-{tag}-{commit[:12]}")
        self.assertEqual(report["releaseUrl"], f"https://github.com/{sync.UPSTREAM}/releases/tag/{tag}")
        self.assertTrue(report["prerelease"])
        self.assertEqual(report["status"], "updateAvailable")
        self.assertEqual(report["featureCoverage"], self.manifest["featureCoverage"])
        self.assertFalse(report["loginApiChanged"])
        self.assertFalse(report["windowsApiContractChanged"])
        self.assertFalse(report["networkBehaviorChanged"])
        self.assertEqual(report["sensitiveReviewFailed"], [])
        self.assertEqual(report["changedAdaptedInputs"], [])
        self.assertEqual(report["sourceDownloads"], len(changed))
        self.assertEqual(report["unchangedBlobInputs"], len(rows) - len(changed))
        self.assertEqual(report["buildCompatibility"]["checkedInputCount"], len(rows))
        self.assertEqual(report["buildCompatibility"]["checkedAdaptedInputCount"], 0)
        self.assertIsNone(report["buildCompatibility"]["adaptedCatalogSha256"])
        self.assertEqual(report["buildCompatibility"]["canonicalCatalogSha256"], sync.CANONICAL_CATALOG_SHA256)
        self.assertEqual(report["buildCompatibility"]["canonicalBaselineCommit"], sync.CANONICAL_BASELINE_COMMIT)
        self.assertEqual(report["buildCompatibility"]["status"], "ready" if ready else "manualAdaptationRequired")
        self.assertEqual(report["autoBuildEligible"], ready)
        self.assertEqual(report["autoPublishEligible"], ready and not report["manualReviewReasons"])
        self.assertEqual(report["changedReusedSources"], [
            item["path"] for item in self.manifest["sources"]
            if item["path"] in changed and item["path"] != sync.BUILD_CONFIGURATION_PATH])
        self.assertEqual(report["changedResources"], [
            item["path"] for item in self.manifest.get("resources", []) if item["path"] in changed])
        self.assertEqual(self.catalog_file.read_bytes(), self.catalog_bytes)

    def assert_metadata(self, report, previous, incoming, old_code, new_code, old_name, new_name):
        self.assertEqual(report["versionMetadataChanges"], [{
            "path": sync.BUILD_CONFIGURATION_PATH,
            "oldVersionCode": old_code, "newVersionCode": new_code,
            "oldVersionName": old_name, "newVersionName": new_name,
            "oldSha256": self.digest(previous), "newSha256": self.digest(incoming),
            "oldBlob": self.blob(previous.replace(b"\r\n", b"\n")),
            "newBlob": self.blob(incoming.replace(b"\r\n", b"\n")),
            "canonicalConfigurationSha256": self.BASELINE_SHA256,
            "unchangedOriginalBodySha256": self.ORIGINAL_BODY_SHA256,
            "comparison": "wholeOriginalBytesExceptTwoSupportedVersionLiterals",
        }])

    def assert_build_manual(self, incoming, message):
        changed = {sync.BUILD_CONFIGURATION_PATH: incoming}
        report, hashes = self.report(changed)
        self.assert_complete_report(report, hashes, changed, ready=False)
        self.assertEqual(report["versionMetadataChanges"], [])
        self.assertEqual(len(report["buildCompatibility"]["reasons"]), 1)
        reason = report["buildCompatibility"]["reasons"][0]
        row = next(item for item in self.manifest["sources"] if item["path"] == sync.BUILD_CONFIGURATION_PATH)
        self.assertEqual((reason["code"], reason["path"], reason["kind"], reason["hashNormalization"]),
                         ("canonicalInputChanged", sync.BUILD_CONFIGURATION_PATH, "buildConfiguration", "lf"))
        self.assertEqual(reason["oldSha256"], row["sha256"])
        self.assertEqual(reason["newSha256"], self.digest(incoming))
        self.assertIn(message, reason["message"])
        return report, hashes

    def test_first_literal_only_release_keeps_complete_report_and_fixed_catalog(self):
        incoming = self.version(428, "0.2.5-alpha.1")
        changed = {sync.BUILD_CONFIGURATION_PATH: incoming}
        report, hashes = self.report(changed)
        self.assert_complete_report(report, hashes, changed, ready=True)
        self.assert_metadata(report, self.baseline, incoming, 427, 428, "0.2.5", "0.2.5-alpha.1")
        self.assertEqual(report["buildCompatibility"]["reasons"], [])
        self.assertEqual(report["manualReviewReasons"], [])
        self.assertEqual(report["changedReusedSources"], [])
        self.assertEqual(report["changedResources"], [])

    def test_accepted_pin_checks_the_next_release_and_current_build_blob(self):
        previous = self.accept_fixture_pin(428, "0.2.5-alpha.1", commit="c" * 40, tag="v0.2.5-alpha.1")
        incoming = self.version(429, "0.2.5-alpha.2")
        changed = {sync.BUILD_CONFIGURATION_PATH: incoming}
        report, hashes = self.report(changed)
        self.assert_complete_report(report, hashes, changed, ready=True)
        self.assert_metadata(report, previous, incoming, 428, 429, "0.2.5-alpha.1", "0.2.5-alpha.2")
        self.accept_fixture_pin(429, "0.2.5-alpha.2", commit="d" * 40, tag="v0.2.5-alpha.2")
        report, hashes = self.report()
        self.assert_complete_report(report, hashes, {}, ready=True)
        self.assertEqual(report["versionMetadataChanges"], [])
        self.assertEqual(report["sourceDownloads"], 0)
        self.assertEqual(self.offline_blob_calls, [])

    def test_higher_code_with_the_same_name_is_metadata_only(self):
        incoming = self.version(428, "0.2.5")
        changed = {sync.BUILD_CONFIGURATION_PATH: incoming}
        report, hashes = self.report(changed)
        self.assert_complete_report(report, hashes, changed, ready=True)
        self.assert_metadata(report, self.baseline, incoming, 427, 428, "0.2.5", "0.2.5")

    def test_equal_code_name_change_or_accepted_version_rollback_stays_manual(self):
        self.assert_build_manual(self.version(427, "0.2.6"), "monotonic versionCode")
        self.accept_fixture_pin(429, "0.2.5-alpha.2", commit="c" * 40, tag="v0.2.5-alpha.2")
        for code, name in ((428, "0.2.5-alpha.1"), (427, "0.2.5"), (429, "0.2.5-alpha.1")):
            with self.subTest(code=code, name=name):
                self.assert_build_manual(self.version(code, name), "monotonic versionCode")
        # An incompatible already-local version is rejected before reading any
        # candidate tree; matching a manifest hash is not a replacement for proof.
        self.accept_fixture_pin(427, "0.2.6")
        with self.assertRaisesRegex(sync.UpdateError, "monotonic versionCode"):
            self.report({sync.BUILD_CONFIGURATION_PATH: self.version(430, "0.2.6")})
        self.assertEqual(len(self.offline_api_calls), 2)
        self.assertEqual(self.offline_blob_calls, [])

    def test_duplicate_expression_and_unbounded_literals_never_get_metadata_admission(self):
        valid = self.version(428, "0.2.5-alpha.1")
        cases = {
            "duplicateCode": (valid + b"\nversionCode = 429\n", "unique supported"),
            "duplicateName": (valid + b'\nversionName = "0.2.5-alpha.2"\n', "unique supported"),
            "codeExpression": (valid.replace(b"versionCode = 428", b"versionCode = 427 + 1", 1), "unique supported"),
            "nameExpression": (valid.replace(b'"0.2.5-alpha.1"', b'"0.2.5" + "-alpha.1"', 1), "unique supported"),
            "nameTemplate": (valid.replace(b'"0.2.5-alpha.1"', b'"0.2.5-$' + b'{suffix}"', 1), "unique supported"),
            "leadingZero": (valid.replace(b"versionCode = 428", b"versionCode = 0428", 1), "unique supported"),
            "zeroCode": (valid.replace(b"versionCode = 428", b"versionCode = 0", 1), "unique supported"),
            "integerOverflow": (valid.replace(b"versionCode = 428", b"versionCode = 2147483648", 1), "supported bounds"),
            "longInteger": (valid.replace(b"versionCode = 428", b"versionCode = " + b"9" * 10000, 1), "unique supported"),
            "longName": (self.version(428, "0.2.5-" + "a" * 129), "supported bounds"),
            "invalidUtf8": (valid + b"\n\xff\n", "valid UTF-8"),
            "byteBudget": (valid + b"x" * sync.MAX_MANIFEST_BYTES, "bounded input budget"),
        }
        for label, (incoming, message) in cases.items():
            with self.subTest(label=label):
                self.assert_build_manual(incoming, message)

    def test_comment_plugin_dependency_scope_and_other_body_bytes_stay_manual(self):
        valid = self.version(428, "0.2.5-alpha.1")
        replacements = {
            "plugin": (b'id("com.android.application")', b'id("different.plugin")'),
            "dependency": (b"implementation(libs.miuix.ui)", b"implementation(libs.miuix.preference)"),
            "scope": (b"    defaultConfig {", b"    changedScope {"),
            "literalIndentation": (b"        versionCode = 428", b"       versionCode = 428"),
        }
        cases = {"comment": valid + b"\n// changed upstream build comment\n",
                 "otherBody": valid + b"\nval extraBuildValue = 1\n"}
        for label, (before, after) in replacements.items():
            self.assertEqual(valid.count(before), 1, f"{label} must change one actual original block.")
            cases[label] = valid.replace(before, after, 1)
        for label, incoming in cases.items():
            with self.subTest(label=label):
                self.assert_build_manual(incoming, "Non-version build configuration bytes changed")

    def test_missing_damaged_baseline_or_untrusted_catalog_fails_before_the_candidate_tree(self):
        cases = {"missing": None, "damaged": self.baseline + b"\n// altered retained original\n",
                 "oversized": self.baseline + b"x" * sync.MAX_MANIFEST_BYTES}
        for label, contents in cases.items():
            with self.subTest(label=label):
                if contents is None:
                    self.baseline_file.unlink()
                else:
                    self.baseline_file.write_bytes(contents)
                with self.assertRaises(sync.UpdateError):
                    self.report({sync.BUILD_CONFIGURATION_PATH: self.version(428, "0.2.5-alpha.1")})
                self.assertEqual(self.offline_api_calls, [
                    "releases?per_page=100&page=1", "git/ref/tags/v0.2.5-alpha.metadata-fixture"])
                self.assertEqual(self.offline_blob_calls, [])
                self.baseline_file.write_bytes(self.baseline)
        self.catalog_file.write_bytes(self.catalog_bytes + b"\n")
        with self.assertRaisesRegex(sync.UpdateError, "Unknown canonical source catalog"):
            self.report({sync.BUILD_CONFIGURATION_PATH: self.version(428, "0.2.5-alpha.1")})
        self.assertEqual(len(self.offline_api_calls), 2)
        self.assertEqual(self.offline_blob_calls, [])

    def test_windows_crlf_whole_baseline_and_checkout_use_the_existing_lf_pin(self):
        self.baseline_file.write_bytes(self.baseline.replace(b"\n", b"\r\n"))
        previous = self.accept_fixture_pin(427, "0.2.5", crlf=True)
        incoming = self.version(428, "0.2.5-alpha.1").replace(b"\n", b"\r\n")
        changed = {sync.BUILD_CONFIGURATION_PATH: incoming}
        report, hashes = self.report(changed)
        self.assert_complete_report(report, hashes, changed, ready=True)
        self.assert_metadata(report, previous, incoming, 427, 428, "0.2.5", "0.2.5-alpha.1")
        detail = report["versionMetadataChanges"][0]
        self.assertEqual(detail["oldBlob"], self.BASELINE_BLOB)
        self.assertNotEqual(detail["newBlob"], self.blob(incoming),
                            "The raw CRLF download is checked first, then the existing LF pin contract applies.")
        self.assertEqual(self.offline_blob_calls, [(NEW, sync.BUILD_CONFIGURATION_PATH)])
        self.assertEqual(self.baseline_file.read_bytes(), self.baseline.replace(b"\n", b"\r\n"))

    def test_valid_metadata_does_not_admit_changed_other_source_or_raw_resource(self):
        source = next(item for item in self.manifest["sources"] if item["path"] != sync.BUILD_CONFIGURATION_PATH)
        resource = next(item for item in self.manifest["resources"]
                        if item.get("hashNormalization") == "raw" and item["path"].endswith(".png"))
        incoming = self.version(428, "0.2.5-alpha.1")
        changed = {sync.BUILD_CONFIGURATION_PATH: incoming,
                   source["path"]: (self.repo / source["path"]).read_bytes() + b"\n// changed upstream original\n",
                   resource["path"]: (self.repo / resource["path"]).read_bytes() + b"\r\nchanged raw resource"}
        report, hashes = self.report(changed)
        self.assert_complete_report(report, hashes, changed, ready=False)
        self.assert_metadata(report, self.baseline, incoming, 427, 428, "0.2.5", "0.2.5-alpha.1")
        self.assertEqual({(reason["code"], reason["path"], reason["kind"])
                          for reason in report["buildCompatibility"]["reasons"]},
                         {("canonicalInputChanged", source["path"], "source"),
                          ("canonicalInputChanged", resource["path"], "resource")})
        self.assertEqual(len(report["buildCompatibility"]["reasons"]), 2)
        self.assertEqual(hashes[resource["path"]], hashlib.sha256(changed[resource["path"]]).hexdigest())
        self.assertNotEqual(hashes[resource["path"]], self.digest(changed[resource["path"]]))

    def test_real_v025_to_b209_build_changes_remain_manual_and_blob_mismatch_is_fatal(self):
        self.assertEqual(len(self.b209), 26211)
        self.assertEqual(self.digest(self.b209, "raw"), self.B209_SHA256)
        self.assertEqual(self.blob(self.b209), self.B209_BLOB)
        self.assertIn(b"versionCode = 449", self.b209)
        self.assertIn(b'versionName = "0.3.4-beta.2"', self.b209)
        self.assertNotEqual(self.version(449, "0.3.4-beta.2"), self.b209,
                            "The real 79-to-b209 delta contains changes beyond the two supported literals.")
        changed = {sync.BUILD_CONFIGURATION_PATH: self.b209}
        report, hashes = self.report(changed, commit=self.B209_COMMIT, tag="v0.3.4-beta.2")
        self.assert_complete_report(report, hashes, changed, ready=False,
                                    commit=self.B209_COMMIT, tag="v0.3.4-beta.2")
        self.assertEqual(report["versionMetadataChanges"], [])
        self.assertEqual(len(report["buildCompatibility"]["reasons"]), 1)
        reason = report["buildCompatibility"]["reasons"][0]
        self.assertEqual((reason["code"], reason["path"], reason["oldSha256"], reason["newSha256"]),
                         ("canonicalInputChanged", sync.BUILD_CONFIGURATION_PATH, self.BASELINE_SHA256, self.B209_SHA256))
        self.assertIn("Non-version build configuration bytes changed", reason["message"])
        self.assertEqual(self.offline_blob_calls, [(self.B209_COMMIT, sync.BUILD_CONFIGURATION_PATH)])
        with self.assertRaisesRegex(sync.UpdateError, "blob identity"):
            self.report(changed, commit=self.B209_COMMIT, tag="v0.3.4-beta.2",
                        download_overrides={sync.BUILD_CONFIGURATION_PATH: self.b209 + b"\ncorrupt download\n"})
        self.assertEqual(self.offline_blob_calls, [(self.B209_COMMIT, sync.BUILD_CONFIGURATION_PATH)])


class AdaptedCompatibilityInventoryTests(unittest.TestCase):
    """Real retained inputs and offline source trees; no network or build runs."""

    # This ignored local note is optional in the real inventory. Keep its exact
    # bytes in the test fixture so a fresh Linux checkout can exercise presence.
    OPTIONAL_README = (
        b'Fixed original source slice: BiliPai v0.2.9, commit a4b77f894d0a2dd26c0b9fc144b8adb88ac05480.\n'
        b'The five complete pure Kotlin files and five original test files are copied without adaptations.\n'
        b"The original module's Apache-2.0 license remains at danmaku-engine/LICENSE; the application retains\n"
        b'its existing GPL-3.0 license. No Android library or new runtime dependency is added.\n'
        b'\n'
        b'Scope: genuine Mode9 BAS grammar, absolute-time scene timeline, independent XML/protobuf parsing,\n'
        b'and retention in the existing bounded Desktop loader. Mode7 text-array parsing remains separate.\n'
        b'Invalid Mode9 scripts are discarded and are never converted to ordinary scrolling text.\n'
        b'Windows document admission retains the existing 16,384-character scene limit, plus bounded\n'
        b'lexical work (4,096 tokens, nesting 64, 256 text/button/path tokens) and compiled object/transition caps.\n'
        b'These Desktop input bounds do not modify the original parser or timeline files.\n'
        b'\n'
        b'NOT IMPLEMENTED BY THIS SLICE: Windows BAS scene painting, perspective/SVG/multi-object drawing,\n'
        b'button hit-testing and target dispatch, special-file range/lifetime indexes, BAS plugin/filter bridge,\n'
        b'or visible BAS playback in the actual native Canvas window. Pure parser tests do not prove any of those.\n'
        b'The canonical baseline remains v0.2.5; this slice does not claim a complete v0.2.9 port.\n'
    )

    @classmethod
    def setUpClass(cls):
        cls.original_repo = Path(__file__).parents[3]
        cls.inventory_bytes = (cls.original_repo / sync.COMPATIBILITY_CATALOG_PATH).read_bytes()
        cls.inventory = json.loads(cls.inventory_bytes)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        # This is a source-only clone-shaped fixture, without .git or ignored
        # docs. Copy the actual catalog/build and all required published leaves.
        for relative in ("desktop/build.gradle.kts", sync.COMPATIBILITY_CATALOG_PATH,
                         sync.CANONICAL_CATALOG_PATH):
            destination = self.repo / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((self.original_repo / relative).read_bytes())
        for archive in self.inventory["archives"]:
            for leaf in archive["files"]:
                destination = self.repo / leaf["path"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((self.original_repo / leaf["path"]).read_bytes())

    def load(self):
        return sync.adapted_compatibility_inputs(self.repo)

    def test_new_v038_and_v040_inputs_reject_changed_and_missing_originals(self):
        for relative in (
            'desktop/upstream-slices/v038-listen-viewmodel/ListenVideoViewModel.kt',
            'desktop/upstream-slices/v040-player-tap/PlayerGestureHandler.kt',
            'desktop/upstream-slices/v041-rss-dynamic/FeedDocumentParser.kt',
            'desktop/upstream-slices/v041-rss-dynamic/FeedHtmlParser.kt',
            'desktop/upstream-slices/v041-rss-dynamic/DynamicAuthorSearchPolicy.kt',
            'desktop/upstream-slices/v041-rss-dynamic/FeedDocumentParserTest.kt',
            'desktop/upstream-slices/v041-rss-dynamic/DynamicAuthorSearchPolicyTest.kt',
            'desktop/upstream-slices/v041-feed-import-title/FeedImportParser.kt',
            'desktop/upstream-slices/v041-feed-import-title/SubscriptionFeedStore.kt',
            'desktop/upstream-slices/v041-feed-import-title/FeedTitleResolver.kt',
            'desktop/upstream-slices/v041-feed-import-title/FeedModels.kt',
            'desktop/upstream-slices/v041-feed-import-title/FeedSourceCatalog.kt',
            'desktop/upstream-slices/v041-dynamic-author-search/DynamicAuthorSearchDialog.kt',
            'desktop/upstream-slices/v041-dynamic-author-search/DynamicScreen.kt',
            'desktop/upstream-slices/v041-dynamic-author-search/DynamicViewModel.kt',
            'desktop/upstream-slices/v041-dynamic-comment-identity/DynamicViewModel.kt',
            'desktop/upstream-slices/v041-dynamic-comment-identity/DynamicCommentSheet.kt',
            'desktop/upstream-slices/v041-player-cover/VideoPlayerSectionPolicy.kt',
            'desktop/upstream-slices/v041-player-cover/VideoPlayerSection.kt',
            'desktop/upstream-slices/v041-player-cover/VideoDetailCoverContentScale.kt',
            'desktop/upstream-slices/v041-player-cover/VideoPlayerCoverPolicyTest.kt',
            'desktop/upstream-slices/v041-player-cover/VideoPlayerSectionPolicyTest.kt',
            'desktop/upstream-slices/v041-video-status-labels/VideoStatusLabels.kt',
            'desktop/upstream-slices/v041-video-status-labels/VideoStatusBadges.kt',
            'desktop/upstream-slices/v041-video-status-labels/ListModels.kt',
            'desktop/upstream-slices/v041-video-status-labels/SpaceModels.kt',
            'desktop/upstream-slices/v041-video-status-labels/SearchModels.kt',
            'desktop/upstream-slices/v041-video-status-labels/FavoriteModels.kt',
            'desktop/upstream-slices/v041-video-status-labels/UserContentRepository.kt',
            'desktop/upstream-slices/v041-video-status-labels/VideoStatusLabelsTest.kt',
            'desktop/upstream-slices/v041-video-status-labels/VideoCooperationMetadataTest.kt',
            'desktop/upstream-slices/v041-video-status-labels/VideoCard.kt',
            'desktop/upstream-slices/v041-video-status-labels/HomeStyleSingleColumnVideoCard.kt',
            'desktop/upstream-slices/v041-video-status-labels/SpaceScreen.kt',
            'desktop/upstream-slices/v041-video-status-labels/SpaceLoadPolicy.kt',
            'desktop/upstream-slices/v041-video-status-labels/WatchLaterRepository.kt',
            'desktop/upstream-slices/v041-video-status-labels/WatchLaterScreen.kt',
        ):
            path = self.repo / relative
            original = path.read_bytes()
            with self.subTest(path=relative, mutation='changed'):
                path.write_bytes(original + b'\n// tampered fixture\n')
                with self.assertRaisesRegex(sync.UpdateError, 'Adapted archive bytes changed'):
                    self.load()
            path.write_bytes(original)
            with self.subTest(path=relative, mutation='missing'):
                path.unlink()
                with self.assertRaisesRegex(sync.UpdateError, 'Incomplete adapted archive'):
                    self.load()
            path.write_bytes(original)
        self.assertEqual(self.load(), self.inventory['inputs'])

    def test_actual_240_originals_load_with_separate_commits_and_nested_live_inputs(self):
        self.assertEqual(sync.adapted_compatibility_inputs(self.original_repo), self.inventory["inputs"])
        entries = self.load()
        self.assertEqual(entries, self.inventory["inputs"])
        self.assertEqual(len(entries), 555)
        self.assertEqual(len({(item["upstreamCommit"], item["path"]) for item in entries}), 544)
        self.assertEqual(len(self.inventory["buildInputRoots"]), 113)
        self.assertEqual(len(self.inventory["archives"]), 117)
        for root, names in {
            "desktop/upstream-slices/v042-player-readability/":
                {"TopControlBar.kt", "PortraitSubtitleOverlay.kt"},
            "desktop/upstream-slices/v042-follow-confirmation/":
                {"ActionRepository.kt", "VideoInteractionStatusCacheTest.kt"},
        }.items():
            with self.subTest(v042Root=root):
                rows = {Path(item["localPath"]).name: item for item in entries
                        if item["localPath"].startswith(root)}
                self.assertEqual(set(rows), names)
                self.assertEqual({item["upstreamCommit"] for item in rows.values()},
                                 {"5c34a82418b33bf88acea01784bb3c6b179d6491"})
                self.assertTrue(all(item["hashNormalization"] == "raw" and
                                    item["referenceOnly"] is False for item in rows.values()))
                archives = [row for row in self.inventory["archives"]
                            if row["directory"] == root.rstrip("/")]
                self.assertEqual(len(archives), 1)
                self.assertEqual({Path(leaf["path"]).name for leaf in archives[0]["files"]},
                                 names | {"manifest.json"})
        # These five exact v041 inputs extend the previous506-input inventory.
        # Whole author policy/tests now share the real dynamic-tabs UI producer.
        v041_root = "desktop/upstream-slices/v041-rss-dynamic/"
        v041_rows = {Path(item["localPath"]).name: item for item in entries
                     if item["localPath"].startswith(v041_root)}
        self.assertEqual(set(v041_rows), {"FeedDocumentParser.kt", "FeedHtmlParser.kt",
            "DynamicAuthorSearchPolicy.kt", "FeedDocumentParserTest.kt", "DynamicAuthorSearchPolicyTest.kt"})
        self.assertEqual({item["upstreamCommit"] for item in v041_rows.values()},
                         {"d18fb20e31adb11bbec9fae6dfce7d705d556217"})
        self.assertTrue(all(item["hashNormalization"] == "raw" for item in v041_rows.values()))
        self.assertEqual({name for name, item in v041_rows.items() if item["referenceOnly"]},
                         set())
        v041_archives = [row for row in self.inventory["archives"]
                         if row["directory"] == v041_root.rstrip("/")]
        self.assertEqual(len(v041_archives), 1)
        self.assertEqual({Path(leaf["path"]).name for leaf in v041_archives[0]["files"]},
                         set(v041_rows) | {"manifest.json"})
        complete_root = "desktop/upstream-slices/v041-feed-import-title/"
        complete_rows = {Path(item["localPath"]).name: item for item in entries
                         if item["localPath"].startswith(complete_root)}
        self.assertEqual(set(complete_rows), {"FeedImportParser.kt", "SubscriptionFeedStore.kt",
            "FeedTitleResolver.kt", "FeedModels.kt", "FeedSourceCatalog.kt"})
        self.assertEqual({item["upstreamCommit"] for item in complete_rows.values()},
                         {"d18fb20e31adb11bbec9fae6dfce7d705d556217"})
        self.assertTrue(all(item["hashNormalization"] == "raw" and item["referenceOnly"] is False
                            for item in complete_rows.values()))
        complete_archives = [row for row in self.inventory["archives"]
                             if row["directory"] == complete_root.rstrip("/")]
        self.assertEqual(len(complete_archives), 1)
        self.assertEqual({Path(leaf["path"]).name for leaf in complete_archives[0]["files"]},
                         set(complete_rows) | {"manifest.json"})
        author_root = "desktop/upstream-slices/v041-dynamic-author-search/"
        author_rows = {Path(item["localPath"]).name: item for item in entries
                       if item["localPath"].startswith(author_root)}
        self.assertEqual(set(author_rows), {"DynamicAuthorSearchDialog.kt", "DynamicScreen.kt", "DynamicViewModel.kt"})
        self.assertEqual({name for name, item in author_rows.items() if item["referenceOnly"]},
                         {"DynamicScreen.kt", "DynamicViewModel.kt"})
        self.assertTrue(all(item["upstreamCommit"] == "d18fb20e31adb11bbec9fae6dfce7d705d556217"
                            and item["hashNormalization"] == "raw" for item in author_rows.values()))
        author_archives = [row for row in self.inventory["archives"] if row["directory"] == author_root.rstrip("/")]
        self.assertEqual(len(author_archives), 1)
        self.assertEqual({Path(leaf["path"]).name for leaf in author_archives[0]["files"]},
                         set(author_rows) | {"manifest.json"})
        identity_root = "desktop/upstream-slices/v041-dynamic-comment-identity/"
        identity_rows = {Path(item["localPath"]).name: item for item in entries
                         if item["localPath"].startswith(identity_root)}
        self.assertEqual(set(identity_rows), {"DynamicViewModel.kt", "DynamicCommentSheet.kt"})
        self.assertTrue(all(item["upstreamCommit"] == "d18fb20e31adb11bbec9fae6dfce7d705d556217"
                            and item["hashNormalization"] == "raw" and item["referenceOnly"] is False
                            for item in identity_rows.values()))
        identity_archives = [row for row in self.inventory["archives"] if row["directory"] == identity_root.rstrip("/")]
        self.assertEqual(len(identity_archives), 1)
        self.assertEqual({Path(leaf["path"]).name for leaf in identity_archives[0]["files"]},
                         set(identity_rows) | {"manifest.json"})
        cover_root = "desktop/upstream-slices/v041-player-cover/"
        cover_rows = {Path(item["localPath"]).name: item for item in entries
                      if item["localPath"].startswith(cover_root)}
        self.assertEqual(set(cover_rows), {"VideoPlayerSectionPolicy.kt", "VideoPlayerSection.kt",
            "VideoDetailCoverContentScale.kt", "VideoPlayerCoverPolicyTest.kt", "VideoPlayerSectionPolicyTest.kt"})
        self.assertTrue(all(item["upstreamCommit"] == "d18fb20e31adb11bbec9fae6dfce7d705d556217"
                            and item["hashNormalization"] == "raw" and item["partialReuse"] is True
                            for item in cover_rows.values()))
        self.assertEqual({name for name, item in cover_rows.items() if item["referenceOnly"]},
                         {"VideoDetailCoverContentScale.kt"})
        cover_archives = [row for row in self.inventory["archives"] if row["directory"] == cover_root.rstrip("/")]
        self.assertEqual(len(cover_archives), 1)
        self.assertEqual({Path(leaf["path"]).name for leaf in cover_archives[0]["files"]},
                         set(cover_rows) | {"manifest.json"})
        status_root = "desktop/upstream-slices/v041-video-status-labels/"
        status_rows = {Path(item["localPath"]).name: item for item in entries
                       if item["localPath"].startswith(status_root)}
        self.assertEqual(set(status_rows), {"VideoStatusLabels.kt", "VideoStatusBadges.kt", "ListModels.kt",
            "SpaceModels.kt", "SearchModels.kt", "FavoriteModels.kt", "UserContentRepository.kt",
            "VideoStatusLabelsTest.kt", "VideoCooperationMetadataTest.kt", "VideoCard.kt",
            "HomeStyleSingleColumnVideoCard.kt", "SpaceScreen.kt", "SpaceLoadPolicy.kt",
            "WatchLaterRepository.kt", "WatchLaterScreen.kt"})
        self.assertTrue(all(item["upstreamCommit"] == "d18fb20e31adb11bbec9fae6dfce7d705d556217"
                            and item["hashNormalization"] == "raw" and item["kind"] == "adaptedSource"
                            for item in status_rows.values()))
        # Eight whole sources are emitted from the archive; seven are containment or hunk sources only.
        self.assertEqual({name for name, item in status_rows.items() if not item["referenceOnly"]},
                         {"VideoStatusLabels.kt", "VideoStatusBadges.kt", "ListModels.kt", "SpaceModels.kt", "SearchModels.kt", "FavoriteModels.kt",
                          "VideoStatusLabelsTest.kt", "VideoCooperationMetadataTest.kt"})
        self.assertTrue(all(item["partialReuse"] is True and item["usedScope"]
                            for item in status_rows.values() if item["referenceOnly"]))
        status_archives = [row for row in self.inventory["archives"] if row["directory"] == status_root.rstrip("/")]
        self.assertEqual(len(status_archives), 1)
        self.assertEqual({Path(leaf["path"]).name for leaf in status_archives[0]["files"]},
                         set(status_rows) | {"manifest.json"})
        self.assertIn(status_root.rstrip("/"), self.inventory["buildInputRoots"])
        for root, count in (("v034-favorite-content-errors", 1), ("v034-on-demand", 5),
                            ("v034-detail-bootstrap", 4), ("v034-metadata-related", 2),
                            ("v034-text-lyrics", 3), ("v034-next-watch", 1),
                            ("v034-pgc-initial-detail", 2), ("v034-next-watch-ui", 3),
                            ("v035-autoplay-continuation", 1), ("v035-favorite-cover", 5),
                            ("v035-story", 6), ("v035-story-pager", 1), ("v035-dynamic-link", 7), ("v035-ambient", 4), ("v035-scrollable-lists", 1), ("v035-dynamic-preview", 5), ("v036-comment-route", 2), ("v036-video-comment-cache", 5), ("v036-dynamic-comment-cache", 2), ("v036-comment-viewport", 3)):
            directory = "desktop/upstream-slices/" + root
            with self.subTest(root=root):
                self.assertIn(directory, self.inventory["buildInputRoots"])
                archives = [row for row in self.inventory["archives"] if row["directory"] == directory]
                self.assertEqual(len(archives), 1)
                metadata_count = 2 if root == "v034-pgc-initial-detail" else 1
                self.assertEqual(len(archives[0]["files"]), count + metadata_count)  # originals + exact manifests
                rows = [item for item in entries if item["localPath"].startswith(directory + "/")]
                self.assertEqual(len(rows), count)
                self.assertTrue(all(item["hashNormalization"] == "raw" for item in rows))
                expected_references = ({"BasePlayerViewModel.kt"}
                                       if root == "v034-pgc-initial-detail" else
                                       {"VideoPlayerSection.kt"} if root == "v034-next-watch-ui" else
                                       {"DynamicModels.kt"} if root == "v034-text-lyrics" else
                                       {"DynamicComponents.kt"} if root == "v035-dynamic-link" else
                                       {"README.md"} if root == "v035-ambient" else
                                       {"HomeFeedSettingsSection.kt"} if root == "v035-dynamic-preview" else set())
                self.assertEqual({Path(item["localPath"]).name for item in rows if item["referenceOnly"]},
                                 expected_references)
                self.assertTrue(all(item["referenceOnly"] is False for item in rows
                                    if Path(item["localPath"]).name not in expected_references))
        dynamic_root = "desktop/upstream-slices/v035-dynamic-link/"
        dynamic_rows = {Path(item["localPath"]).name: item for item in entries
                        if item["localPath"].startswith(dynamic_root)}
        dynamic_names = {"DynamicModels.kt", "DynamicCard.kt", "DynamicComponents.kt", "ForwardedContent.kt",
                         "DynamicLinkCard.kt", "DynamicLinkCardPolicy.kt", "PageAwareAsyncImage.kt"}
        self.assertEqual(set(dynamic_rows), dynamic_names)
        dynamic_manifest = json.loads((self.repo / dynamic_root / "manifest.json").read_bytes())
        self.assertEqual(dynamic_manifest["upstreamCommit"], "46eaf277e83512061785be9759d1f07aba3c4839")
        self.assertEqual(dynamic_manifest["canonicalBaselineCommit"], "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
        dynamic_source_pins = {source["file"]: source for source in dynamic_manifest["sources"]}
        self.assertEqual(set(dynamic_source_pins), dynamic_names)
        dynamic_references = {"DynamicComponents.kt"}
        whole_runtime_names = {"DynamicModels.kt", "DynamicCard.kt", "ForwardedContent.kt", "DynamicLinkCard.kt", "DynamicLinkCardPolicy.kt", "PageAwareAsyncImage.kt"}
        dynamic_raw_by_name = {}
        for filename, row in dynamic_rows.items():
            with self.subTest(dynamicLinkOriginal=filename):
                pin = dynamic_source_pins[filename]
                self.assertEqual(row["upstreamCommit"], dynamic_manifest["upstreamCommit"])
                self.assertEqual(row["path"], pin["originalPath"])
                self.assertEqual(row["referenceOnly"], filename in dynamic_references)
                self.assertIs(row["partialReuse"], False)
                self.assertEqual(pin["referenceOnly"], row["referenceOnly"])
                self.assertEqual(pin["partialReuse"], row["partialReuse"])
                raw = (self.repo / row["localPath"]).read_bytes()
                self.assertEqual(len(raw), pin["bytesRaw"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), row["sha256"])
                self.assertEqual(sync.blob_oid(raw), row["blob"])
                self.assertEqual(row["sha256"], pin["sha256Raw"])
                self.assertEqual(row["blob"], pin["gitBlob"])
                dynamic_raw_by_name[filename] = raw
        self.assertEqual({filename for filename, row in dynamic_rows.items()
                          if not row["referenceOnly"] and not row["partialReuse"]}, whole_runtime_names)
        for filename in dynamic_references:
            self.assertEqual(dynamic_rows[filename]["usedScope"],
                             "Complete fixed original comparison/dependency evidence only; not emitted by this slice.")
        for filename in whole_runtime_names - {"PageAwareAsyncImage.kt", "DynamicCard.kt", "ForwardedContent.kt"}:
            self.assertEqual(dynamic_rows[filename]["usedScope"], "Complete original file in the existing sole output owner.")
        self.assertEqual(dynamic_rows["PageAwareAsyncImage.kt"]["usedScope"],
                         "Complete original page-aware request/cache-key and static replay consumer with finite Windows Coil/context/Android-drawable adaptations.")
        # The a69 DTO remains a validated full input, without claiming it as the
        # effective runtime DTO. Both actual lyric originals remain effective.
        lyrics_root = "desktop/upstream-slices/v034-text-lyrics/"
        lyrics_rows = {Path(item["localPath"]).name: item for item in entries
                       if item["localPath"].startswith(lyrics_root)}
        self.assertEqual(set(lyrics_rows), {"DynamicModels.kt", "LyricsParser.kt", "LyricsDisplayPolicy.kt"})
        historical_dto = lyrics_rows["DynamicModels.kt"]
        self.assertEqual(historical_dto["upstreamCommit"], "a69d4181a073aa3207def3fca43318dcf7ab2c70")
        self.assertIs(historical_dto["referenceOnly"], True)
        self.assertEqual(historical_dto["usedScope"],
                         "Historical full original validation input for the existing text/lyrics producer and complete inverse to the new pinned DTO; not an effective runtime DTO output.")
        self.assertTrue(all(lyrics_rows[filename]["referenceOnly"] is False for filename in
                            ("LyricsParser.kt", "LyricsDisplayPolicy.kt")))
        historical_raw = (self.repo / historical_dto["localPath"]).read_bytes()
        self.assertEqual(len(historical_raw), 52499)
        self.assertEqual(hashlib.sha256(historical_raw).hexdigest(), historical_dto["sha256"])
        self.assertEqual(sync.blob_oid(historical_raw), historical_dto["blob"])
        dto_recipe = dynamic_manifest["dynamicModel"]
        self.assertEqual(dto_recipe["previousCommit"], historical_dto["upstreamCommit"])
        self.assertEqual(dto_recipe["previousSource"]["sha256"], historical_dto["sha256"])
        self.assertEqual(dto_recipe["effectiveCommit"], dynamic_manifest["upstreamCommit"])
        self.assertIs(dto_recipe["fullOriginal"], True)
        self.assertEqual(dto_recipe["output"], "com/android/purebilibili/data/model/response/DynamicModels.kt")
        self.assertEqual(len(dto_recipe["edits"]), 1)
        replacement = dto_recipe["edits"][0]
        self.assertEqual(replacement["offsetBytes"], 0)
        self.assertEqual(replacement["before"].encode("utf-8"), historical_raw)
        self.assertEqual(replacement["after"].encode("utf-8"), dynamic_raw_by_name["DynamicModels.kt"])
        self.assertEqual(replacement["beforeWholeSha256"], historical_dto["sha256"])
        self.assertEqual(replacement["afterWholeSha256"], dynamic_rows["DynamicModels.kt"]["sha256"])
        dynamic_consumers = [
            "complete-original-link-mapping-identity-dedup-and-target-dispatch",
            "complete-original-opus-link-card-consumer",
            "complete-original-feed-additional-link-card-consumer",
            "complete-original-reserve-override-and-link-card-slot-closure",
        ]
        self.assertEqual(dynamic_rows["DynamicCard.kt"]["reusedConsumers"], dynamic_consumers)
        self.assertEqual([consumer["name"] for consumer in dynamic_manifest["card"]["consumers"]], dynamic_consumers)
        self.assertEqual(dynamic_rows["DynamicCard.kt"]["usedScope"],
                         "Complete original DynamicCard in the existing sole Windows producer, with recorded platform adaptations.")
        self.assertEqual(dynamic_rows["ForwardedContent.kt"]["usedScope"],
                         "Complete original ForwardedContent in the existing sole Windows producer, with recorded platform adaptations.")
        original_card = sync.normalized_source(dynamic_raw_by_name["DynamicCard.kt"])
        for consumer in dynamic_manifest["card"]["consumers"]:
            with self.subTest(dynamicCardConsumer=consumer["name"]):
                start = consumer["offsetBytesLF"]
                selected = original_card[start:start + consumer["bytesLF"]]
                self.assertEqual(selected, consumer["original"].encode("utf-8"))
                self.assertEqual(hashlib.sha256(selected).hexdigest(), consumer["sha256LF"])
        self.assertEqual(dynamic_manifest["pageAware"]["source"], "PageAwareAsyncImage.kt")
        self.assertEqual(dynamic_manifest["pageAware"]["beforeSha256LF"],
                         dynamic_rows["PageAwareAsyncImage.kt"]["sha256"])
        retired_dynamic_direct = [
            "app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicAdditionalCardPolicy.kt",
            "app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicOpusLinkCard.kt",
        ]
        self.assertEqual(dynamic_manifest["retiredDirectSources"], retired_dynamic_direct)
        actual_canonical_dynamic = sync.read_manifest(self.original_repo)
        for original_path in retired_dynamic_direct:
            old_row = next(source for source in actual_canonical_dynamic["sources"] if source["path"] == original_path)
            self.assertEqual(old_row["mode"], "selected", "The sole dynamic producer owns each runtime declaration once")
        self.assertIn('inputs.file("tools/v035_dynamic_link.py")',
                      (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        preview_root = "desktop/upstream-slices/v035-dynamic-preview/"
        preview_manifest = json.loads((self.repo / preview_root / "manifest.json").read_bytes())
        self.assertEqual(preview_manifest["upstreamCommit"], dynamic_manifest["upstreamCommit"])
        self.assertTrue(dynamic_manifest["card"]["wholeOriginalActive"])
        self.assertTrue(dynamic_manifest["card"]["body"]["historicalPartialBodyOnly"])
        self.assertEqual(len(preview_manifest["wholeRuntimeUnits"]), 3)
        self.assertTrue(all(preview_manifest["sharedTopicDeclarationsExactlyEqualToV035"].values()))
        preview_originals = {row["archiveFile"]: row for row in preview_manifest["sources"]}
        self.assertEqual(set(preview_originals), {"DynamicSettingsStore.kt", "VideoCardSharedElementContext.kt",
            "DynamicCommentPreviewPolicy.kt", "DynamicRichTextPolicy.kt", "HomeFeedSettingsSection.kt"})
        for filename, row in preview_originals.items():
            raw = (self.repo / preview_root / filename).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row["sha256"])
            self.assertEqual(sync.blob_oid(raw), row["gitBlob"])
            self.assertNotIn("saved", row)
        context_path = "app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardSharedElementContext.kt"
        self.assertEqual(next(row for row in actual_canonical_dynamic["sources"] if row["path"] == context_path)["mode"], "selected")
        self.assertIn('inputs.file("tools/v035_dynamic_preview.py")', (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        route_root = "desktop/upstream-slices/v036-comment-route/"
        route_manifest = json.loads((self.repo / route_root / "manifest.json").read_bytes())
        self.assertEqual(route_manifest["fixedUpstreamCommit"], "3608e0347ba3314ac74190d724d78598fdbad879")
        route_rows = {Path(row["localPath"]).name: row for row in entries if row["localPath"].startswith(route_root)}
        self.assertEqual(set(route_rows), {"CommentRepository.kt", "CommentReadRouteMemo.kt"})
        for original in route_manifest["files"]:
            row = route_rows[original["filename"]]
            self.assertEqual(row["path"], original["path"])
            self.assertEqual(row["upstreamCommit"], route_manifest["fixedUpstreamCommit"])
            self.assertEqual(row["sha256"], original["sha256Bytes"])
            self.assertEqual(row["blob"], original["gitBlob"])
            self.assertFalse(row["referenceOnly"])
        self.assertIn('inputs.file("tools/v036_comment_route.py")', (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        cache_root = "desktop/upstream-slices/v036-video-comment-cache/"
        cache_manifest = json.loads((self.repo / cache_root / "manifest.json").read_bytes())
        self.assertEqual(cache_manifest["fixedUpstreamCommit"], "3608e0347ba3314ac74190d724d78598fdbad879")
        cache_rows = {Path(row["localPath"]).name: row for row in entries if row["localPath"].startswith(cache_root)}
        self.assertEqual(len(cache_rows), 5)
        for original in cache_manifest["files"]:
            row = cache_rows[original["filename"]]
            self.assertEqual(row["path"], original["path"])
            self.assertEqual(row["sha256"], original["sha256Bytes"])
            self.assertEqual(row["blob"], original["gitBlob"])
            self.assertFalse(row["referenceOnly"])
        self.assertIn('inputs.file("tools/v036_video_comment_cache.py")', (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        scrollable_root = "desktop/upstream-slices/v035-scrollable-lists/"
        scrollable_manifest = json.loads((self.repo / scrollable_root / "manifest.json").read_bytes())
        scrollable_raw = (self.repo / scrollable_root / "AppScrollableLists.kt").read_bytes()
        self.assertEqual(hashlib.sha256(scrollable_raw).hexdigest(), scrollable_manifest["rawSha256"])
        self.assertEqual(sync.blob_oid(scrollable_raw), scrollable_manifest["gitBlob"])
        self.assertEqual(scrollable_manifest["mode"], "whole-original-direct")
        self.assertEqual(scrollable_manifest["adaptations"], [])
        self.assertIn('inputs.dir("upstream-slices/v035-scrollable-lists")',
                      (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        ambient_root = "desktop/upstream-slices/v035-ambient/"
        ambient_rows = {Path(item["localPath"]).name: item for item in entries
                        if item["localPath"].startswith(ambient_root)}
        self.assertEqual(set(ambient_rows), {"AmbientFramePolicy.kt", "AmbientFrameController.kt",
                                           "AmbientPlayerBinding.kt", "README.md"})
        ambient_manifest = json.loads((self.repo / ambient_root / "manifest.json").read_bytes())
        self.assertEqual(ambient_manifest["upstreamCommit"], "46eaf277e83512061785be9759d1f07aba3c4839")
        for item in ambient_manifest["entries"]:
            with self.subTest(ambientOriginal=item["archiveFile"]):
                raw = (self.repo / ambient_root / item["archiveFile"]).read_bytes()
                self.assertEqual(hashlib.sha256(raw).hexdigest(), item["rawSha256"])
                self.assertEqual(sync.blob_oid(raw), item["gitBlob"])
                self.assertEqual(ambient_rows[item["archiveFile"]]["referenceOnly"], item["referenceOnly"])
                if item["referenceOnly"]:
                    continue
                lines = sync.normalized_source(raw).decode("utf-8").splitlines(keepends=True)
                recipe = item["recipe"]
                self.assertEqual(recipe["sourceSha256LF"], hashlib.sha256("".join(lines).encode()).hexdigest())
                self.assertEqual(recipe["mode"], "policy-extract")
                cursor = 0
                generated = []
                for edit in recipe["edits"]:
                    self.assertEqual(edit["start"], cursor)
                    original = "".join(lines[edit["start"]:edit["end"]])
                    self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), edit["beforeSha256LF"])
                    generated.append(original if edit["after"] is None else edit["after"])
                    cursor = edit["end"]
                self.assertEqual(cursor, len(lines))
                self.assertEqual(hashlib.sha256("".join(generated).encode()).hexdigest(), recipe["outputSha256LF"])
                old_row = next(source for source in actual_canonical_dynamic["sources"] if source["path"] == item["path"])
                self.assertEqual(old_row["mode"], "selected", "The sole ambient producer owns the complete latest declaration")
        self.assertIn('inputs.file("tools/v035_ambient.py")',
                      (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        story_root = "desktop/upstream-slices/v035-story/"
        story_rows = {Path(item["localPath"]).name: item for item in entries
                      if item["localPath"].startswith(story_root)}
        story_names = {"StoryRepository.kt", "StoryFeedPolicy.kt", "StoryViewModel.kt",
                       "StoryModels.kt", "ApiClient.kt", "StoryScreen.kt"}
        self.assertEqual(set(story_rows), story_names)
        story_manifest = json.loads((self.repo / story_root / "manifest.json").read_bytes())
        self.assertEqual(story_manifest["upstreamCommit"], "46eaf277e83512061785be9759d1f07aba3c4839")
        story_source_pins = {source["file"]: source for source in story_manifest["sources"]}
        self.assertEqual(set(story_source_pins), story_names)
        story_raw_by_name = {}
        for filename, row in story_rows.items():
            with self.subTest(storyOriginal=filename):
                self.assertEqual(row["upstreamCommit"], story_manifest["upstreamCommit"])
                self.assertIs(row["referenceOnly"], False)
                self.assertEqual(row["partialReuse"], filename in {"StoryRepository.kt", "ApiClient.kt"})
                raw = (self.repo / row["localPath"]).read_bytes()
                pin = story_source_pins[filename]
                self.assertEqual(row["path"], pin["originalPath"])
                self.assertEqual(len(raw), pin["bytes"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), row["sha256"])
                self.assertEqual(sync.blob_oid(raw), row["blob"])
                self.assertEqual(row["sha256"], pin["sha256"])
                self.assertEqual(row["blob"], pin["gitBlob"])
                story_raw_by_name[filename] = raw
        self.assertEqual(story_rows["StoryRepository.kt"]["reusedMethods"], ["getStoryFeed"])
        self.assertEqual(story_rows["StoryRepository.kt"]["usedScope"], "complete getStoryFeed method only")
        self.assertEqual(story_rows["ApiClient.kt"]["reusedDeclarations"], ["StoryApi"])
        self.assertEqual(story_rows["ApiClient.kt"]["usedScope"], "complete StoryApi interface only")
        for filename in story_names - {"StoryRepository.kt", "ApiClient.kt"}:
            self.assertEqual(story_rows[filename]["usedScope"], "complete original file")
            projection = story_manifest["projections"][filename]
            self.assertEqual(projection["source"], filename)
            if filename in {"StoryModels.kt", "StoryFeedPolicy.kt"}:
                self.assertEqual(projection["mode"], "completeRaw")
                self.assertEqual(projection["emittedSha256"], story_rows[filename]["sha256"])
            else:
                self.assertEqual(projection["originalSha256LF"], story_rows[filename]["sha256"])
        # Method/declaration selections use original Unicode-character offsets;
        # the Pager's complete consumer selections explicitly use byte offsets.
        for filename, field, digest_field in (
            ("StoryRepository.kt", "originalMethod", "originalMethodSha256LF"),
            ("ApiClient.kt", "originalDeclaration", "originalDeclarationSha256LF"),
        ):
            projection = story_manifest["projections"][filename]
            original = sync.normalized_source(story_raw_by_name[filename]).decode("utf-8")
            selected = projection[field]
            start = projection["originalOffsetChars"]
            self.assertEqual(original[start:start + len(selected)], selected)
            self.assertEqual(hashlib.sha256(selected.encode("utf-8")).hexdigest(), projection[digest_field])
        required_pager_ports = ["recommendationsManagedExternally", "recommendationLoadInProgress",
                                "recommendationLoadError", "onLoadMoreRecommendations"]
        self.assertEqual(story_manifest["pagerRequiredPorts"], required_pager_ports)
        pager_root = "desktop/upstream-slices/v035-story-pager/"
        pager_row = next(item for item in entries if item["localPath"] == pager_root + "PortraitVideoPager.kt")
        self.assertEqual(pager_row["upstreamCommit"], story_manifest["upstreamCommit"])
        self.assertIs(pager_row["referenceOnly"], False)
        self.assertIs(pager_row["partialReuse"], True)
        pager_manifest = json.loads((self.repo / pager_root / "manifest.json").read_bytes())
        self.assertEqual(pager_manifest["upstreamCommit"], story_manifest["upstreamCommit"])
        self.assertEqual(pager_manifest["canonicalBaselineCommit"], "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
        pager_raw = (self.repo / pager_row["localPath"]).read_bytes()
        self.assertEqual(len(pager_raw), 206277, "Whole v035 Pager is retained; only nine consumers are reused")
        self.assertEqual(len(pager_raw), pager_manifest["source"]["bytesRaw"])
        self.assertEqual(hashlib.sha256(pager_raw).hexdigest(), pager_row["sha256"])
        self.assertEqual(sync.blob_oid(pager_raw), pager_row["blob"])
        self.assertEqual(pager_row["sha256"], pager_manifest["source"]["sha256Raw"])
        self.assertEqual(pager_row["blob"], pager_manifest["source"]["gitBlob"])
        pager_consumers = [
            "four-original-external-owner-ports", "latest-original-loadmore-callback",
            "original-applied-list-and-empty-batch-state",
            "complete-original-parent-list-effect-preserves-external-order",
            "original-watchlater-append-owner-guard", "original-discovery-owner-guard",
            "original-private-home-tail-owner-guard",
            "complete-original-external-near-tail-loading-error-empty-batch-consumer",
            "complete-original-tail-error-and-three-empty-batch-retry-ui",
        ]
        self.assertEqual(pager_row["reusedConsumers"], pager_consumers)
        self.assertEqual([consumer["name"] for consumer in pager_manifest["reusedConsumers"]], pager_consumers)
        self.assertEqual(pager_row["usedScope"],
                         "Nine complete original external-recommendation consumer slices; not a full v035 Pager upgrade.")
        pager_lf = sync.normalized_source(pager_raw)
        for consumer in pager_manifest["reusedConsumers"]:
            with self.subTest(pagerConsumer=consumer["name"]):
                start = consumer["offsetBytesLF"]
                selected = pager_lf[start:start + consumer["bytesLF"]]
                self.assertEqual(selected, consumer["original"].encode("utf-8"))
                self.assertEqual(hashlib.sha256(selected).hexdigest(), consumer["sha256LF"])
        build_source = (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('inputs.file("tools/v035_story.py")', build_source)
        self.assertIn('inputs.file("tools/v035_story_pager.py")', build_source)
        story_canonical = sync.read_manifest(self.original_repo)
        for original_path in (
            "core-data/src/main/java/com/android/purebilibili/data/model/response/StoryModels.kt",
            "app/src/main/java/com/android/purebilibili/feature/story/StoryFeedPolicy.kt",
        ):
            old_source = next(source for source in story_canonical["sources"] if source["path"] == original_path)
            self.assertEqual(old_source["mode"], "selected", "The sole Story producer replaces the old direct declaration")
        cover_root = "desktop/upstream-slices/v035-favorite-cover/"
        cover_rows = {Path(item["localPath"]).name: item for item in entries
                      if item["localPath"].startswith(cover_root)}
        self.assertEqual(set(cover_rows), {"FavoriteFolderCoverCache.kt", "FavoriteFolderCoverPrefetchPolicy.kt",
                                           "FavoriteFolderCardList.kt", "ListViewModel.kt", "CommonListScreen.kt"})
        cover_manifest = json.loads((self.repo / cover_root / "manifest.json").read_bytes())
        self.assertEqual(cover_manifest["upstreamCommit"], "46eaf277e83512061785be9759d1f07aba3c4839")
        self.assertEqual(cover_manifest["canonicalBaselineCommit"], "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
        cover_source_pins = {source["file"]: source for source in cover_manifest["sources"]}
        cover_raw_by_name = {}
        for filename, row in cover_rows.items():
            with self.subTest(coverOriginal=filename):
                self.assertEqual(row["upstreamCommit"], cover_manifest["upstreamCommit"])
                self.assertIs(row["referenceOnly"], False)
                self.assertEqual(row["partialReuse"], filename in {"ListViewModel.kt", "CommonListScreen.kt"})
                raw = (self.repo / row["localPath"]).read_bytes()
                self.assertEqual(len(raw), cover_source_pins[filename]["bytesRaw"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), row["sha256"])
                self.assertEqual(sync.blob_oid(raw), row["blob"])
                self.assertEqual(row["sha256"], cover_source_pins[filename]["sha256Raw"])
                cover_raw_by_name[filename] = raw
        cover_methods = ["fetchFolders", "applyCachedFavoriteFolderCovers", "ensureFavoriteFolderCovers",
                         "fetchFavoriteFolderCovers", "applyFavoriteFolderCovers"]
        self.assertEqual(cover_rows["ListViewModel.kt"]["reusedMethods"], cover_methods)
        cover_method_pin = cover_manifest["partialVmReuse"]
        self.assertEqual(cover_method_pin["methods"], cover_methods)
        cover_method_raw = sync.normalized_source(cover_raw_by_name["ListViewModel.kt"])
        start = cover_method_pin["offsetBytesLF"]
        cover_method_raw = cover_method_raw[start:start + cover_method_pin["bytesLF"]]
        self.assertEqual(hashlib.sha256(cover_method_raw).hexdigest(), cover_method_pin["sha256LF"])
        self.assertEqual(cover_rows["CommonListScreen.kt"]["reusedSlice"], "complete original onPrefetchCovers callback only")
        cover_callback_pin = cover_manifest["partialUiReuse"]
        callback_raw = sync.normalized_source(cover_raw_by_name["CommonListScreen.kt"])
        start = cover_callback_pin["offsetBytesLF"]
        callback_raw = callback_raw[start:start + cover_callback_pin["bytesLF"]]
        self.assertEqual(hashlib.sha256(callback_raw).hexdigest(), cover_callback_pin["sha256LF"])
        self.assertIn('inputs.file("tools/v035_favorite_cover.py")',
                      (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        canonical_manifest = sync.read_manifest(self.original_repo)
        self.assertEqual(canonical_manifest["upstreamCommit"], "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
        canonical_card = next(source for source in canonical_manifest["sources"] if source["path"] ==
                              "app/src/main/java/com/android/purebilibili/feature/list/FavoriteFolderCardList.kt")
        self.assertEqual(canonical_card["mode"], "selected", "The sole Favorites producer owns the v035 card declaration")
        autoplay_row = next(item for item in entries if item["localPath"] ==
                            "desktop/upstream-slices/v035-autoplay-continuation/VideoPlaybackViewModel.kt")
        self.assertIs(autoplay_row["referenceOnly"], False)
        self.assertIs(autoplay_row["partialReuse"], True)
        reused_methods = ["onPlaybackStateChanged", "playNextRecommended", "playNextFromPlaylist",
                          "playNextInOrder", "playNextPageOrSeason", "playNextPageOrRecommended", "switchPage"]
        self.assertEqual(autoplay_row["reusedMethods"], reused_methods)
        self.assertEqual(autoplay_row["upstreamCommit"], "46eaf277e83512061785be9759d1f07aba3c4839")
        autoplay_raw = (self.repo / autoplay_row["localPath"]).read_bytes()
        self.assertEqual(len(autoplay_raw), 415045, "The complete v035 VM is retained, not claimed as a full runtime VM")
        self.assertEqual(hashlib.sha256(autoplay_raw).hexdigest(), autoplay_row["sha256"])
        self.assertEqual(sync.blob_oid(autoplay_raw), autoplay_row["blob"])
        autoplay_manifest = json.loads((self.repo / "desktop/upstream-slices/v035-autoplay-continuation/"
                                        "manifest.json").read_bytes())
        self.assertEqual([method["name"] for method in autoplay_manifest["originalMethods"]], reused_methods)
        self.assertEqual(autoplay_manifest["originalVm"]["sha256"], autoplay_row["sha256"])
        self.assertIn('inputs.file("tools/v035_autoplay_continuation.py")',
                      (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        audio_row = next(item for item in entries if item["localPath"] ==
                         "desktop/upstream-slices/v034-pgc-initial-detail/BangumiPlayerViewModel.kt")
        self.assertIs(audio_row["partialReuse"], True)
        self.assertEqual(audio_row["reusedMethods"], ["downloadAudio"])
        raw_vm = (self.repo / audio_row["localPath"]).read_bytes()
        self.assertEqual(len(raw_vm), 65157, "The complete a69 VM remains retained; only one method is reused")
        self.assertEqual(hashlib.sha256(raw_vm).hexdigest(), audio_row["sha256"])
        self.assertEqual(sync.blob_oid(raw_vm), audio_row["blob"])
        audio_manifest = json.loads((self.repo / "desktop/upstream-slices/v034-pgc-initial-detail/"
                                     "audio-download-manifest.json").read_bytes())
        self.assertEqual(audio_manifest["canonicalBaselineCommit"], "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
        self.assertIs(audio_manifest["partialReuse"], True)
        self.assertEqual(audio_manifest["source"]["localPath"], audio_row["localPath"])
        self.assertIs(audio_manifest["source"]["referenceOnly"], False)
        self.assertIs(audio_manifest["source"]["partialReuse"], True)
        self.assertEqual(audio_manifest["originalMethod"]["name"], "downloadAudio")
        self.assertIs(audio_manifest["originalMethod"]["completeOriginalMethod"], True)
        self.assertIn('inputs.file("tools/v034_pgc_audio_download.py")',
                      (self.repo / "desktop/build.gradle.kts").read_text(encoding="utf-8"))
        for filename in ("LiveInteractionSheets.kt", "LiveChatSection.kt"):
            rows = [item for item in entries if item["localPath"].endswith("/" + filename)]
            self.assertTrue(rows, filename)
            self.assertTrue(any(item["upstreamCommit"] == "b2091b1de636e499df80af7e700796b4f52b7f92"
                                for item in rows), filename)
            for item in rows:
                self.assertEqual(sync.source_digest(sync.adapted_local_input(self.repo, item),
                                                    item["hashNormalization"]), item["sha256"])
        sponsor_rows = [item for item in entries if item["localPath"] ==
                        "desktop/upstream-slices/v034-sponsor-ui/SponsorSkipUI.kt"]
        self.assertEqual(sponsor_rows, [{
            "path": "app/src/main/java/com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt",
            "localPath": "desktop/upstream-slices/v034-sponsor-ui/SponsorSkipUI.kt",
            "upstreamCommit": "b2091b1de636e499df80af7e700796b4f52b7f92",
            "blob": "ca6dc93eddbd2319adbbd13a245bcd97bb4e2e35",
            "sha256": "bf4f74363fe6150a5429cb6f5c00c9071e8341805c76bc5cdee8dfbafb87b462",
            "hashNormalization": "raw", "kind": "adaptedSource",
            "features": ["v034-sponsor-original-ui"], "referenceOnly": False,
        }])
        raw = (self.repo / sponsor_rows[0]["localPath"]).read_bytes()
        self.assertEqual(len(raw), 13343)
        self.assertNotIn(b"\r\n", raw, "The retained b209 original is exact LF, not a checkout-normalized substitute")
        self.assertEqual(hashlib.sha256(raw).hexdigest(), sponsor_rows[0]["sha256"])
        self.assertEqual(sync.blob_oid(raw), sponsor_rows[0]["blob"])

    def test_real_conflicting_baselines_share_one_incoming_download_and_keep_pins(self):
        # Restrict this request fixture to one actual conflicting path. It is
        # deliberately not a claim that all retained versions can be unchanged.
        all_entries = self.load()
        path = "app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt"
        adapted = [item for item in all_entries if item["path"] == path][:3]
        self.assertEqual(len({item["sha256"] for item in adapted}), 3)
        self.assertEqual(len({item["upstreamCommit"] for item in adapted}), 3)
        manifest = sync.read_manifest(self.original_repo)
        canonical = {**next(item for item in manifest["sources"] if item["path"] == path), "kind": "source"}
        destination = self.repo / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((self.original_repo / path).read_bytes())
        incoming = sync.adapted_local_input(self.repo, adapted[1])
        entries = [canonical, *adapted]
        frozen = json.dumps(entries, sort_keys=True)
        fixture_tree = {"tree": [tree_blob(path, incoming)]}
        with patch.object(sync, "compatibility_inputs", return_value=entries), \
             patch.object(sync, "latest_release", return_value={"tag_name": "multi-pin-fixture", "prerelease": True}), \
             patch.object(sync, "resolve_tag", return_value=NEW), \
             patch.object(sync, "request_json", return_value=fixture_tree) as api, \
             patch.object(sync, "source_bytes", return_value=incoming) as downloaded:
            report, hashes = sync.check_update(self.repo, manifest)
        api.assert_called_once_with(f"git/trees/{NEW}?recursive=1")
        downloaded.assert_called_once_with(NEW, path)
        self.assertEqual(report["sourceDownloads"], 1)
        self.assertEqual(report["unchangedBlobInputs"], 1)
        self.assertEqual(report["changedReusedSources"], [path])
        self.assertEqual(len(report["changedAdaptedInputs"]), 2)
        self.assertEqual({item["upstreamCommit"] for item in report["changedAdaptedInputs"]},
                         {adapted[0]["upstreamCommit"], adapted[2]["upstreamCommit"]})
        self.assertEqual(hashes, {path: sync.source_digest(incoming, canonical.get("hashNormalization", "lf"))})
        self.assertEqual(json.dumps(entries, sort_keys=True), frozen)
        self.assertEqual((self.repo / sync.COMPATIBILITY_CATALOG_PATH).read_bytes(), self.inventory_bytes)
        self.assertEqual(report["buildCompatibility"]["status"], "manualAdaptationRequired")
        self.assertFalse(report["autoBuildEligible"])
        self.assertFalse(report["autoPublishEligible"])

    def test_future_build_archive_or_standalone_input_requires_review(self):
        build = self.repo / "desktop/build.gradle.kts"
        original = build.read_bytes()
        for statement in (b'\ninputs.dir("upstream-slices/v099-new-original")\n',
                          b'\ninputs.file("third-party/new-original/Original.kt")\n'):
            with self.subTest(statement=statement):
                build.write_bytes(original + statement)
                with self.assertRaisesRegex(sync.UpdateError, "build input coverage changed"):
                    self.load()
        build.write_bytes(original)
        added = self.repo / "desktop/upstream-slices/v099-new-original"
        added.mkdir()
        with self.assertRaisesRegex(sync.UpdateError, "Unreviewed adapted source directory"):
            self.load()

    def test_future_source_or_metadata_leaf_is_not_silently_ignored(self):
        folder = self.repo / "desktop/upstream-slices/v029-bas-core"
        for filename in ("New.kt", "New.kts", "New.java", "New.glsl", "New.js",
                         "additional-manifest.json", "provenance.json"):
            with self.subTest(filename=filename):
                new = folder / filename
                new.write_bytes(b"unreviewed input")
                try:
                    with self.assertRaisesRegex(sync.UpdateError, "Unreviewed adapted archive leaf"):
                        self.load()
                finally:
                    new.unlink()

    def test_changed_manifest_and_missing_published_leaf_fail_closed(self):
        manifest = self.repo / "desktop/upstream-slices/v034-live-moderation-ui/manifest.json"
        original = manifest.read_bytes()
        manifest.write_bytes(original + b"\n")
        with self.assertRaisesRegex(sync.UpdateError, "Adapted archive bytes changed"):
            self.load()
        manifest.write_bytes(original)
        leaf = self.repo / "desktop/upstream-slices/v029-bas-core/BasEasing.kt"
        leaf.unlink()
        with self.assertRaisesRegex(sync.UpdateError, "Incomplete adapted archive"):
            self.load()

    def test_new_v034_source_slices_reject_modified_or_missing_required_raw_sources(self):
        leaves = (
            "v034-favorite-content-errors/core-data/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt",
            "v034-on-demand/OnDemandRequestCache.kt",
            "v034-detail-bootstrap/VideoDetailUiState.kt",
            "v034-metadata-related/RelatedVideoLoadScheduler.kt",
            "v034-text-lyrics/DynamicModels.kt",
            "v034-text-lyrics/LyricsParser.kt",
            "v034-text-lyrics/LyricsDisplayPolicy.kt",
            "v034-next-watch/NextWatchSuggestion.kt",
            "v034-pgc-initial-detail/BasePlayerViewModel.kt",
            "v034-pgc-initial-detail/BangumiPlayerViewModel.kt",
            "v034-next-watch-ui/NextWatchOverlay.kt",
            "v034-next-watch-ui/SettingsManager.kt",
            "v034-next-watch-ui/VideoPlayerSection.kt",
            "v035-autoplay-continuation/VideoPlaybackViewModel.kt",
            "v035-favorite-cover/FavoriteFolderCoverCache.kt",
            "v035-favorite-cover/FavoriteFolderCoverPrefetchPolicy.kt",
            "v035-favorite-cover/FavoriteFolderCardList.kt",
            "v035-favorite-cover/ListViewModel.kt",
            "v035-favorite-cover/CommonListScreen.kt",
            "v035-favorite-cover/manifest.json",
            "v035-story/StoryRepository.kt",
            "v035-story/StoryFeedPolicy.kt",
            "v035-story/StoryViewModel.kt",
            "v035-story/StoryModels.kt",
            "v035-story/ApiClient.kt",
            "v035-story/StoryScreen.kt",
            "v035-story/manifest.json",
            "v035-story-pager/PortraitVideoPager.kt",
            "v035-story-pager/manifest.json",
            "v035-dynamic-link/DynamicModels.kt",
            "v035-dynamic-link/DynamicCard.kt",
            "v035-dynamic-link/DynamicComponents.kt",
            "v035-dynamic-link/ForwardedContent.kt",
            "v035-dynamic-link/DynamicLinkCard.kt",
            "v035-dynamic-link/DynamicLinkCardPolicy.kt",
            "v035-dynamic-link/PageAwareAsyncImage.kt",
            "v035-dynamic-link/manifest.json",
            "v035-ambient/AmbientFramePolicy.kt",
            "v035-ambient/AmbientFrameController.kt",
            "v035-ambient/AmbientPlayerBinding.kt",
            "v035-ambient/README.md",
            "v035-ambient/manifest.json",
            "v035-scrollable-lists/AppScrollableLists.kt",
            "v035-scrollable-lists/manifest.json",
            "v035-dynamic-preview/DynamicSettingsStore.kt",
            "v035-dynamic-preview/VideoCardSharedElementContext.kt",
            "v035-dynamic-preview/DynamicCommentPreviewPolicy.kt",
            "v035-dynamic-preview/DynamicRichTextPolicy.kt",
            "v035-dynamic-preview/HomeFeedSettingsSection.kt",
            "v035-dynamic-preview/manifest.json",
            "v036-comment-route/CommentRepository.kt",
            "v036-comment-route/CommentReadRouteMemo.kt",
            "v036-comment-route/manifest.json",
            "v036-video-comment-cache/CommentPaginationPolicy.kt",
            "v036-video-comment-cache/manifest.json",
            "v036-video-comment-cache/SubReplySortPolicy.kt",
            "v036-video-comment-cache/VideoCommentLoadPolicy.kt",
            "v036-video-comment-cache/VideoCommentPaginationPolicy.kt",
            "v036-video-comment-cache/VideoCommentViewModel.kt",
        )
        for leaf in leaves:
            source = self.repo / "desktop/upstream-slices" / leaf
            original = source.read_bytes()
            with self.subTest(leaf=leaf):
                try:
                    source.write_bytes(original + b"\n// unreviewed source55 change\n")
                    with self.assertRaisesRegex(sync.UpdateError, "Adapted archive bytes changed"):
                        self.load()
                    source.write_bytes(original)
                    source.unlink()
                    with self.assertRaisesRegex(sync.UpdateError, "Incomplete adapted archive"):
                        self.load()
                finally:
                    source.write_bytes(original)

    def test_optional_local_document_can_be_absent_or_exact_but_not_changed(self):
        optional = [leaf for archive in self.inventory["archives"]
                    for leaf in archive.get("optionalLocalFiles", [])]
        self.assertEqual(len(optional), 1)
        pin = optional[0]
        document = self.repo / pin["path"]
        self.assertFalse(document.exists())
        self.assertEqual(len(self.load()), 555)
        self.assertEqual(hashlib.sha256(self.OPTIONAL_README).hexdigest(), pin["sha256"])
        document.write_bytes(self.OPTIONAL_README)
        self.assertEqual(len(self.load()), 555)
        document.write_bytes(self.OPTIONAL_README + b"changed local note")
        with self.assertRaisesRegex(sync.UpdateError, "Optional local document bytes changed"):
            self.load()

    def test_only_original_lf_input_and_catalog_allow_checkout_crlf(self):
        catalog = self.repo / sync.COMPATIBILITY_CATALOG_PATH
        catalog_lf = sync.normalized_source(catalog.read_bytes())
        catalog.write_bytes(catalog_lf.replace(b"\n", b"\r\n"))
        input_pin = next(item for item in self.inventory["inputs"]
                         if item["localPath"].endswith("/DynamicReactionApi.kt"))
        dynamic = self.repo / input_pin["localPath"]
        original_lf = sync.normalized_source(dynamic.read_bytes())
        for contents in (original_lf, original_lf.replace(b"\n", b"\r\n")):
            with self.subTest(dynamicCRLF=b"\r\n" in contents):
                dynamic.write_bytes(contents)
                entries = self.load()
                self.assertEqual(len(entries), 555)
                self.assertIn(input_pin, entries)
        dynamic.write_bytes(original_lf + b"\n// changed source\n")
        with self.assertRaisesRegex(sync.UpdateError, "Adapted archive bytes changed"):
            self.load()
        dynamic.write_bytes(original_lf)
        raw_pin = next(leaf for archive in self.inventory["archives"] for leaf in archive["files"]
                       if leaf["path"].endswith("/BasEasing.kt"))
        self.assertEqual(raw_pin["hashNormalization"], "raw")
        raw = self.repo / raw_pin["path"]
        original = raw.read_bytes()
        self.assertNotIn(b"\r\n", original)
        raw.write_bytes(original.replace(b"\n", b"\r\n"))
        with self.assertRaisesRegex(sync.UpdateError, "Adapted archive bytes changed"):
            self.load()

    def test_missing_or_modified_inventory_never_falls_back_to_canonical_scope(self):
        inventory = self.repo / sync.COMPATIBILITY_CATALOG_PATH
        inventory.unlink()
        with self.assertRaisesRegex(sync.UpdateError, "no reviewed compatibility inventory"):
            self.load()
        inventory.write_bytes(self.inventory_bytes + b" ")
        with self.assertRaisesRegex(sync.UpdateError, "Unknown adapted input inventory"):
            self.load()


class ApiClientRiskTests(unittest.TestCase):
    """Selected canonical API risk fixtures, not a synthetic all-adaptations release."""

    def setUp(self):
        adapted = patch.object(sync, "adapted_compatibility_inputs", return_value=[])
        adapted.start()
        self.addCleanup(adapted.stop)
        self.repo = Path(__file__).parents[3]
        original = canonical_source(self.repo, "app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt")
        self.path = original.relative_to(self.repo).as_posix()
        self.source = sync.normalized_source(original.read_bytes())
        manifest = json.loads((self.repo / "desktop/upstream-sources.json").read_text(encoding="utf-8"))
        self.manifest = {**manifest, "sources": [next(item for item in manifest["sources"] if item["path"] == self.path)], "resources": []}

    def report(self, candidate):
        build = canonical_source(self.repo, "app/build.gradle.kts").read_bytes()
        tree = {"tree": [tree_blob(self.path, candidate), tree_blob("app/build.gradle.kts", sync.normalized_source(build))]}
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
            (b'.baseUrl("https://passport.bilibili.com/").client(okHttpClient)',
             b'.baseUrl("https://changed.bilibili.com/").client(okHttpClient)'),
            (b'.baseUrl("https://passport.bilibili.com/")\n            .client(createQrAuthorizationClient(okHttpClient))',
             b'.baseUrl("https://changed.bilibili.com/")\n            .client(createQrAuthorizationClient(okHttpClient))'),
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
                self.assertFalse(report["autoBuildEligible"])
                self.assertFalse(report["autoPublishEligible"])
                self.assertIn("canonicalInputChanged", [item["code"] for item in report["buildCompatibility"]["reasons"]])
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
            with self.subTest(candidateLength=len(candidate)):
                report = self.report(candidate)
                self.assertFalse(report["autoBuildEligible"])
                self.assertFalse(report["autoPublishEligible"])
                self.assertEqual(report["sensitiveReviewFailed"], [self.path])
                self.assertTrue(report["manualReviewReasons"])


class IsolatedWorktreeTests(unittest.TestCase):
    def test_failed_build_preserves_original_branch_and_files(self):
        self.run_candidate("failedBuild")

    def test_successful_build_is_bound_to_the_precommitted_clean_candidate(self):
        self.run_candidate("success")

    def test_policy_source_mutation_blocks_the_build(self):
        self.run_candidate("policyMutation")

    def test_successful_command_cannot_publish_dirty_or_recommitted_source(self):
        for outcome in ("buildMutation", "buildCommit"):
            with self.subTest(outcome=outcome):
                self.run_candidate(outcome)

    def run_candidate(self, outcome):
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
                "autoBuildEligible": True, "buildCompatibility": {"status": "ready"},
            }
            real_run = sync.run
            observed = []
            verified_manifest = None

            def simulated_build(command, cwd, **kwargs):
                nonlocal verified_manifest
                if command[:2] == ["git", "fetch"]:
                    return subprocess.CompletedProcess(command, 0, "", "")
                if command[0] == sync.sys.executable or command[0] == "pwsh":
                    stage = "policies" if command[0] == sync.sys.executable else "build"
                    self.assertEqual(sync.git(cwd, "status", "--porcelain"), "")
                    self.assertEqual(sync.git(cwd, "rev-parse", "HEAD"), report["candidateHead"])
                    current_manifest = (cwd / "desktop/upstream-sources.json").read_bytes()
                    self.assertEqual(json.loads(current_manifest)["lastSourceReview"]["status"],
                                     "requiresBuildVerification")
                    if stage == "policies":
                        self.assertEqual(command[1:], ["desktop/tools/run-tool-tests.py", "--stage", "policies"])
                        verified_manifest = current_manifest
                    else:
                        self.assertEqual(current_manifest, verified_manifest)
                        self.assertIn("-ReleaseGate", command)
                    observed.append(stage)
                    if (outcome == "policyMutation" and stage == "policies") or (
                            outcome in {"buildMutation", "buildCommit"} and stage == "build"):
                        (cwd / "app/Api.kt").write_bytes(b"unverified mutation")
                        if outcome == "buildCommit":
                            sync.git(cwd, "commit", "-am", "Unverified post-build source")
                    if outcome == "failedBuild" and stage == "build":
                        raise sync.UpdateError("simulated failed Windows build")
                    return subprocess.CompletedProcess(command, 0, "", "")
                return real_run(command, cwd, **kwargs)

            with patch.object(sync, "run", side_effect=simulated_build):
                if outcome == "success":
                    result = sync.sync_update(repo, manifest, report,
                                              {"app/Api.kt": hashlib.sha256(b"new").hexdigest()}, root / "updates", None,
                                              release_gate=True)
                    self.assertIs(result, report)
                else:
                    with self.assertRaisesRegex(sync.UpdateError, "Original checkout preserved"):
                        sync.sync_update(repo, manifest, report,
                                         {"app/Api.kt": hashlib.sha256(b"new").hexdigest()}, root / "updates", None,
                                         release_gate=True)
            self.assertEqual(sync.git(repo, "rev-parse", "HEAD"), original_head)
            self.assertEqual(sync.git(repo, "branch", "--show-current"), "main")
            self.assertEqual(sync.git(repo, "status", "--porcelain"), "")
            self.assertEqual((repo / "app/Api.kt").read_bytes(), b"old")
            self.assertTrue(Path(report["candidatePath"]).is_dir())
            candidate = Path(report["candidatePath"])
            expected_bytes = b"unverified mutation" if outcome in {"policyMutation", "buildMutation", "buildCommit"} else b"new"
            self.assertEqual((candidate / "app/Api.kt").read_bytes(), expected_bytes)
            self.assertEqual(report["status"], "candidateReady" if outcome == "success" else "candidateFailed")
            self.assertEqual(observed, ["policies"] if outcome == "policyMutation" else ["policies", "build"])
            if outcome in {"success", "failedBuild"}:
                self.assertEqual(sync.git(candidate, "rev-parse", "HEAD"), report["candidateHead"])
                self.assertEqual(sync.git(candidate, "status", "--porcelain"), "")
            if outcome == "success":
                self.assertEqual(report["buildStatus"], "passed")
                self.assertTrue(report["releaseGatePassed"])
                self.assertEqual((candidate / "desktop/upstream-sources.json").read_bytes(), verified_manifest)
            elif outcome in {"policyMutation", "buildMutation", "buildCommit"}:
                self.assertIn("source changed", report["error"])
                self.assertNotEqual(report["buildStatus"], "passed")
                self.assertNotIn("releaseGatePassed", report)
            committed_manifest = json.loads((candidate / "desktop/upstream-sources.json").read_text())
            self.assertEqual(committed_manifest["lastSourceReview"]["status"], "requiresBuildVerification")


if __name__ == "__main__":
    unittest.main()
