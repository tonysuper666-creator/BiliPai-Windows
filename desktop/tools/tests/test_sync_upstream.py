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
    def setUp(self):
        self.repo = Path(__file__).parents[3]
        self.manifest = sync.read_manifest(self.repo)
        self.catalog = sync.canonical_baseline(self.repo, self.manifest)
        self.entries = sync.compatibility_inputs(self.repo, self.manifest, self.catalog)
        self.tree = {"tree": [{"path": item["path"], "type": "blob", "mode": "100644",
                               "sha": self.catalog["paths"][item["path"]]["blob"]} for item in self.entries]}

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

    def test_current_complete_fixed_baseline_skips_downloads_and_allows_build_only(self):
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


class ApiClientRiskTests(unittest.TestCase):
    def setUp(self):
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
