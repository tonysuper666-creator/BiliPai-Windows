import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("extract_upstream_api", Path(__file__).with_name("extract-upstream-api.py"))
extractor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extractor)


class UpstreamApiExtractionTest(unittest.TestCase):
    def test_updated_route_and_multiline_signature_are_preserved(self):
        source = '''
    @Headers("Referer: https://search.bilibili.com/")
    @GET("x/new/search/type")
    suspend fun search(
        @QueryMap params: Map<String, String>
    ): SearchTypeResponse
'''
        result = extractor.extract_method(source, "search")
        self.assertIn('@GET("x/new/search/type")', result)
        self.assertIn('@Headers("Referer: https://search.bilibili.com/")', result)
        self.assertIn('@QueryMap params: Map<String, String>', result)

    def test_removed_api_method_requires_explicit_review(self):
        with self.assertRaisesRegex(ValueError, "Expected one upstream search declaration"):
            extractor.extract_method('@GET("x/search")\nsuspend fun renamed(): SearchTypeResponse', "search")

    def test_changed_method_with_implementation_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unsupported upstream return signature"):
            extractor.extract_method('@GET("x/search")\nsuspend fun search() = implementation()', "search")


if __name__ == "__main__":
    unittest.main()
