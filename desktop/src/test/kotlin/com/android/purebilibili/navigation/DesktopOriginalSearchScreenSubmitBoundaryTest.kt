package com.android.purebilibili.navigation

import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.function.Consumer
import javax.tools.ToolProvider
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * Invoke the actual generated SearchScreen submit lambda without mounting Compose or networking.
 * Its class bytes are unchanged. Only SearchViewModel is replaced, in a private loader, with a
 * recording search/dismiss collaborator. A policy-only test cannot detect a bypass in this consumer.
 */
class DesktopOriginalSearchScreenSubmitBoundaryTest {
    @TestFactory
    fun `compiled search screen submits complete candidates`() = DesktopSearchSubmitCases.cases.map { case ->
        DynamicTest.dynamicTest(case.name) {
            assertEquals(case.expected, submit(case.input), case.input)
        }
    }

    private fun submit(input: String): String {
        val calls = mutableListOf<String>()
        viewModelClass.getField("record").set(null, Consumer<String> { calls += "search:$it" })
        val types = submitMethod.parameterTypes
        fun proxy(index: Int, body: (String, Array<out Any?>?) -> Any?) = Proxy.newProxyInstance(
            types[index].classLoader, arrayOf(types[index]),
        ) { _, method, args -> body(method.name, args) }
        submitMethod.invoke(
            null, viewModelClass.getConstructor().newInstance(),
            proxy(1) { _, args -> calls += "link:${args!![0]}"; Unit },
            proxy(2) { _, args -> calls += "native:${args!![0]}"; true },
            proxy(3) { _, _ -> Unit },
            proxy(4) { _, _ -> state },
            proxy(5) { method, _ -> if (method == "getValue") false else Unit },
            proxy(6) { method, _ -> if (method == "getValue") false else Unit }, input,
        )
        return calls.joinToString(";")
    }

    companion object {
        private const val VM = "com.android.purebilibili.feature.search.SearchViewModel"
        private const val SCREEN = "com.android.purebilibili.feature.search.SearchScreenKt"
        private val parent = DesktopOriginalSearchScreenSubmitBoundaryTest::class.java.classLoader
        private val loader by lazy {
            val directory = Files.createTempDirectory("search-submit-collaborator-")
            val stubBytes = try {
                val source = directory.resolve("SearchViewModel.java")
                Files.writeString(source, """
                    package com.android.purebilibili.feature.search;
                    public final class SearchViewModel {
                        public static java.util.function.Consumer<String> record;
                        public void search(String value, boolean refresh) { record.accept(value); }
                        public static void search${'$'}default(SearchViewModel self, String value, boolean refresh, int mask, Object marker) {
                            self.search(value, refresh);
                        }
                        public void dismissSuggestions() {}
                    }
                """.trimIndent())
                val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Run desktop tests on a JDK" }
                check(compiler.run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
                Files.readAllBytes(directory.resolve(VM.replace('.', '/') + ".class"))
            } finally {
                directory.toFile().deleteRecursively()
            }
            object : ClassLoader(parent) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name != VM && name != SCREEN) return super.loadClass(name, resolve)
                    synchronized(getClassLoadingLock(name)) {
                        val result = findLoadedClass(name) ?: run {
                            val bytes = if (name == VM) stubBytes else checkNotNull(
                                parent.getResourceAsStream(name.replace('.', '/') + ".class"),
                            ).use { it.readBytes() }
                            defineClass(name, bytes, 0, bytes.size)
                        }
                        if (resolve) resolveClass(result)
                        return result
                    }
                }
            }
        }
        private val viewModelClass by lazy { loader.loadClass(VM) }
        private val state by lazy { parent.loadClass("com.android.purebilibili.feature.search.SearchUiState").getConstructor().newInstance() }
        private val submitMethod by lazy {
            // Match the submit lambda's captured collaborators, not a compiler-generated ordinal.
            loader.loadClass(SCREEN).declaredMethods.single {
                it.parameterCount == 8 && it.parameterTypes.first().name == VM &&
                    it.parameterTypes.last() == String::class.java &&
                    it.parameterTypes[1].name == "kotlin.jvm.functions.Function1" &&
                    it.parameterTypes[4].name == "androidx.compose.runtime.State"
            }.apply { isAccessible = true }
        }
    }
}
