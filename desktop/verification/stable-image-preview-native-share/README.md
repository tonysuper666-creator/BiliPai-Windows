# Original image-preview sharing on Windows

The original image preview's raw-byte sharing now uses the existing retained Root file pool, image reader and Windows native sharing actor. Home, dynamic, space, article and original comment previews use the same binding; GIF bytes remain unchanged. The final publication checks the captured caller and current route/Root ownership. Unknown native retirement keeps the existing file lease.

The integrated standalone desktop project passed `gradle -p desktop classes compileTestKotlin` with no product overrides. Tests were compiled, not executed. That build freshly compiled the native sharing component from the reviewed source and input graph, and verified its output hash. The prepared protocol fixture passed 48 assertions, including actual native prepare/retire and failed-share cleanup.

The Windows share receiver UI, delivery to a receiver, a real Root preview click and long paths are not accepted. A preserved 261-character native prepare case failed with E_INVALIDARG. The default generated image paths are bounded below that length, but this does not establish general long-path support. Cache-clear UI is a separate pending integration, and ordinary-account HTTP 412 is not resolved by this change. The delivered desktop EXE still contains the earlier source revision.

`artifact-index.json` pins the preserved preparation and integrated build records. No runtime JAR, DLL, compiled class tree or snapshot classpath is copied here. The preserved preparation's task spelling assumes a nested project; the correct task is `gradle -p desktop prepareNativeDiagnosticShare`. The installation receipt predates the successful fresh native build; the later actual build and producer receipts record the result.
