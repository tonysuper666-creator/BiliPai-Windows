"""Source tests for the static symbol-closure gate (desktop/tools/check_slice_symbol_closure.py).

No JVM is started and nothing is compiled. The fast classes use small synthetic Kotlin trees, one per failure
class of the Source260 compile (head aad198489); `ProducerMirrorTests` drives the real mirror/producer/report
path on a tiny throw-away git repository; `CurrentIntegrationTree` runs every real producer on this checkout
and `Source260History` re-runs the gate on the failed head through `git archive`.

Those last two classes cost minutes each (every producer runs and a 160 MB mirror is written). They always run by
default; set BP_SYMBOL_GATE_SKIP_HEAVY=1 to leave them out on a deliberate low-resource run. Nothing else changes.
"""
from pathlib import Path
import contextlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate

SOURCE260_HEAD = 'aad1984892a4b276f901daae3c1865c0871ac707'
V025_COMMIT = '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
GRADLE_FILE = REPO / 'desktop/build.gradle.kts'
# the two classes that run every real producer cost minutes each; BP_SYMBOL_GATE_SKIP_HEAVY=1 skips them on purpose
SKIP_HEAVY = os.environ.get('BP_SYMBOL_GATE_SKIP_HEAVY') == '1'


def rmtree_force(path):
    """Remove a fixture tree. Git object files are read-only, so a plain ignore-errors rmtree leaves .git behind."""
    def clear(function, target, *_):
        try:
            os.chmod(target, stat.S_IRWXU)
            function(target)
        except OSError:
            pass

    if sys.version_info >= (3, 12):
        shutil.rmtree(str(path), onexc=clear)
    else:
        shutil.rmtree(str(path), onerror=clear)


class Mini:
    """A tiny compile universe on disk: (logical path, origin, source set, text)."""

    def __init__(self, test):
        self.directory = Path(tempfile.mkdtemp(prefix='bp-t-'))
        test.addCleanup(rmtree_force, self.directory)
        self.sources = []

    def add(self, logical, text, origin='desktop', source_set='main'):
        real = self.directory / ('%04d.kt' % len(self.sources))
        real.write_bytes(textwrap.dedent(text).lstrip('\n').encode('utf8'))
        self.sources.append(gate.Source(logical, real, origin, source_set))
        return self

    COMMON = '''\
        package corpus
        internal fun common(a: Boolean, b: Int, c: String, d: Unit, e: Float, f: Long, g: Any, h: Set<Long>, i: Modifier) {
            listOf(a)
        }
        '''

    def run(self, expected=None, with_corpus=True, producers_partial=False, **options):
        sources = list(self.sources)
        if with_corpus:
            real = self.directory / 'corpus.kt'
            real.write_bytes(textwrap.dedent(self.COMMON).lstrip('\n').encode('utf8'))
            sources.append(gate.Source('app/Corpus.kt', real, 'vendored', 'main'))
        return gate.analyze(sources, [], expected or gate.empty_expected(), gate.Options(**options),
                            producers_partial=producers_partial)


def found(result):
    return sorted({(f.check, f.symbol) for f in result.unexpected})


def checks(result):
    return sorted({f.check for f in result.unexpected})


class LexerTests(unittest.TestCase):
    SAMPLE = '''\
        package a.b
        // import not.real.Comment
        /* nested /* comment */ still comment import not.real.Block */
        val s = "text import not.real.String ${call(arg)} and $name"
        val raw = """raw "quoted" { brace ${inner} """
        val c = '"'
        val d = '{'
        val `tick "name"` = 1
        fun after() = keep.me
        '''

    def test_masking_keeps_length_lines_and_template_code_but_not_comments_or_literals(self):
        text = textwrap.dedent(self.SAMPLE)
        masked = gate.mask_kotlin(text)
        self.assertEqual(len(text), len(masked))
        self.assertEqual(text.count('\n'), masked.count('\n'))
        for gone in ('Comment', 'Block', 'real', 'quoted', 'text import'):
            self.assertNotIn(gone, masked)
        for kept in ('call(arg)', 'name', 'inner', 'keep.me', '`tick "name"`'):
            self.assertIn(kept, masked)

    def test_character_literals_and_unterminated_strings_do_not_swallow_following_code(self):
        masked = gate.mask_kotlin("val a = '\"'\nval b = \"open\nfun kept() = 1\n")
        self.assertIn('fun kept()', masked)

    def test_every_desktop_source_has_balanced_delimiters_after_masking(self):
        unbalanced = []
        files = sorted((REPO / 'desktop/src/main/kotlin').rglob('*.kt'))
        self.assertGreater(len(files), 500)
        for path in files:
            toks, _ = gate.tokenize(gate.mask_kotlin(path.read_bytes().decode('utf8').replace('\r\n', '\n')))
            for pair in ('{}', '()', '[]'):
                if toks.count(pair[0]) != toks.count(pair[1]):
                    unbalanced.append(path.name)
        self.assertEqual([], unbalanced)


class ParserTests(unittest.TestCase):
    def parse(self, text):
        return gate.parse_kotlin(textwrap.dedent(text).lstrip('\n'), 'x/Sample.kt')

    def test_package_imports_aliases_stars_and_file_jvm_name(self):
        info = self.parse('''\
            @file:JvmName("Custom")
            package a.b.c
            import a.b.Foo
            import x.y.Z as W
            import q.r.*
            import `weird`.Name
            fun top() = 1
            ''')
        self.assertEqual('a.b.c', info.package)
        self.assertEqual('Custom', info.jvm_name)
        self.assertEqual('Custom', info.facade)
        self.assertEqual([('a.b.Foo', None, False), ('x.y.Z', 'W', False), ('q.r', None, True),
                          ('weird.Name', None, False)], [(i.path, i.alias, i.star) for i in info.imports])
        self.assertEqual(['Foo', 'W', 'r', 'Name'], [i.simple if not i.star else i.path.rsplit('.', 1)[-1]
                                                       for i in info.imports])

    def test_function_property_and_class_headers(self):
        info = self.parse('''\
            package p
            @Composable
            internal fun Foo(a: Int, b: List<String> = emptyList(), c: () -> Unit = {}): Boolean = a > 1
            private fun <T> List<T>.bar(x: Map<String, Int>): String { return "" }
            fun Player?.snapshot(): Int = 0
            val Int.ext: Int get() = this + 1
            val top = 1
            val state get() = other.state
            data class P(val x: Int, var y: String) : Base(), Other {
                companion object { const val K = 1; fun make() = P(1, "") }
                fun inner() {}
                val prop: Int get() = 3
            }
            enum class E { A, B(1), C { override fun f() {} };
                fun g() {} }
            object O : Thing { val v = 1 }
            interface I { fun a(); val b: Int }
            fun interface Fi { fun call() }
            typealias TA = List<Int>
            ''')
        by_name = {d.name: d for d in info.decls}
        self.assertEqual(('fun', '', 'Int,List<String>,()->Unit', 'Boolean', 'internal'),
                         (by_name['Foo'].kind, by_name['Foo'].receiver, by_name['Foo'].sig, by_name['Foo'].ret,
                          by_name['Foo'].visibility))
        self.assertEqual(('List<T>', 'Map<String,Int>', 'private'),
                         (by_name['bar'].receiver, by_name['bar'].sig, by_name['bar'].visibility))
        self.assertEqual('Player', by_name['snapshot'].receiver)
        self.assertEqual(('Int', 'Int'), (by_name['ext'].receiver, by_name['ext'].ret))
        self.assertEqual('val', by_name['state'].kind)
        self.assertEqual({'K', 'make', 'inner', 'prop', 'x', 'y', 'copy', 'component1', 'component2'},
                         set(by_name['P'].members) - set(gate.ANY_MEMBERS))
        self.assertEqual(('Base', 'Other'), by_name['P'].supers)
        self.assertTrue({'A', 'B', 'C', 'g', 'entries', 'values'} <= set(by_name['E'].members))
        self.assertEqual('interface', by_name['Fi'].kind)
        self.assertIn('call', by_name['Fi'].members)
        self.assertEqual('typealias', by_name['TA'].kind)

    def test_nested_member_extensions_and_property_getters_are_member_names(self):
        info = self.parse('''\
            package p
            class Holder {
                val state get() = 1
                private fun Style.toOther(): Int = 2
                var Style.flag get() = true
            }
            ''')
        holder = info.decls[0]
        self.assertTrue({'state', 'toOther', 'flag'} <= set(holder.members))
        self.assertEqual({'toOther', 'flag'}, set(holder.member_ext))

    def test_regions_cover_each_declaration_and_leave_imports_out(self):
        text = textwrap.dedent('''\
            package p
            import a.B
            @Composable
            fun First() { B() }

            @Suppress("x")
            private val second = 2
            fun Third() {}
            ''')
        info = gate.parse_kotlin(text, 'p/F.kt', keep_tokens=True)
        names = [d.name for d in info.decls]
        self.assertEqual(['First', 'second', 'Third'], names)
        spans = [(d.start, d.end) for d in info.decls]
        self.assertEqual(spans[0][1], spans[1][0])
        self.assertEqual(spans[1][1], spans[2][0])
        self.assertEqual('@', info.toks[info.decls[0].start])
        self.assertEqual('@', info.toks[info.decls[1].start])
        self.assertEqual(len(info.toks), info.decls[-1].end)

    def test_facade_exists_only_for_files_with_top_level_callables(self):
        self.assertIsNone(self.parse('package p\nclass Only\n').facade)
        self.assertEqual('SampleKt', self.parse('package p\nfun f() = 1\n').facade)


class HygieneTests(unittest.TestCase):
    FILES = ('desktop/tools/check_slice_symbol_closure.py', 'desktop/tools/tests/test_symbol_closure.py',
             'desktop/tools/symbol-closure-expected-gaps.json')

    def test_files_are_lf_only_utf8(self):
        for name in self.FILES:
            with self.subTest(name=name):
                raw = (REPO / name).read_bytes()
                self.assertNotIn(b'\r', raw)
                raw.decode('utf8')

    def test_command_line_help_runs_without_touching_anything(self):
        done = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(TOOLS / 'check_slice_symbol_closure.py'), '--help'],
                              capture_output=True, cwd=tempfile.gettempdir())
        self.assertEqual(0, done.returncode)
        output = done.stdout.decode('utf8')
        for flag in ('--revision', '--mirror', '--expected-gaps', '--candidate-path', '--no-carried', '--json'):
            self.assertIn(flag, output)

    def test_tool_never_writes_into_the_repository_it_is_pointed_at(self):
        text = (TOOLS / 'check_slice_symbol_closure.py').read_text(encoding='utf8')
        for forbidden in ('.write_text(', "'w')", '"w")', 'os.remove(', 'unlink('):
            self.assertNotIn(forbidden, text)
        # the only writes are scratch files, the throw-away mirror, candidate blobs and the optional --json report
        self.assertEqual(1, text.count('.write_bytes(((common'))
        self.assertEqual(1, text.count('real.write_bytes(git_bytes'))
        # the one permission change clears read-only git objects inside a scratch tree about to be removed
        self.assertEqual(1, text.count('os.chmod('))
        self.assertEqual(1, text.count('shutil.rmtree(_long(path), onexc=clear)'))


class GradleModelTests(unittest.TestCase):
    SYNTHETIC = '''\
        val repositoryRoot = projectDir.parentFile
        val generatedAppearance = layout.buildDirectory.dir("generated/appearance")
        val prepareUpstreamSources by tasks.registering(Sync::class) {
            from(repositoryRoot) {
                exclude(canonicalOriginalIdentity("app/A.kt"))
                exclude(canonicalOriginalIdentity("app/B.kt"))
            }
        }
        val extractOne by tasks.registering(Exec::class) {
            dependsOn(prepareUpstreamSources)
            workingDir(projectDir)
            commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/one.py",
                "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/one").get().asFile.absolutePath,
                "--test-output", generatedAppearance.get().asFile.absolutePath)
        }
        val extractTwo by tasks.registering(Exec::class) {
            dependsOn(extractOne, prepareUpstreamSources)
            commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/two.py",
                repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/two").get().asFile.absolutePath)
        }
        val verifyTwo by tasks.registering(Exec::class) {
            commandLine("python", "tools/verify.py", layout.buildDirectory.dir("generated/two").get().asFile.absolutePath)
        }
        val unrelated by tasks.registering(Exec::class) {
            commandLine("python", "tools/unrelated.py", layout.buildDirectory.dir("generated/elsewhere").get().asFile.absolutePath)
        }
        kotlin.sourceSets.named("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/one"))
            kotlin.srcDir(layout.buildDirectory.dir("generated/two/com"))
            kotlin.srcDir("third-party/lib/src")
        }
        kotlin.sourceSets.named("test") { kotlin.srcDir(generatedAppearance) }
        '''

    def model(self, text=None):
        return gate.parse_gradle(textwrap.dedent(self.SYNTHETIC if text is None else text))

    def test_variables_tasks_dependencies_and_source_directories_are_modelled(self):
        model = self.model()
        self.assertEqual({'generatedAppearance': 'generated/appearance'}, model.variables)
        self.assertEqual(['app/A.kt', 'app/B.kt'], model.sync_excludes)
        self.assertEqual([('build', 'generated/one'), ('build', 'generated/two/com'), ('project', 'third-party/lib/src')],
                         model.source_dirs['main'])
        self.assertEqual([('build', 'generated/appearance')], model.source_dirs['test'])
        one = model.tasks['extractOne']
        self.assertEqual('tools/one.py', one.tool)
        self.assertEqual(['prepareUpstreamSources'], one.deps)
        self.assertIn(('build', 'generated/appearance'), one.args)
        self.assertEqual(('repo', ''), one.args[3])

    def test_producers_are_python_tasks_feeding_a_source_directory_in_dependency_order(self):
        tasks, skipped = gate.select_producers(self.model())
        self.assertEqual(['extractOne', 'extractTwo'], [t.name for t in tasks])
        self.assertEqual([], skipped)
        only, _ = gate.select_producers(self.model(), only={'extractTwo'})
        self.assertEqual(['extractTwo'], [t.name for t in only])
        _, skipped = gate.select_producers(self.model(), skip={'extractTwo'})
        self.assertEqual([('extractTwo', 'skipped by request')], skipped)

    def test_exclusions_and_commands_the_model_cannot_follow_fail_loudly(self):
        bad_exclude = self.SYNTHETIC.replace('exclude(canonicalOriginalIdentity("app/B.kt"))',
                                             'exclude(canonicalOriginalIdentity("app/B.kt"))\n        exclude("x")')
        with self.assertRaises(gate.ToolError):
            self.model(bad_exclude)
        unresolved_task = self.SYNTHETIC.replace('"--test-output", generatedAppearance.get().asFile.absolutePath',
                                                 '"--test-output", somethingNew.absolutePath')
        with self.assertRaisesRegex(gate.ToolError, 'extractOne'):
            gate.select_producers(self.model(unresolved_task))
        unresolved_dir = self.SYNTHETIC.replace('kotlin.srcDir("third-party/lib/src")', 'kotlin.srcDir(computedDir())')
        with self.assertRaisesRegex(gate.ToolError, 'computedDir'):
            gate.select_producers(self.model(unresolved_dir))

    def test_only_outputs_of_producers_a_run_did_not_start_may_be_missing(self):
        model = self.model()
        tasks, skipped = gate.select_producers(model, skip={'extractTwo'})
        self.assertEqual(['generated/two'], gate.not_run_outputs(model, tasks, skipped))
        tasks, skipped = gate.select_producers(model)
        self.assertEqual([], gate.not_run_outputs(model, tasks, skipped))
        tasks, skipped = gate.select_producers(model, only={'extractOne'})
        self.assertEqual(['generated/elsewhere', 'generated/two'],
                         gate.not_run_outputs(model, tasks, skipped, only={'extractOne'}))
        tasks, skipped = gate.select_producers(model)
        results = [dict(task='extractOne', returncode=0), dict(task='extractTwo', returncode=3)]
        self.assertEqual(['generated/two'], gate.not_run_outputs(model, tasks, skipped, results=results))   # reported as failed

    def test_unrunnable_producers_name_the_directories_they_own_and_the_build_file_still_registers_them(self):
        self.assertEqual(set(gate.UNRUNNABLE_TASKS), set(gate.UNRUNNABLE_OUTPUTS))
        model = gate.parse_gradle(GRADLE_FILE.read_text(encoding='utf8'))
        registered = [rel for owner in ('main', 'test') for kind, rel in model.source_dirs[owner] if kind == 'build']
        for task, directories in gate.UNRUNNABLE_OUTPUTS.items():
            for directory in directories:
                with self.subTest(task=task, directory=directory):
                    self.assertTrue(any(gate._under(r, directory) for r in registered),
                                    'no Kotlin source directory is registered below %s any more' % directory)
        tasks, skipped = gate.select_producers(model)
        allowed = gate.not_run_outputs(model, tasks, skipped)
        for relative in ('generated/js-worker', 'generated/native-diagnostic-share/kotlin'):
            self.assertTrue(any(gate._under(relative, owned) for owned in allowed), relative)
        self.assertFalse(any(gate._under(r, owned) for r in registered if r.startswith('generated/original')
                             for owned in allowed), 'a runnable producer directory must never be allowed to be missing')

    def test_real_build_file_is_fully_modelled(self):
        model = gate.parse_gradle(GRADLE_FILE.read_text(encoding='utf8'))
        self.assertEqual([], model.unresolved_source_dirs)
        self.assertGreater(len(model.sync_excludes), 40)
        self.assertTrue(set(gate.UNRUNNABLE_TASKS) <= set(model.tasks), 'a renamed task escapes the skip list')
        tasks, skipped = gate.select_producers(model)
        names = [t.name for t in tasks]
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(sorted(gate.UNRUNNABLE_TASKS), [n for n, _ in skipped])
        for position, task in enumerate(tasks):
            for dep in task.deps:
                if dep in names:
                    self.assertLess(names.index(dep), position, '%s runs before its dependency %s' % (task.name, dep))
        for task in tasks:
            self.assertEqual(('python', ''), task.args[0])
            self.assertTrue((REPO / 'desktop' / task.tool).is_file(), task.tool)
        # every build-directory source root is produced by a runnable producer, the vendored Sync, or is named here
        wanted = {rel for owner in ('main', 'test') for kind, rel in model.source_dirs[owner] if kind == 'build'}
        produced = {v for t in tasks for k, v in t.args if k == 'build'}
        known_side_effect_or_unrunnable = {'generated/upstream', 'generated/js-worker',
                                           'generated/native-diagnostic-share/kotlin',
                                           'generated/audio-library-original-tests',
                                           'generated/original-video-interaction-retry-tests'}
        for rel in sorted(wanted - known_side_effect_or_unrunnable):
            self.assertTrue(any(gate._under(rel, p) or gate._under(p, rel) for p in produced), rel)


class ChecksOnSyntheticTrees(unittest.TestCase):
    """One small tree per failure class; each has a positive and a negative case."""

    def test_duplicate_declarations_need_the_same_package_kind_name_and_signature(self):
        mini = Mini(self)
        mini.add('g/A.kt', '''\
            package p.q
            internal fun shared(a: Boolean): Boolean = a
            internal fun overloaded(a: Int) = a
            private fun hidden() = 1
            class Dup
            ''', 'generated')
        mini.add('g/B.kt', '''\
            package p.q
            internal fun shared(a: Boolean): Boolean = !a
            internal fun overloaded(a: String) = a
            private fun hidden() = 2
            class Dup
            ''', 'generated')
        mini.add('d/Other.kt', 'package p.r\ninternal fun shared(a: Boolean): Boolean = a\n')
        mini.add('t/TestSide.kt', 'package p.q\ninternal fun shared(a: Boolean): Boolean = a\n', 'desktop', 'test')
        result = mini.run(carried=False)
        self.assertEqual([('duplicate-declaration', 'p.q.Dup'),
                          ('duplicate-declaration', 'p.q.shared(Boolean)')], found(result))
        locations = sorted(f.file for f in result.unexpected if f.symbol == 'p.q.shared(Boolean)')
        self.assertEqual(['g/A.kt', 'g/B.kt'], locations)

    def test_two_files_with_one_facade_name_clash_unless_renamed_or_without_callables(self):
        mini = Mini(self)
        mini.add('a/Util.kt', 'package p\nfun one() = 1\n', 'generated')
        mini.add('b/Util.kt', 'package p\nfun two() = 2\n', 'generated')
        mini.add('a/Types.kt', 'package p\nclass OnlyTypes\n')
        mini.add('b/Types.kt', 'package p\nclass OtherTypes\n')
        mini.add('a/Named.kt', '@file:JvmName("NamedA")\npackage p\nfun three() = 3\n')
        mini.add('b/Named.kt', '@file:JvmName("NamedB")\npackage p\nfun four() = 4\n')
        self.assertEqual([('duplicate-file-class', 'p.UtilKt')], found(mini.run(carried=False)))

    def test_unresolved_import_class_with_aliases_stars_members_and_synthetic_definers(self):
        mini = Mini(self)
        mini.add('d/Defs.kt', '''\
            package com.android.purebilibili.core.ui
            object AppContentColors { fun error() = 0 }
            enum class Mode { FAST, SLOW }
            fun helper() = 1
            ''', 'vendored')
        mini.add('g/Use.kt', '''\
            package com.android.purebilibili.feature.x
            import com.android.purebilibili.core.ui.AppContentColors
            import com.android.purebilibili.core.ui.AppContentColors as Colors
            import com.android.purebilibili.core.ui.Mode.FAST
            import com.android.purebilibili.core.ui.Mode.MISSING
            import com.android.purebilibili.core.ui.*
            import com.android.purebilibili.core.nothing.*
            import com.android.purebilibili.core.ui.userVipPink
            import com.bilipai.desktop.diagnostics.AssetHash
            import androidx.compose.runtime.mutableIntStateOf
            fun use() = 1
            ''', 'generated')
        expected = gate.empty_expected()
        expected['synthetic'] = {'com.bilipai.desktop.diagnostics': {'AssetHash'}}
        result = mini.run(expected, carried=False)
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.nothing.*'),
                          ('unresolved-import', 'com.android.purebilibili.core.ui.Mode.MISSING'),
                          ('unresolved-import', 'com.android.purebilibili.core.ui.userVipPink')], found(result))

    def test_android_sdk_imports_need_a_desktop_shim_and_are_ignored_in_carried_archives(self):
        mini = Mini(self)
        mini.add('d/Shim.kt', 'package android.util\nobject Log { fun d(a: String) = 1 }\n')
        mini.add('g/Use.kt', 'package p\nimport android.util.Log\nimport android.content.Context\nfun u() = 1\n', 'generated')
        mini.add('a/Archive.kt', 'package p\nimport android.content.Context\nimport com.android.purebilibili.R\nfun a() = 1\n',
                 'archive', 'carried')
        result = mini.run()
        self.assertEqual([('unresolved-android-import', 'android.content.Context')], found(result))
        self.assertEqual(['compiled'], [f.scope for f in result.unexpected])

    def test_fully_qualified_reference_without_a_definer_is_the_user_vip_pink_class(self):
        mini = Mini(self)
        mini.add('d/Known.kt', 'package com.android.purebilibili.core.ui.components\nfun known() = 1\nval Local = 2\n',
                 'vendored')
        mini.add('g/Use.kt', '''\
            package p
            fun a() = com.android.purebilibili.core.ui.components.userVipPink()
            fun b() = com.android.purebilibili.core.ui.components.known()
            fun c() = com.android.purebilibili.core.ui.components
                .Local.toString()
            fun com.android.purebilibili.core.ui.components.Local.Receiver.ext() = 1
            val s = "com.android.purebilibili.core.ui.components.inString()"
            // com.android.purebilibili.core.ui.components.inComment()
            ''', 'generated')
        result = mini.run(carried=False)
        self.assertEqual([('unresolved-qualified', 'com.android.purebilibili.core.ui.components.userVipPink')],
                         found(result))
        self.assertEqual(2, result.unexpected[0].line)

    def test_carried_archives_report_unresolved_symbols_but_ignore_generated_android_classes(self):
        mini = Mini(self)
        mini.add('a/Archive.kt', '''\
            package p
            import com.android.purebilibili.core.ui.components.AppPressShiftContent
            import com.android.purebilibili.R
            fun a() = com.android.purebilibili.R.drawable.x
            fun b() = com.android.purebilibili.core.ui.components.userVipPink()
            ''', 'archive', 'carried')
        result = mini.run()
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.ui.components.AppPressShiftContent'),
                          ('unresolved-qualified', 'com.android.purebilibili.core.ui.components.userVipPink')],
                         found(result))
        self.assertEqual({'carried'}, {f.scope for f in result.unexpected})
        self.assertEqual([], mini.run(carried=False).unexpected)

    ORIGINAL = '''\
        package com.android.purebilibili.feature.video.ui.components

        import androidx.compose.runtime.mutableIntStateOf
        import androidx.compose.runtime.getValue
        import com.android.purebilibili.core.ui.AppContentColors

        internal fun SubReplyDetailItem(reply: ReplyItem, expanded: Boolean, onToggle: () -> Unit) {
            val count = mutableIntStateOf(reply.count)
            val color = AppContentColors.error()
            if (expanded) renderReply(reply, count, color, onToggle)
        }
        '''

    def copied(self, imports):
        header = 'package com.android.purebilibili.feature.video.ui.components\n\n' + ''.join(
            'import %s\n' % i for i in imports)
        body = '''\

            internal fun SubReplyDetailItem(reply: ReplyItem, expanded: Boolean, onToggle: () -> Unit) {
                val count = mutableIntStateOf(reply.count)
                val color = AppContentColors.error()
                if (expanded) renderReply(reply, count, color, onToggle)
            }
            '''
        return header + textwrap.dedent(body)

    def test_missing_copied_import_is_the_app_content_colors_and_mutable_int_state_class(self):
        mini = Mini(self)
        mini.add('desktop/upstream-slices/v046/ReplyComponents.kt', self.ORIGINAL, 'archive', 'carried')
        mini.add('d/Defs.kt', 'package com.android.purebilibili.core.ui\nobject AppContentColors { fun error() = 0 }\n',
                 'vendored')
        mini.add('g/Retained.kt', self.copied(['androidx.compose.runtime.getValue']), 'generated')
        result = mini.run(carried=False)
        symbols = [s for c, s in found(result) if c == 'missing-copied-import']
        self.assertEqual(['androidx.compose.runtime.mutableIntStateOf', 'com.android.purebilibili.core.ui.AppContentColors'],
                         symbols)

    def test_copied_imports_are_satisfied_by_explicit_or_covering_star_imports(self):
        for imports in (['androidx.compose.runtime.mutableIntStateOf', 'com.android.purebilibili.core.ui.AppContentColors'],
                        ['androidx.compose.runtime.*', 'com.android.purebilibili.core.ui.*']):
            with self.subTest(imports=imports):
                mini = Mini(self)
                mini.add('desktop/upstream-slices/v046/ReplyComponents.kt', self.ORIGINAL, 'archive', 'carried')
                mini.add('d/Defs.kt', 'package com.android.purebilibili.core.ui\nobject AppContentColors { fun error() = 0 }\n',
                         'vendored')
                mini.add('g/Retained.kt', self.copied(imports), 'generated')
                self.assertEqual([], [f for f in mini.run(carried=False).unexpected
                                      if f.check == 'missing-copied-import'])

    def test_a_dissimilar_declaration_of_the_same_name_is_not_compared_with_the_original(self):
        mini = Mini(self)
        mini.add('desktop/upstream-slices/v046/ReplyComponents.kt', self.ORIGINAL, 'archive', 'carried')
        mini.add('g/Other.kt', '''\
            package com.android.purebilibili.feature.video.ui.components
            internal fun SubReplyDetailItem(reply: ReplyItem, expanded: Boolean, onToggle: () -> Unit) {
                unrelatedA(unrelatedB, unrelatedC, unrelatedD, unrelatedE, unrelatedF, unrelatedG)
                val a = unrelatedH() + unrelatedI() + unrelatedJ() + unrelatedK()
            }
            ''', 'generated')
        self.assertEqual([], [f for f in mini.run(carried=False).unexpected if f.check == 'missing-copied-import'])

    SCOPE_MISTAKE = '''\
        package p
        import androidx.compose.foundation.layout.*

        data class CommentState(val pinnedReplyIds: Set<Long>)

        internal fun Portrait(commentState: CommentState) {
            val pinnedReplyIds = commentState.pinnedReplyIds
            Show(pinnedReplyIds = pinnedReplyIds)
        }

        internal fun Landscape(commentState: CommentState, modifier: Modifier = Modifier) {
            Show(pinnedReplyIds = pinnedReplyIds)
        }
        '''

    def test_name_used_in_a_sibling_declaration_without_being_declared_is_flagged(self):
        mini = Mini(self)
        mini.add('g/Panel.kt', self.SCOPE_MISTAKE, 'generated')
        result = mini.run(carried=False)
        self.assertEqual([('unresolved-name', 'pinnedReplyIds')], found(result))
        self.assertEqual(12, result.unexpected[0].line)
        fixed = self.SCOPE_MISTAKE.replace('internal fun Landscape(commentState: CommentState, modifier',
                                           'internal fun Landscape(commentState: CommentState, pinnedReplyIds: Set<Long>, modifier')
        mini = Mini(self)
        mini.add('g/Panel.kt', fixed, 'generated')
        self.assertEqual([], mini.run(carried=False).unexpected)

    def test_names_from_synthetic_definers_resolve_through_star_imports(self):
        mini = Mini(self)
        mini.add('d/Other.kt', 'package com.bilipai.desktop.diagnostics\nobject Something\n')
        mini.add('g/Use.kt', '''\
            package com.bilipai.desktop
            import com.bilipai.desktop.diagnostics.*
            internal fun use() = AssetHash.sha256
            ''', 'generated')
        self.assertEqual([('unresolved-name', 'AssetHash')], found(mini.run(carried=False)))
        expected = gate.empty_expected()
        expected['synthetic'] = {'com.bilipai.desktop.diagnostics': {'AssetHash'}}
        self.assertEqual([], mini.run(expected, carried=False).unexpected)

    STAR_COMPONENTS = 'package com.android.purebilibili.core.ui.components\nfun known() = 1\n'
    STAR_USER = '''\
        package com.android.purebilibili.feature.x
        import com.android.purebilibili.core.ui.components.*
        internal fun use() {
            known()
            AppPressShiftContent()
            userVipPink()
        }
        '''

    def test_a_star_import_of_a_known_project_package_does_not_resolve_what_only_a_carried_archive_imports(self):
        # the userVipPink / AppPressShiftContent class in star form: the carried archive imports the missing symbols
        # explicitly, the generated file reaches them through a star import of a package that lacks them
        mini = Mini(self)
        mini.add('d/Components.kt', self.STAR_COMPONENTS, 'vendored')
        mini.add('a/Archive.kt', '''\
            package p
            import com.android.purebilibili.core.ui.components.AppPressShiftContent
            import com.android.purebilibili.core.ui.components.userVipPink
            fun a() = 1
            ''', 'archive', 'carried')
        mini.add('g/Use.kt', self.STAR_USER, 'generated')
        self.assertEqual([('unresolved-name', 'AppPressShiftContent'), ('unresolved-name', 'userVipPink')],
                         found(mini.run(carried=False)))
        with_carried = found(mini.run())
        self.assertIn(('unresolved-name', 'AppPressShiftContent'), with_carried)
        self.assertIn(('unresolved-name', 'userVipPink'), with_carried)
        self.assertIn(('unresolved-import', 'com.android.purebilibili.core.ui.components.userVipPink'), with_carried)

    def test_the_same_star_import_resolves_names_the_package_declares_or_that_it_cannot_enumerate(self):
        mini = Mini(self)
        mini.add('d/Components.kt', self.STAR_COMPONENTS + 'fun userVipPink() = 2\nclass AppPressShiftContent\n', 'vendored')
        mini.add('a/Archive.kt', 'package p\nimport com.android.purebilibili.core.ui.components.AppPressShiftContent\n'
                 'import com.android.purebilibili.core.ui.components.userVipPink\nfun a() = 1\n', 'archive', 'carried')
        mini.add('g/Use.kt', self.STAR_USER, 'generated')
        self.assertEqual([], mini.run(carried=False).unexpected)
        # a star import of a library package, or of a project package nothing declares, stays lenient: it may supply
        # the name, and an unknown project star is reported once, as the unresolved import it is
        mini = Mini(self)
        mini.add('a/Archive.kt', 'package p\nimport androidx.compose.foundation.layout.Row\n'
                 'import com.android.purebilibili.core.nothing.Ghost\nfun a() = 1\n', 'archive', 'carried')
        mini.add('g/Use.kt', '''\
            package com.android.purebilibili.feature.x
            import androidx.compose.foundation.layout.*
            import com.android.purebilibili.core.nothing.*
            internal fun use() {
                Row()
                Ghost()
            }
            ''', 'generated')
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.nothing.*')], found(mini.run(carried=False)))

    def test_a_carried_archive_cannot_teach_a_missing_project_symbol_as_an_ambient_name(self):
        # a second archive reaches the missing symbol through the same star import; learning from it must not make the
        # name ambient, or the generated file would be silenced again
        mini = Mini(self)
        mini.add('d/Components.kt', self.STAR_COMPONENTS, 'vendored')
        mini.add('a/Importer.kt', 'package p\nimport com.android.purebilibili.core.ui.components.AppPressShiftContent\n'
                 'import com.android.purebilibili.core.ui.components.userVipPink\nfun a() = 1\n', 'archive', 'carried')
        mini.add('a/Star.kt', '''\
            package q
            import com.android.purebilibili.core.ui.components.*
            internal fun star() {
                AppPressShiftContent()
                userVipPink()
            }
            ''', 'archive', 'carried')
        mini.add('g/Use.kt', self.STAR_USER, 'generated')
        self.assertEqual([('unresolved-name', 'AppPressShiftContent'), ('unresolved-name', 'userVipPink')],
                         found(mini.run(carried=False)))

    def test_a_carried_archive_cannot_teach_a_missing_capitalized_project_symbol_as_an_ambient_name(self):
        # the missing-definer class itself: an upstream snapshot bare-uses MissingThing in its own package without
        # importing it (the same-package definer is not in the universe), and a generated file does the same
        archive = 'package p\ninternal fun carried() { MissingThing() }\n'
        use = 'package p\ninternal fun use() { MissingThing() }\n'
        for carried in (False, True):
            with self.subTest(carried=carried):
                mini = Mini(self)
                mini.add('a/Archive.kt', archive, 'archive', 'carried')
                mini.add('g/Use.kt', use, 'generated')
                result = mini.run(carried=carried)
                self.assertEqual([('unresolved-name', 'MissingThing')], found(result))
                self.assertNotIn('MissingThing', result.ambient)
        # a vendored file is compile-proven: the same bare use there is a stdlib, library or implicit-receiver name
        # (OverlayClip is androidx.compose.animation.SharedTransitionScope.OverlayClip reached through a receiver)
        for origin, source_set in (('vendored', 'main'), ('vendored-other', 'other')):
            with self.subTest(origin=origin):
                mini = Mini(self)
                mini.add('d/Vendored.kt', archive, origin, source_set)
                mini.add('g/Use.kt', use, 'generated')
                result = mini.run(carried=False)
                self.assertEqual([], found(result))
                self.assertIn('MissingThing', result.ambient)

    def test_archives_teach_lowercase_library_names_but_never_a_name_a_project_package_supplies(self):
        # drawBackdrop is a library extension: the archive uses it bare, the project imports it from the library.
        # missingHelper is a project function whose definer is absent: another archive imports it from a project
        # package, so a bare use is the project's and must not be silenced by the archive that uses it bare
        archive = 'package p\ninternal fun carried() { drawBackdrop(); missingHelper() }\n'
        importer = ('package q\nimport com.kyant.backdrop.drawBackdrop\n'
                    'import com.android.purebilibili.core.util.missingHelper\nfun importer() = 1\n')
        use = 'package p\ninternal fun use() { drawBackdrop(); missingHelper() }\n'
        mini = Mini(self)
        mini.add('a/Archive.kt', archive, 'archive', 'carried')
        mini.add('a/Importer.kt', importer, 'archive', 'carried')
        mini.add('g/Use.kt', use, 'generated')
        result = mini.run(carried=False)
        self.assertEqual([('unresolved-name', 'missingHelper')], found(result))
        self.assertIn('drawBackdrop', result.ambient)
        self.assertNotIn('missingHelper', result.ambient)
        # the same helper taught by a vendored file stays ambient: that file compiled, so the helper is not missing
        mini = Mini(self)
        mini.add('d/Vendored.kt', archive, 'vendored')
        mini.add('a/Importer.kt', importer, 'archive', 'carried')
        mini.add('g/Use.kt', use, 'generated')
        self.assertEqual([], found(mini.run(carried=False)))

    def test_ambient_teaching_rules_by_origin_and_case(self):
        providers = {'fromProject': {'com.android.purebilibili.core.util'}, 'fromRoot': {'com.android.purebilibili'},
                     'fromDesktop': {'com.bilipai.desktop.x'}, 'fromLibrary': {'androidx.compose.ui'},
                     'lookalike': {'com.android.purebilibiliX'}}
        teaches = gate.teaches_ambient
        for origin in gate.AMBIENT_CAPITAL_ORIGINS:
            for name in ('Capital', 'lower', 'fromProject', '_x'):
                self.assertTrue(teaches(origin, name, providers), (origin, name))
        self.assertEqual(('vendored', 'vendored-other'), gate.AMBIENT_CAPITAL_ORIGINS)
        for name in ('Capital', 'VISIBLE', '_x', '1x'):
            self.assertFalse(teaches('archive', name, providers), name)
        for name in ('fromProject', 'fromRoot', 'fromDesktop'):
            self.assertFalse(teaches('archive', name, providers), name)
        for name in ('lower', 'fromLibrary', 'lookalike'):
            self.assertTrue(teaches('archive', name, providers), name)

    def test_java_classes_are_declared_but_their_members_are_never_checked(self):
        mini = Mini(self)
        java = mini.directory / 'Palette.java'
        java.write_bytes(b'package com.bilipai.desktop.palette;\npublic final class Palette { }\n')
        mini.sources.append(gate.Source('src/main/java/Palette.java', java, 'desktop', 'main'))
        mini.add('g/Use.kt', '''\
            package p
            import com.bilipai.desktop.palette.Palette
            internal fun use() { Palette.quantize(1) }
            ''', 'generated')
        self.assertEqual([], mini.run(carried=False).unexpected)

    def test_names_that_nothing_in_the_project_knows_are_not_reported_unless_capitalized(self):
        mini = Mini(self)
        mini.add('g/Use.kt', '''\
            package p
            enum class Level { LOW, HIGH }
            sealed interface State { object Idle : State }
            internal fun use(level: Level) {
                val local = 1
                library(local)
                when (level) { Level.LOW -> low(); Level.HIGH -> Mystery.go() }
            }
            fun Level.shout() = shoutImplicit()
            ''', 'generated')
        self.assertEqual([('unresolved-name', 'Mystery')], found(mini.run(carried=False)))

    def test_ambient_names_are_learned_from_the_vendored_corpus(self):
        corpus = '''\
            package q
            internal fun Corpus() { Surface(1) }
            '''
        for with_corpus in (False, True):
            with self.subTest(with_corpus=with_corpus):
                mini = Mini(self)
                if with_corpus:
                    mini.add('app/Corpus.kt', corpus, 'vendored')
                mini.add('g/Use.kt', 'package p\ninternal fun Use() { Surface(2) }\n', 'generated')
                result = mini.run(carried=False, with_corpus=False)
                self.assertEqual([] if with_corpus else [('unresolved-name', 'Surface')], found(result))

    def test_extension_receivers_classes_and_enum_entries_declare_their_members(self):
        mini = Mini(self)
        mini.add('d/Model.kt', 'package p\nclass Style(val color: Int, val scale: Float)\n')
        mini.add('g/Ext.kt', '''\
            package p
            internal fun Style.toStyle(): Int = color + scale.toInt()
            class Holder(val inner: Int) {
                enum class Kind { ONE, TWO }
                fun pick() = if (inner > 0) Kind.ONE else TWO
            }
            ''', 'generated')
        self.assertEqual([], mini.run(carried=False).unexpected)

    MOTION = '''\
        package com.android.purebilibili.core.ui
        data class AppBottomSheetMotion(val enterMillis: Int)
        fun rememberAppBottomSheetMotion(): AppBottomSheetMotion = AppBottomSheetMotion(1)
        '''
    REVEAL = '''\
        package com.android.purebilibili.feature.video.ui.components
        import com.android.purebilibili.core.ui.rememberAppBottomSheetMotion
        internal fun reveal() {
            val sheetMotion = rememberAppBottomSheetMotion()
            val reduced = sheetMotion.reducedMotion || false
            val ok = sheetMotion.enterMillis
        }
        '''

    def test_member_the_type_lacks_is_flagged_and_a_compat_extension_retires_it(self):
        mini = Mini(self)
        mini.add('d/Motion.kt', self.MOTION, 'vendored')
        mini.add('g/Reveal.kt', self.REVEAL, 'generated')
        result = mini.run(carried=False)
        self.assertEqual([('unresolved-member', 'AppBottomSheetMotion.reducedMotion')], found(result))
        mini.add('g/Compat.kt', '''\
            package com.android.purebilibili.feature.video.ui.components
            import com.android.purebilibili.core.ui.AppBottomSheetMotion
            internal val AppBottomSheetMotion.reducedMotion: Boolean get() = false
            ''', 'generated')
        self.assertEqual([], mini.run(carried=False).unexpected)

    def test_members_are_not_checked_on_smart_cast_sealed_shadowed_or_chained_values(self):
        mini = Mini(self)
        mini.add('d/Motion.kt', self.MOTION + '''
            sealed class UiState { class Error(val msg: String) : UiState() }
            ''', 'vendored')
        mini.add('g/Use.kt', '''\
            package com.android.purebilibili.feature.video.ui.components
            import com.android.purebilibili.core.ui.rememberAppBottomSheetMotion
            import com.android.purebilibili.core.ui.UiState
            import com.android.purebilibili.core.ui.AppBottomSheetMotion
            internal fun sealedCast(state: UiState) { if (state is UiState.Error) state.msg }
            internal fun chained() { val m = rememberAppBottomSheetMotion().copy(); m.whatever }
            internal fun shadowed() {
                val m = rememberAppBottomSheetMotion()
                listOf(1).forEach { m -> m.size }
                m.size
            }
            internal fun qualifier() { AppBottomSheetMotion.Companion }
            ''', 'generated')
        self.assertEqual([], mini.run(carried=False).unexpected)

    def test_qualifier_member_of_a_project_object_is_checked_against_its_declaration(self):
        mini = Mini(self)
        mini.add('d/Colors.kt', '''\
            package com.android.purebilibili.core.ui
            object AppContentColors { fun error() = 0 }
            enum class Mode { FAST, SLOW }
            ''', 'vendored')
        mini.add('g/Use.kt', '''\
            package com.android.purebilibili.feature.x
            import com.android.purebilibili.core.ui.AppContentColors
            import com.android.purebilibili.core.ui.Mode
            internal fun use() {
                AppContentColors.error()
                AppContentColors.warning()
                Mode.FAST
                Mode.entries
                Mode.MEDIUM
            }
            ''', 'generated')
        self.assertEqual([('unresolved-member', 'AppContentColors.warning'), ('unresolved-member', 'Mode.MEDIUM')],
                         found(mini.run(carried=False)))

    def test_name_scope_selection_limits_which_sources_the_name_checks_inspect(self):
        mini = Mini(self)
        mini.add('d/Panel.kt', self.SCOPE_MISTAKE)
        self.assertEqual([], mini.run(carried=False).unexpected)
        self.assertEqual([('unresolved-name', 'pinnedReplyIds')], found(mini.run(carried=False, names_scope='all')))
        self.assertEqual([], mini.run(carried=False, names_scope='none').unexpected)

    def test_registered_source_directories_that_are_absent_are_findings_unless_a_skipped_producer_owns_them(self):
        mini = Mini(self)
        mini.add('d/Defs.kt', 'package p\nfun f() = 1\n')
        notes = ['source directory not produced: build/generated/silent',
                 'source directory not produced: build/generated/js-worker',
                 'source directory not produced: build/generated/native-diagnostic-share/kotlin',
                 'source directory missing: desktop/third-party/lib/src']

        def run(allowed):
            return gate.analyze(mini.sources, notes, gate.empty_expected(), gate.Options(carried=False),
                                allowed_unproduced=allowed)

        result = run(['generated/js-worker', 'generated/native-diagnostic-share'])
        self.assertEqual([('producer-output-missing', 'build/generated/silent'),
                          ('producer-output-missing', 'desktop/third-party/lib/src')], found(result))
        self.assertFalse(result.passed)
        self.assertEqual(notes, sorted(result.notes, key=notes.index))   # every note is still printed
        self.assertEqual(['build/generated/js-worker', 'build/generated/native-diagnostic-share/kotlin',
                          'build/generated/silent', 'desktop/third-party/lib/src'],
                         sorted(f.symbol for f in run([]).unexpected))
        self.assertEqual(['desktop/third-party/lib/src'],
                         [f.symbol for f in run(['generated']).unexpected])   # a parent directory covers its children
        listed = dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='producer-output-missing', symbol='build/generated/silent'),
            dict(scope='compiled', check='producer-output-missing', symbol='desktop/third-party/lib/src')])])
        directory = Path(tempfile.mkdtemp(prefix='bp-t-'))
        self.addCleanup(rmtree_force, directory)
        (directory / 'gaps.json').write_bytes(json.dumps(listed).encode('utf8'))
        accepted = gate.analyze(mini.sources, notes, gate.load_expected(directory / 'gaps.json'),
                                gate.Options(carried=False), allowed_unproduced=['generated/js-worker',
                                                                                 'generated/native-diagnostic-share'])
        self.assertTrue(accepted.passed)

    def test_check_selection_runs_only_the_named_checks(self):
        mini = Mini(self)
        mini.add('g/A.kt', 'package p\nimport com.android.purebilibili.core.Gone\ninternal fun same() = 1\n', 'generated')
        mini.add('g/B.kt', 'package p\ninternal fun same() = 2\n', 'generated')
        self.assertEqual(['duplicate-declaration', 'unresolved-import'], checks(mini.run(carried=False)))
        self.assertEqual(['duplicate-declaration'], checks(mini.run(carried=False, checks=['duplicates'])))

    def test_a_mistyped_or_empty_check_selection_or_names_scope_is_a_tool_error(self):
        # a typo used to run zero checks and print PASS
        self.assertEqual(set(gate.CHECK_NAMES), set(gate.CHECK_GROUP.values()))
        self.assertEqual(set(gate.CHECK_NAMES), gate.Options().checks)
        for every in (list(gate.CHECK_NAMES), ['imports'], ['duplicates', 'members']):
            self.assertEqual(set(every), gate.Options(checks=every).checks)
        for bad in (['duplicate'], ['bogus'], [''], [], ['imports', 'impotrs'], ['Imports']):
            with self.subTest(checks=bad), self.assertRaises(gate.ToolError):
                gate.Options(checks=bad)
        self.assertEqual({'imports', 'names'}, gate.parse_checks(' imports , names,'))   # a stray comma is ignored
        self.assertIsNone(gate.parse_checks(None))
        for text in ('', ',', ' , ', 'duplicate', 'bogus', 'imports,bogus'):
            with self.subTest(text=text), self.assertRaises(gate.ToolError):
                gate.parse_checks(text)
        for scope in gate.NAMES_SCOPES:
            self.assertEqual(scope, gate.Options(names_scope=scope).names_scope)
        with self.assertRaises(gate.ToolError):
            gate.Options(names_scope='everything')
        # 'all' stays a library-level option for these tests; the command line does not offer it
        self.assertEqual(('generated', 'none'), gate.CLI_NAMES_SCOPES)


class ExpectedGapsTests(unittest.TestCase):
    def write(self, data):
        directory = Path(tempfile.mkdtemp(prefix='bp-t-'))
        self.addCleanup(rmtree_force, directory)
        path = directory / 'gaps.json'
        path.write_bytes(json.dumps(data).encode('utf8'))
        return path

    def base(self, **extra):
        data = dict(schemaVersion=1, groups=[], latent=[], syntheticDefiners=[])
        data.update(extra)
        return data

    def mini(self):
        mini = Mini(self)
        mini.add('a/Archive.kt', 'package p\nimport com.android.purebilibili.core.ui.components.AppPressShiftContent\n'
                 'fun a() = com.android.purebilibili.core.ui.components.userVipPink()\n', 'archive', 'carried')
        mini.add('d/Defs.kt', 'package com.android.purebilibili.core.ui\nclass AppBottomSheetMotion(val a: Int)\n'
                 'val AppBottomSheetMotion.reducedMotion: Boolean get() = false\nfun relocated() = 1\n')
        return mini

    GROUP = dict(reason='archive reference', closedBy='fnd-identity-badges-vip-pink', gaps=[
        dict(scope='carried', check='unresolved-qualified', symbol='com.android.purebilibili.core.ui.components.userVipPink'),
        dict(scope='carried', check='unresolved-import', symbol='com.android.purebilibili.core.ui.components.AppPressShiftContent')])

    def test_listed_gaps_are_accepted_and_unlisted_ones_fail(self):
        mini = self.mini()
        self.assertFalse(mini.run().passed)
        expected = gate.load_expected(self.write(self.base(groups=[self.GROUP])))
        result = mini.run(expected)
        self.assertTrue(result.passed)
        self.assertEqual(2, len(result.known))
        self.assertEqual([], result.stale)
        partial = dict(self.GROUP, gaps=self.GROUP['gaps'][:1])
        result = mini.run(gate.load_expected(self.write(self.base(groups=[partial]))))
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.ui.components.AppPressShiftContent')],
                         found(result))

    def test_a_listed_gap_that_is_no_longer_observed_is_stale_and_fails(self):
        expected = gate.load_expected(self.write(self.base(groups=[self.GROUP])))
        mini = Mini(self)
        mini.add('d/Defs.kt', 'package com.android.purebilibili.core.ui.components\nfun userVipPink() = 1\n'
                 'class AppPressShiftContent\n')
        result = mini.run(expected)
        self.assertFalse(result.passed)
        self.assertEqual(2, len(result.stale))
        self.assertEqual([], result.unexpected)

    def test_a_gap_can_be_limited_to_paths(self):
        group = dict(reason='r', closedBy='c', gaps=[dict(
            scope='carried', check='unresolved-qualified',
            symbol='com.android.purebilibili.core.ui.components.userVipPink', paths=['other/*'])])
        result = self.mini().run(gate.load_expected(self.write(self.base(groups=[group]))))
        self.assertIn(('unresolved-qualified', 'com.android.purebilibili.core.ui.components.userVipPink'), found(result))
        self.assertEqual(1, len(result.stale))

    PARTIAL_GAPS = [
        dict(scope='carried', check='unresolved-qualified', symbol='com.android.purebilibili.core.ui.components.userVipPink'),
        dict(scope='carried', check='unresolved-import', symbol='com.android.purebilibili.core.ui.components.AppPressShiftContent'),
        dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.Gone'),
        dict(scope='compiled', check='duplicate-declaration', symbol='p.same()'),
        dict(scope='compiled', check='unresolved-name', symbol='Mystery'),
    ]

    def partial_mini(self, with_gone_import=True):
        mini = self.mini()
        mini.add('g/A.kt', 'package p\n%sinternal fun same() = 1\ninternal fun use() { Mystery.go() }\n' % (
            'import com.android.purebilibili.core.Gone\n' if with_gone_import else ''), 'generated')
        mini.add('g/B.kt', 'package p\ninternal fun same() = 2\n', 'generated')
        return mini

    def partial_expected(self):
        return gate.load_expected(self.write(self.base(groups=[dict(reason='r', closedBy='c', gaps=self.PARTIAL_GAPS)])))

    def test_every_partial_run_judges_only_the_expected_gaps_it_could_observe(self):
        expected = self.partial_expected()
        full = self.partial_mini().run(expected)
        self.assertTrue(full.passed, gate.render_text(full))
        self.assertEqual((6, 0), (len(full.known), len(full.stale)))   # the duplicate is reported in both files
        runs = [
            ('no carried archives', dict(carried=False), 4),
            ('duplicates only', dict(checks=['duplicates']), 2),
            ('imports only', dict(checks=['imports']), 2),
            ('no name checks', dict(names_scope='none'), 5),
            ('no name checks and no carried archives', dict(names_scope='none', carried=False), 3),
        ]
        for label, options, known in runs:
            with self.subTest(label):
                result = self.partial_mini().run(expected, **options)
                self.assertEqual([], [(g['check'], g['symbol']) for g in result.stale])
                self.assertEqual([], [(f.check, f.symbol) for f in result.unexpected])
                self.assertEqual(known, len(result.known))
                self.assertTrue(result.passed)

    def test_a_partial_run_still_reports_a_gap_its_own_checks_no_longer_observe_as_stale(self):
        expected = self.partial_expected()
        result = self.partial_mini(with_gone_import=False).run(expected, carried=False)
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.Gone')],
                         [(g['check'], g['symbol']) for g in result.stale])
        self.assertFalse(result.passed)
        # the compiled duplicate is also dropped when only the duplicate check runs: it is observed there and not stale
        self.assertEqual([], self.partial_mini().run(expected, checks=['duplicates']).stale)

    def test_a_producer_failed_gap_is_judged_only_when_the_producers_ran(self):
        group = dict(reason='r', closedBy='c', gaps=[dict(scope='compiled', check='producer-failed', symbol='extractGen')])
        expected = gate.load_expected(self.write(self.base(groups=[group])))
        mini = Mini(self)
        mini.add('d/Defs.kt', 'package p\nfun f() = 1\n')
        ran = gate.analyze(mini.sources, [], expected, gate.Options(carried=False))
        rescan = gate.analyze(mini.sources, [], expected, gate.Options(carried=False), producers_ran=False)
        self.assertEqual(1, len(ran.stale))
        self.assertEqual([], rescan.stale)

    def test_a_run_that_chose_its_producers_judges_no_compiled_gap_and_leaves_retired_seeds_unjudged(self):
        expected = self.partial_expected()
        full = self.partial_mini().run(expected)
        self.assertTrue(full.passed, gate.render_text(full))
        # --only-producers / --skip-producers drop the generated sources, so the compiled-scope gaps that lived in
        # them (the import, the duplicate, the unresolved name) are not seen and must not be called stale
        dropped = self.mini()   # the archive and desktop files only: every generated file belongs to a left-out producer
        chosen = dropped.run(expected, producers_partial=True)
        self.assertEqual([], [(g['check'], g['symbol']) for g in chosen.stale])
        self.assertTrue(chosen.passed, gate.render_text(chosen))
        complete = dropped.run(expected)
        self.assertEqual(['unresolved-import', 'duplicate-declaration', 'unresolved-name'],
                         [g['check'] for g in complete.stale])
        self.assertFalse(complete.passed)
        # carried gaps stay judged: leaving a producer out only removes definers, so a carried gap cannot vanish
        carried_gone = Mini(self)
        carried_gone.add('d/Defs.kt', 'package com.android.purebilibili.core.ui.components\nfun userVipPink() = 1\n'
                         'class AppPressShiftContent\n')
        stale = carried_gone.run(gate.load_expected(self.write(self.base(groups=[self.GROUP]))), producers_partial=True)
        self.assertEqual(2, len(stale.stale))
        self.assertFalse(stale.passed)
        for gap in expected['gaps']:
            self.assertEqual(gap['scope'] == 'carried', gate.gap_observable(gap, gate.Options(), True, True), gap)
            self.assertTrue(gate.gap_observable(gap, gate.Options(), True, False), gap)

    def test_a_retired_seed_is_unjudged_not_lost_when_the_producer_of_its_definer_was_left_out(self):
        seed = dict(symbol='reducedMotion', kind='member', owner='AppBottomSheetMotion', reason='r', evidence='e',
                    retiredBy='the shim', guardedBy='this test', closedBy='c', closedByEvidence='roadmap')
        expected = gate.load_expected(self.write(self.base(groups=[self.GROUP], retiredSeeds=[seed])))
        lost = Mini(self)
        lost.add('a/Archive.kt', 'package p\nimport com.android.purebilibili.core.ui.components.AppPressShiftContent\n'
                 'fun a() = com.android.purebilibili.core.ui.components.userVipPink()\n', 'archive', 'carried')
        lost.add('d/Defs.kt', 'package com.android.purebilibili.core.ui\nclass AppBottomSheetMotion(val a: Int)\n')
        complete = lost.run(expected)
        self.assertEqual([('reducedMotion', 'lost')], [(e['symbol'], e['status']) for e in complete.retired])
        self.assertFalse(complete.passed)
        chosen = lost.run(expected, producers_partial=True)
        self.assertEqual([('reducedMotion', 'unjudged')], [(e['symbol'], e['status']) for e in chosen.retired])
        self.assertTrue(chosen.passed, gate.render_text(chosen))
        text = gate.render_text(chosen)
        self.assertIn('UNJUDGED AppBottomSheetMotion.reducedMotion  (retiredBy the shim)', text)
        self.assertIn('unjudged: 1', text)
        self.assertEqual('unjudged', gate.result_to_dict(chosen)['retired'][0]['status'])
        # a definer that exists is DEFINED in a partial run too
        defined = self.mini().run(expected, producers_partial=True)
        self.assertEqual([('reducedMotion', 'defined')], [(e['symbol'], e['status']) for e in defined.retired])
        self.assertNotIn('unjudged', gate.render_text(defined))

    def test_retired_seeds_must_keep_a_definer(self):
        seed = dict(symbol='reducedMotion', kind='member', owner='AppBottomSheetMotion', reason='r', evidence='e',
                    retiredBy='the shim', guardedBy='this test', closedBy='c', closedByEvidence='roadmap')
        expected = gate.load_expected(self.write(self.base(groups=[self.GROUP], retiredSeeds=[seed])))
        result = self.mini().run(expected)
        self.assertEqual([('reducedMotion', 'defined')], [(e['symbol'], e['status']) for e in result.retired])
        self.assertTrue(result.passed)
        self.assertIn('DEFINED AppBottomSheetMotion.reducedMotion  (retiredBy the shim)', gate.render_text(result))
        self.assertEqual('defined', gate.result_to_dict(result)['retired'][0]['status'])
        lost = Mini(self)
        lost.add('a/Archive.kt', 'package p\nimport com.android.purebilibili.core.ui.components.AppPressShiftContent\n'
                 'fun a() = com.android.purebilibili.core.ui.components.userVipPink()\n', 'archive', 'carried')
        lost.add('d/Defs.kt', 'package com.android.purebilibili.core.ui\nclass AppBottomSheetMotion(val a: Int)\n')
        result = lost.run(expected)
        self.assertEqual([('reducedMotion', 'lost')], [(e['symbol'], e['status']) for e in result.retired])
        self.assertFalse(result.passed)
        self.assertEqual([], result.unexpected)
        self.assertIn('LOST    AppBottomSheetMotion.reducedMotion', gate.render_text(result))

    def test_closed_by_evidence_is_validated_inherited_and_printed_when_only_proposed(self):
        proposed = dict(self.GROUP, closedByEvidence='proposed')
        expected = gate.load_expected(self.write(self.base(groups=[proposed])))
        self.assertEqual({'proposed'}, {g['closedByEvidence'] for g in expected['gaps']})
        override = dict(self.GROUP, closedByEvidence='roadmap', gaps=[
            dict(self.GROUP['gaps'][0], closedByEvidence='unassigned'), self.GROUP['gaps'][1]])
        self.assertEqual(['unassigned', 'roadmap'],
                         [g['closedByEvidence'] for g in gate.load_expected(self.write(self.base(groups=[override])))['gaps']])
        result = self.mini().run(expected)
        self.assertIn('closedBy fnd-identity-badges-vip-pink (proposed)', gate.render_text(result))
        self.assertEqual({'proposed'}, {k['closedByEvidence'] for k in gate.result_to_dict(result)['known']})
        quiet = gate.render_text(self.mini().run(gate.load_expected(self.write(self.base(groups=[self.GROUP])))))
        self.assertNotIn('(proposed)', quiet)
        with self.assertRaises(gate.ToolError):
            gate.load_expected(self.write(self.base(groups=[dict(self.GROUP, closedByEvidence='certain')])))

    def test_latent_entries_must_stay_undefined_and_are_verified_three_ways(self):
        latent = [
            dict(symbol='relocated', kind='declaration', package='com.android.purebilibili.data.model',
                 reason='r', evidence='e', closedBy='c'),
            dict(symbol='relocated', kind='declaration', package='com.android.purebilibili.core.ui',
                 reason='r', evidence='e', closedBy='c'),
            dict(symbol='reducedMotion', kind='member', owner='AppBottomSheetMotion', reason='r', evidence='e', closedBy='c'),
            dict(symbol='parseFailed', kind='identifier', reason='r', evidence='e', closedBy='c'),
            dict(symbol='failedSegmentCount', kind='identifier', reason='r', evidence='e', closedBy='c'),
        ]
        mini = self.mini()
        mini.add('d/Reads.kt', 'package p\nfun read(x: Any) = x.toString() // parseFailed in a comment is not a use\n'
                 'val s = "failedSegmentCount in a string"\nval parseFailed = 1\n')
        expected = gate.load_expected(self.write(self.base(groups=[self.GROUP], latent=latent)))
        result = mini.run(expected)
        status = [(e['symbol'], e.get('package') or e.get('owner') or e['kind'], e['status']) for e in result.latent]
        self.assertEqual([
            ('relocated', 'com.android.purebilibili.data.model', 'open'),
            ('relocated', 'com.android.purebilibili.core.ui', 'defined'),
            ('reducedMotion', 'AppBottomSheetMotion', 'defined'),
            ('parseFailed', 'identifier', 'defined'),
            ('failedSegmentCount', 'identifier', 'open')], status)
        self.assertFalse(result.passed)

    def test_malformed_expected_files_are_tool_errors(self):
        bad = [
            self.base(schemaVersion=2),
            self.base(groups=[dict(reason='', closedBy='c', gaps=[])]),
            self.base(groups=[dict(reason='r', closedBy='c', gaps=[dict(scope='nowhere', check='unresolved-import', symbol='x')])]),
            self.base(groups=[dict(reason='r', closedBy='c', gaps=[dict(scope='carried', check='made-up', symbol='x')])]),
            self.base(groups=[dict(reason='r', closedBy='c', gaps=[dict(scope='carried', check='unresolved-import', symbol='x')] * 2)]),
            self.base(latent=[dict(symbol='x', reason='r', closedBy='c')]),
            self.base(latent=[dict(symbol='x', kind='telepathy', reason='r', evidence='e', closedBy='c')]),
            self.base(syntheticDefiners=[dict(package='p', names=[], reason='r')]),
        ]
        for data in bad:
            with self.subTest(data=data), self.assertRaises(gate.ToolError):
                gate.load_expected(self.write(data))
        with self.assertRaises(gate.ToolError):
            gate.load_expected(Path('does-not-exist.json'))
        self.assertEqual(gate.empty_expected(), gate.load_expected(None))

    def test_the_committed_expected_gaps_file_is_well_formed_and_documents_every_gap(self):
        expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        self.assertGreater(len(expected['gaps']), 40)
        for gap in expected['gaps']:
            self.assertTrue(gap['reason'].strip() and gap['closedBy'].strip(), gap)
            self.assertIn(gap['scope'], gate.KNOWN_SCOPES)
        for entry in expected['latent']:
            self.assertTrue(entry['evidence'].strip(), entry)
        raw = (REPO / gate.EXPECTED_GAPS_FILE).read_bytes()
        self.assertNotIn(b'\r', raw)
        self.assertEqual(raw, (json.dumps(json.loads(raw), indent=2, ensure_ascii=False) + '\n').encode('utf8'))

    def test_roadmap_seed_symbols_are_all_accounted_for(self):
        expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        listed = ' '.join([g['symbol'] for g in expected['gaps']] + [e['symbol'] for e in expected['latent']] +
                          [e['symbol'] for e in expected['retired']])
        for seed in ('userVipPink', 'resolveVideoDisplayProgressState', 'resolveWatchLaterDisplayProgressState',
                     'resolveVideoCardStatsTexts', 'AppPressShiftContent', 'HomeOverlayPillButton',
                     'resolveWallpaperGlassAlpha', 'parseFailed', 'failedSegmentCount', 'invalidateDanmakuSegment',
                     'ForegroundSettleBudget', 'reducedMotion'):
            with self.subTest(seed=seed):
                self.assertIn(seed, listed)
        # AppBottomSheetMotion.reducedMotion is the twelfth seed: the generated compat extension already retires it,
        # and the expected-gaps file says so instead of omitting it
        # the interim compat extension is gone: the real member comes from v046-sheet-motion
        self.assertNotIn('COMPAT_REDUCED_MOTION', (TOOLS / 'v046_comment_list.py').read_text(encoding='utf8'))
        self.assertEqual([('AppBottomSheetMotion', 'reducedMotion')],
                         [(e['owner'], e['symbol']) for e in expected['retired']
                          if e.get('owner') and e['symbol'] not in DANMAKU_SEEDS])
        self.assertEqual(sorted([name for _package, name in CLOSED_SEEDS] + list(DANMAKU_SEEDS)),
                         sorted(e['symbol'] for e in expected['retired'] if not e.get('owner') or e['symbol'] in DANMAKU_SEEDS))

    def test_the_retired_seed_names_its_shim_its_guards_and_the_slice_that_replaces_it(self):
        expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        seed = expected['retired'][0]
        self.assertEqual('member', seed['kind'])
        self.assertIn('v046-sheet-motion', seed['retiredBy'])
        self.assertIn('v046_sheet_motion.py', seed['retiredBy'])
        self.assertEqual('fnd-sheet-motion-and-glyph', seed['closedBy'])
        self.assertIn('d6bf8b16', seed['evidence'])
        this_file = Path(__file__).read_text(encoding='utf8')
        for guard in ('test_member_the_type_lacks_is_flagged_and_a_compat_extension_retires_it',
                      'test_retired_seeds_must_keep_a_definer',
                      'test_the_compat_extension_retires_the_twelfth_seed_in_the_real_tree'):
            self.assertIn(guard, seed['guardedBy'])
            self.assertIn('def %s(' % guard, this_file)
        shim = (TOOLS / 'v046_comment_list.py').read_text(encoding='utf8')
        self.assertNotIn('DesktopOriginalCommentMotionCompat.kt', shim)
        self.assertIn('val reducedMotion: Boolean',
                      (REPO / 'desktop/upstream-slices/v046-sheet-motion/AppSheetComponents.kt').read_text(encoding='utf8'))

    def test_every_listed_symbol_says_how_firm_its_closing_slice_is(self):
        expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        for entry in expected['gaps'] + expected['latent'] + expected['retired']:
            with self.subTest(symbol=entry['symbol']):
                self.assertIn(entry.get('closedByEvidence'), gate.KNOWN_EVIDENCE)
                unowned = entry['closedBy'].startswith('none')
                self.assertEqual(unowned, entry['closedByEvidence'] == 'unassigned')
                if entry['closedByEvidence'] == 'proposed':
                    self.assertIn('inference', entry['reason'])
        # the roadmap names the proposed groups' slices but not these exact symbols; a reader must see that
        proposed = {g['symbol'].rsplit('.', 1)[-1] for g in expected['gaps'] if g['closedByEvidence'] == 'proposed'}
        for name in ('rememberBangumiSubtitles', 'shouldAttemptInstantArticle',
                     'readSeedWallpaperColorScheme', 'sanitizeWallpaperTransform', 'WallpaperPaletteRequest',
                     'isScheduledDarkTheme', 'normalizeDarkThemeSchedule'):
            self.assertIn(name, proposed)
        roadmap_named = {g['symbol'].rsplit('.', 1)[-1] for g in expected['gaps'] if g['closedByEvidence'] == 'roadmap'}
        for name in DANMAKU_SEEDS:
            self.assertNotIn(name, roadmap_named)
        for _package, name in CLOSED_SEEDS:
            self.assertNotIn(name, roadmap_named)

    def test_the_wallpaper_transform_gaps_say_the_slice_names_only_the_policy_file(self):
        # wallpaper-foundation-and-adjust names WallpaperTransformPolicy.kt (upstreamFiles, firstStep) but not the two
        # symbols that file declares; the label stays 'proposed' and the reason must say exactly that, not "unnamed"
        expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        for name in ('WallpaperTransform', 'sanitizeWallpaperTransform'):
            with self.subTest(symbol=name):
                gaps = [g for g in expected['gaps'] if g['symbol'].rsplit('.', 1)[-1] == name]
                self.assertEqual(1, len(gaps))
                reason = gaps[0]['reason']
                self.assertEqual('proposed', gaps[0]['closedByEvidence'])
                self.assertEqual('wallpaper-foundation-and-adjust', gaps[0]['closedBy'])
                self.assertIn('names WallpaperTransformPolicy.kt', reason)
                self.assertIn('names neither symbol', reason)
                self.assertIn('inference', reason)
                # the "roadmap text does not name ..." list must not also claim these two are unnamed
                unnamed = reason.split('does not name ', 1)[1].split('. For ', 1)[0]
                self.assertNotIn(name, unnamed)
                self.assertIn('readSeedWallpaperColorScheme', unnamed)

    def test_report_states_the_limits_the_result_and_is_deterministic(self):
        expected = gate.load_expected(self.write(self.base(groups=[self.GROUP])))
        first = gate.render_text(self.mini().run(expected))
        second = gate.render_text(self.mini().run(expected))
        self.assertEqual(first, second)
        self.assertIn('RESULT: PASS', first)
        for limit in gate.HEURISTIC_LIMITS:
            self.assertIn(limit, first)
        self.assertIn('not a compile', first)
        failing = gate.render_text(self.mini().run())
        self.assertIn('RESULT: FAIL', failing)
        self.assertIn('UNEXPECTED FINDINGS', failing)
        payload = gate.result_to_dict(self.mini().run(expected))
        self.assertEqual('pass', payload['result'])
        self.assertEqual(payload, json.loads(json.dumps(payload)))


GRADLE_MINI = '''\
val repositoryRoot = projectDir.parentFile
val generatedUpstream = layout.buildDirectory.dir("generated/upstream")
val extractGen by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/gen.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/gen").get().asFile.absolutePath)
    outputs.dir(layout.buildDirectory.dir("generated/gen"))
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedUpstream)
    kotlin.srcDir(layout.buildDirectory.dir("generated/gen"))
}
'''

GEN_PY = '''\
import argparse, subprocess, sys
from pathlib import Path
cli = argparse.ArgumentParser()
cli.add_argument('--repo', type=Path, required=True)
cli.add_argument('--output', type=Path, required=True)
args = cli.parse_args()
if %(fail)s:
    sys.stderr.write('producer exploded\\n')
    sys.exit(3)
marker = subprocess.check_output(['git', 'show', '%(commit)s:marker.txt'], cwd=args.repo).decode().strip()
target = args.output / 'com/example/Generated.kt'
target.parent.mkdir(parents=True)
target.write_text('package com.example\\nimport com.android.purebilibili.core.ui.Missing\\nfun generated() = "' + marker + '"\\n')
# a producer must not be able to touch the checkout it was started from
(args.repo / 'desktop/tools/WRITTEN-BY-PRODUCER.txt').write_text('stray')
'''


def git(root, *args):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-c', 'user.name=t', '-c', 'user.email=t@example.invalid',
                           '-C', str(root), *args], check=True, capture_output=True).stdout.decode().strip()


GRADLE_SILENT = GRADLE_MINI + '''\
val extractSilent by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/silent.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/silent").get().asFile.absolutePath)
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/silent"))
}
'''


def make_repo(test, fail=False, silent=False):
    root = Path(tempfile.mkdtemp(prefix='bp-t-repo-'))
    test.addCleanup(rmtree_force, root)
    git(root, 'init', '-q')
    (root / 'marker.txt').write_bytes(b'pinned upstream text\n')
    git(root, 'add', '.')
    git(root, 'commit', '-q', '-m', 'marker')
    commit = git(root, 'rev-parse', 'HEAD')
    files = {
        'desktop/build.gradle.kts': GRADLE_SILENT if silent else GRADLE_MINI,
        'desktop/upstream-sources.json': json.dumps(dict(sources=[], resources=[])),
        'desktop/tools/v025-canonical-sources.json': json.dumps(dict(sourceRoots=['app/'], paths={}, previousPaths={})),
        'desktop/tools/gen.py': GEN_PY % dict(fail=repr(fail), commit=commit),
        'desktop/src/main/kotlin/com/example/Main.kt': 'package com.example\nfun main() = println(generated())\n',
        'desktop/upstream-slices/v1/Archive.kt': 'package x\nimport com.android.purebilibili.core.ui.GoneInArchive\n',
        'app/README.txt': 'vendored root placeholder\n',
    }
    if silent:
        files['desktop/tools/silent.py'] = 'import sys\nsys.exit(0)  # a producer that succeeds and emits nothing\n'
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode('utf8'))
    git(root, 'add', '.')
    git(root, 'commit', '-q', '-m', 'tree')
    return root


def run_cli(root, *argv):
    """gate.main on `root`; returns (exit code, stdout text, stderr text)."""
    buffer = io.TextIOWrapper(io.BytesIO(), encoding='utf8')
    with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(io.StringIO()) as errors:
        code = gate.main(['--repo', str(root), '--quiet', *argv])
    buffer.flush()
    return code, buffer.buffer.getvalue().decode('utf8'), errors.getvalue()


class FixtureCleanupTests(unittest.TestCase):
    def test_fixture_cleanup_removes_a_git_repository_whose_objects_are_read_only(self):
        root = make_repo(self)
        self.assertTrue((root / '.git/objects').is_dir())
        rmtree_force(root)
        self.assertFalse(root.exists())
        rmtree_force(root)   # a path that is already gone is not an error

    def test_the_gates_own_scratch_cleanup_does_the_same(self):
        root = make_repo(self)
        gate._remove_tree(root)
        self.assertFalse(root.exists())
        gate._remove_tree(root)

    def test_a_finished_fixture_leaves_nothing_behind(self):
        # the cleanups registered by make_repo and Mini run when this test ends; the directories are removed by then
        holder = {}

        class Inner(unittest.TestCase):
            def runTest(self):
                holder['repo'] = make_repo(self)
                holder['mini'] = Mini(self).directory

        result = unittest.TestResult()
        Inner().run(result)
        self.assertTrue(result.wasSuccessful())
        self.assertFalse(holder['repo'].exists())
        self.assertFalse(holder['mini'].exists())


class ScratchPathTests(unittest.TestCase):
    @staticmethod
    def path_of_length(length):
        base = os.path.abspath(os.sep + 'x')
        return os.sep + 'x' * (1 + length - len(base))

    def test_a_mirror_root_beyond_the_producer_limit_is_refused_with_the_real_cause(self):
        limit = gate.MAX_SCRATCH_ROOT
        edge = self.path_of_length(limit)
        self.assertEqual(limit, len(os.path.abspath(edge)))
        gate.check_scratch_root(edge, windows=True)
        with self.assertRaisesRegex(gate.ToolError, 'MAX_PATH') as caught:
            gate.check_scratch_root(self.path_of_length(limit + 1), windows=True)
        message = str(caught.exception)
        for part in (str(limit + 1), str(gate.LONGEST_PRODUCER_PATH), 'C:/bp-sc', '--scratch'):
            self.assertIn(part, message)
        gate.check_scratch_root(self.path_of_length(limit + 100), windows=False)

    def test_the_limit_leaves_room_for_the_longest_path_a_producer_writes(self):
        self.assertEqual(gate.WINDOWS_MAX_PATH, gate.MAX_SCRATCH_ROOT + 1 + gate.LONGEST_PRODUCER_PATH +
                         gate.PRODUCER_PATH_MARGIN)
        self.assertGreater(gate.MAX_SCRATCH_ROOT, 50, 'the default temp mirror root must stay usable')

    @unittest.skipUnless(os.name == 'nt', 'the producer path limit is a Windows rule')
    def test_a_deep_scratch_directory_is_refused_before_anything_is_created(self):
        root = make_repo(self)
        deep = Path(tempfile.gettempdir()) / ('d' * 90)
        self.assertGreater(len(str(deep / 'r')), gate.MAX_SCRATCH_ROOT)
        with self.assertRaisesRegex(gate.ToolError, 'MAX_PATH'):
            gate.run_gate(root, scratch=deep, expected_path=None, options=gate.Options(carried=False))
        with self.assertRaisesRegex(gate.ToolError, 'MAX_PATH'):
            gate.make_scratch_repo(root, deep)
        self.assertFalse(os.path.exists(str(deep)))
        code, _, errors = run_cli(root, '--scratch', str(deep))
        self.assertEqual(2, code)
        self.assertIn('MAX_PATH', errors)

    @unittest.skipUnless(os.name == 'nt', 'long-path forms are a Windows concern')
    def test_sources_below_a_deep_root_are_still_found_and_read(self):
        base = Path(tempfile.mkdtemp(prefix='bp-t-'))
        self.addCleanup(rmtree_force, gate._long(base))   # the tree is deeper than a plain path can reach
        deep = base / ('a' * 100) / ('b' * 100) / ('c' * 60)
        os.makedirs(gate._long(deep))
        target = gate._long(deep / 'Deep.kt')
        with open(target, 'wb') as handle:
            handle.write(b'package deep\nfun f() = 1\n')
        self.assertGreater(len(str(deep / 'Deep.kt')), 260)
        found_files = gate.walk_sources(deep)
        self.assertEqual(1, len(found_files))
        self.assertEqual('deep', gate.analyze_source(gate.Source('Deep.kt', found_files[0], 'desktop', 'main')).package)


class ProducerMirrorTests(unittest.TestCase):
    def test_producers_run_in_a_mirror_findings_are_reported_and_the_checkout_is_untouched(self):
        root = make_repo(self)
        before = gate.repo_state(root)
        self.assertEqual('', before)
        result, producers = gate.run_gate(root, expected_path=None, options=gate.Options(carried=False))
        self.assertEqual('', gate.repo_state(root))
        self.assertFalse((root / 'desktop/tools/WRITTEN-BY-PRODUCER.txt').exists())
        self.assertFalse((root / 'desktop/build').exists())
        self.assertEqual([('extractGen', 0)], [(r['task'], r['returncode']) for r in producers['results']])
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.ui.Missing')], found(result))
        self.assertEqual(['build/generated/gen/com/example/Generated.kt'], [f.file for f in result.unexpected])
        self.assertEqual({'main/desktop': 1, 'main/generated': 1}, {k: v for k, v in result.counts.items()
                                                                     if not k.startswith(('other', 'carried'))})

    def test_the_gate_is_deterministic_and_removes_its_scratch_directory(self):
        root = make_repo(self)
        scratch = Path(tempfile.mkdtemp(prefix='bp-t-scratch-'))
        self.addCleanup(rmtree_force, scratch)
        texts = []
        for _ in range(2):
            result, producers = gate.run_gate(root, scratch=scratch / 'run', expected_path=None,
                                              options=gate.Options(carried=True))
            texts.append(gate.render_text(result, producers))
            self.assertFalse((scratch / 'run').exists())
        self.assertEqual(texts[0], texts[1])
        self.assertIn('GoneInArchive', texts[0])

    def test_a_failing_producer_is_a_finding_and_known_gaps_apply_to_it(self):
        root = make_repo(self, fail=True)
        result, _ = gate.run_gate(root, expected_path=None, options=gate.Options(carried=False))
        self.assertIn(('producer-failed', 'extractGen'), found(result))
        self.assertNotIn('producer-output-missing', checks(result))   # the failure already explains the absent directory
        self.assertIn('producer exploded', result.unexpected[0].detail)
        group = dict(reason='r', closedBy='c', gaps=[dict(scope='compiled', check='producer-failed', symbol='extractGen')])
        data = Path(tempfile.mkdtemp(prefix='bp-t-')) / 'gaps.json'
        self.addCleanup(rmtree_force, data.parent)
        data.write_bytes(json.dumps(dict(schemaVersion=1, groups=[group])).encode('utf8'))
        result, _ = gate.run_gate(root, expected_path=data, options=gate.Options(carried=False))
        self.assertTrue(result.passed)

    def test_command_line_exit_codes_and_json_report(self):
        root = make_repo(self)
        out = Path(tempfile.mkdtemp(prefix='bp-t-'))
        self.addCleanup(rmtree_force, out)

        def run(*argv):
            buffer = io.TextIOWrapper(io.BytesIO(), encoding='utf8')
            with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(io.StringIO()) as errors:
                code = gate.main(['--repo', str(root), '--quiet', *argv])
            buffer.flush()
            return code, buffer.buffer.getvalue().decode('utf8'), errors.getvalue()

        code, text, _ = run('--expected-gaps', 'none', '--no-carried', '--json', str(out / 'r.json'))
        self.assertEqual(1, code)
        self.assertIn('RESULT: FAIL', text)
        payload = json.loads((out / 'r.json').read_bytes())
        self.assertEqual('fail', payload['result'])
        self.assertEqual('com.android.purebilibili.core.ui.Missing', payload['unexpected'][0]['symbol'])
        gaps = out / 'gaps.json'
        gaps.write_bytes(json.dumps(dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.ui.Missing'),
            dict(scope='carried', check='unresolved-import', symbol='com.android.purebilibili.core.ui.GoneInArchive')])])).encode('utf8'))
        code, text, _ = run('--expected-gaps', str(gaps))
        self.assertEqual(0, code)
        self.assertIn('RESULT: PASS', text)
        code, _, errors = run('--expected-gaps', str(out / 'missing.json'))
        self.assertEqual(2, code)
        self.assertIn('ERROR', errors)
        code, text, _ = run('--list-producers')
        self.assertEqual(0, code)
        self.assertEqual('extractGen tools/gen.py', text.splitlines()[0])

    def test_candidate_files_are_read_from_git_objects_and_checked_like_carried_files(self):
        root = make_repo(self)
        planned = root / 'planned/Planned.kt'
        planned.parent.mkdir()
        planned.write_bytes(b'package planned\nimport com.android.purebilibili.data.model.resolveThing\n')
        git(root, 'add', '.')
        git(root, 'commit', '-q', '-m', 'planned')
        commit = git(root, 'rev-parse', 'HEAD')
        result, _ = gate.run_gate(root, expected_path=None, options=gate.Options(carried=False),
                                  candidate_paths=['planned/Planned.kt'], candidate_commit=commit)
        symbols = [(f.check, f.symbol, f.scope) for f in result.unexpected]
        self.assertIn(('unresolved-import', 'com.android.purebilibili.data.model.resolveThing', 'carried'), symbols)
        self.assertNotIn('GoneInArchive', ' '.join(s for _, s, _ in symbols))
        with self.assertRaises(gate.ToolError):
            gate.run_gate(root, expected_path=None, candidate_paths=['planned/Planned.kt'], candidate_commit='HEAD')
        with self.assertRaises(gate.ToolError):
            gate.run_gate(root, expected_path=None, candidate_paths=['../escape.kt'], candidate_commit=commit)

    def test_the_mirror_excludes_build_output_and_keeps_the_object_store_readable(self):
        root = make_repo(self)
        (root / 'desktop/build/generated').mkdir(parents=True)
        (root / 'desktop/build/generated/stale.kt').write_bytes(b'package stale\n')
        scratch = Path(tempfile.mkdtemp(prefix='bp-t-'))
        self.addCleanup(rmtree_force, scratch)
        mirror, _ = gate.make_scratch_repo(root, scratch)
        self.assertFalse((mirror / 'desktop/build').exists())
        self.assertTrue((mirror / 'desktop/tools/gen.py').is_file())
        self.assertEqual('pinned upstream text', gate.git_text(mirror, 'show', git(root, 'rev-list', '--max-parents=0', 'HEAD') + ':marker.txt').strip())

    def test_a_registered_directory_nobody_produced_fails_unless_its_producer_was_skipped(self):
        root = make_repo(self, silent=True)
        options = gate.Options(carried=False)
        result, producers = gate.run_gate(root, expected_path=None, options=options)
        self.assertEqual(['extractGen', 'extractSilent'], sorted(r['task'] for r in producers['results']))
        self.assertEqual([('producer-output-missing', 'build/generated/silent'),
                          ('unresolved-import', 'com.android.purebilibili.core.ui.Missing')], found(result))
        self.assertFalse(result.passed)
        self.assertEqual(['build/generated/silent'], [f.symbol for f in result.unexpected if f.check == 'producer-output-missing'])
        for label, arguments in (('skipped by request', dict(skip=['extractSilent'])),
                                 ('left out by --only-producers', dict(only=['extractGen']))):
            with self.subTest(label):
                result, _ = gate.run_gate(root, expected_path=None, options=options, **arguments)
                self.assertNotIn('producer-output-missing', checks(result))
                self.assertIn('source directory not produced: build/generated/silent', result.notes)
        listed = dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='producer-output-missing', symbol='build/generated/silent'),
            dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.ui.Missing')])])
        gaps = Path(tempfile.mkdtemp(prefix='bp-t-')) / 'gaps.json'
        self.addCleanup(rmtree_force, gaps.parent)
        gaps.write_bytes(json.dumps(listed).encode('utf8'))
        self.assertTrue(gate.run_gate(root, expected_path=gaps, options=options)[0].passed)

    def test_a_kept_mirror_is_rescanned_without_judging_producer_gaps(self):
        root = make_repo(self)
        scratch = Path(tempfile.mkdtemp(prefix='bp-t-scratch-'))
        self.addCleanup(rmtree_force, scratch)
        options = gate.Options(carried=False)
        first, _ = gate.run_gate(root, scratch=scratch / 'kept', keep_scratch=True, expected_path=None, options=options)
        self.assertTrue((scratch / 'kept/r/desktop/build/generated/gen/com/example/Generated.kt').is_file())
        listed = dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.ui.Missing'),
            dict(scope='compiled', check='producer-failed', symbol='extractGen')])])
        gaps = scratch / 'gaps.json'
        gaps.write_bytes(json.dumps(listed).encode('utf8'))
        again, producers = gate.run_gate(root, mirror=scratch / 'kept/r', expected_path=gaps, options=options)
        self.assertIsNone(producers)
        self.assertTrue(again.passed, gate.render_text(again))
        self.assertEqual([('unresolved-import', 'com.android.purebilibili.core.ui.Missing')], found(first))
        self.assertEqual([], found(again))   # the same finding, now a listed gap
        live, _ = gate.run_gate(root, expected_path=gaps, options=options)
        self.assertEqual(['extractGen'], [g['symbol'] for g in live.stale])
        self.assertFalse(live.passed)

    def test_a_scratch_directory_that_already_holds_files_keeps_them_and_a_kept_mirror_is_never_overwritten(self):
        root = make_repo(self)
        scratch = Path(tempfile.mkdtemp(prefix='bp-t-scratch-'))
        self.addCleanup(rmtree_force, scratch)
        (scratch / 'keep.txt').write_bytes(b'precious')
        gate.run_gate(root, scratch=scratch, expected_path=None, options=gate.Options(carried=False))
        self.assertEqual(b'precious', (scratch / 'keep.txt').read_bytes())
        self.assertFalse((scratch / 'r').exists())
        gate.run_gate(root, scratch=scratch, keep_scratch=True, expected_path=None, options=gate.Options(carried=False))
        self.assertTrue((scratch / 'r/desktop/tools/gen.py').is_file())
        with self.assertRaisesRegex(gate.ToolError, 'already contains'):
            gate.run_gate(root, scratch=scratch, expected_path=None, options=gate.Options(carried=False))
        self.assertTrue((scratch / 'r/desktop/tools/gen.py').is_file(), 'the refused run must not delete the kept mirror')
        self.assertEqual(b'precious', (scratch / 'keep.txt').read_bytes())

    def test_partial_command_line_runs_against_the_default_expected_gaps_file_report_no_stale_gaps(self):
        root = make_repo(self)
        listed = dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.ui.Missing'),
            dict(scope='carried', check='unresolved-import', symbol='com.android.purebilibili.core.ui.GoneInArchive')])])
        (root / gate.EXPECTED_GAPS_FILE).write_bytes(json.dumps(listed).encode('utf8'))
        self.assertEqual(0, run_cli(root)[0])
        for argv in (['--no-carried'], ['--checks', 'duplicates'], ['--checks', 'qualified'], ['--checks', 'imports'],
                     ['--names-scope', 'none'], ['--no-carried', '--checks', 'imports']):
            with self.subTest(argv=argv):
                code, text, _ = run_cli(root, *argv)
                self.assertEqual(0, code, text)
                self.assertIn('stale expected gaps: 0', text)
        # a listed gap its enabled checks no longer see is still stale in a partial run
        listed['groups'][0]['gaps'][0]['symbol'] = 'com.android.purebilibili.core.ui.NotThere'
        (root / gate.EXPECTED_GAPS_FILE).write_bytes(json.dumps(listed).encode('utf8'))
        code, text, _ = run_cli(root, '--no-carried')
        self.assertEqual(1, code)
        self.assertIn('stale expected gaps: 1', text)

    def test_a_mistyped_check_selection_or_names_scope_is_exit_2_never_a_green_run(self):
        root = make_repo(self)
        listed = dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.ui.Missing'),
            dict(scope='carried', check='unresolved-import', symbol='com.android.purebilibili.core.ui.GoneInArchive')])])
        (root / gate.EXPECTED_GAPS_FILE).write_bytes(json.dumps(listed).encode('utf8'))
        for checks_argument in ('duplicate', 'bogus', ',', '', 'imports,impotrs', 'Imports'):
            for gaps in (['--expected-gaps', 'none'], []):    # with the default gaps file a typo used to be green too
                with self.subTest(checks=checks_argument, gaps=gaps):
                    code, text, errors = run_cli(root, *gaps, '--checks', checks_argument)
                    self.assertEqual(2, code, text)
                    self.assertNotIn('RESULT', text)
                    self.assertIn('--checks', errors)
        # the spelled-out valid selections still run
        code, text, _ = run_cli(root, '--expected-gaps', 'none', '--no-carried', '--checks', 'duplicates')
        self.assertEqual((0, True), (code, 'RESULT: PASS' in text))
        code, text, _ = run_cli(root, '--expected-gaps', 'none', '--no-carried', '--checks', ' imports , qualified,')
        self.assertEqual((1, True), (code, 'unresolved-import=1' in text))
        # 'all' is advertised nowhere and refused by the parser (argparse exits 2)
        with self.assertRaises(SystemExit) as refused:
            run_cli(root, '--names-scope', 'all')
        self.assertEqual(2, refused.exception.code)
        done = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(TOOLS / 'check_slice_symbol_closure.py'), '--help'],
                              capture_output=True, cwd=tempfile.gettempdir())
        help_text = ' '.join(done.stdout.decode('utf8').split())
        self.assertIn('{generated,none}', help_text)
        self.assertNotIn('{generated,all,none}', help_text)
        self.assertIn('duplicates,imports,qualified,copied,names,members', help_text)

    def test_only_and_skip_producers_never_call_the_gaps_of_a_left_out_producer_stale(self):
        root = make_repo(self, silent=True)
        listed = dict(schemaVersion=1, groups=[dict(reason='r', closedBy='c', gaps=[
            dict(scope='compiled', check='unresolved-import', symbol='com.android.purebilibili.core.ui.Missing'),
            dict(scope='compiled', check='producer-output-missing', symbol='build/generated/silent'),
            dict(scope='carried', check='unresolved-import', symbol='com.android.purebilibili.core.ui.GoneInArchive')])])
        gaps_path = root / gate.EXPECTED_GAPS_FILE
        gaps_path.write_bytes(json.dumps(listed).encode('utf8'))
        code, text, _ = run_cli(root)
        self.assertEqual(0, code, text)    # the complete run accepts exactly these three gaps
        partial = (['--only-producers', 'extractSilent'], ['--skip-producers', 'extractGen'],
                   ['--only-producers', 'extractGen'], ['--skip-producers', 'extractSilent'],
                   ['--only-producers', 'extractGen, extractSilent'])
        for argv in partial:
            with self.subTest(argv=argv):
                code, text, _ = run_cli(root, *argv)
                self.assertEqual(0, code, text)
                self.assertIn('stale expected gaps: 0', text)
        # the same options with an explicit gaps file behave the same, and carried gaps stay judged
        listed['groups'][0]['gaps'][2]['symbol'] = 'com.android.purebilibili.core.ui.NotThere'
        gaps_path.write_bytes(json.dumps(listed).encode('utf8'))
        for argv in partial[:4]:
            with self.subTest(carried_gap_gone=argv):
                code, text, _ = run_cli(root, '--expected-gaps', str(gaps_path), *argv)
                self.assertEqual(1, code, text)
                self.assertIn('stale expected gaps: 1', text)
                self.assertIn('[unresolved-import] com.android.purebilibili.core.ui.NotThere', text)

    def test_a_missing_mirror_an_unknown_producer_a_timeout_or_an_unwritable_report_is_exit_2_not_findings(self):
        root = make_repo(self)
        scratch = Path(tempfile.mkdtemp(prefix='bp-t-'))
        self.addCleanup(rmtree_force, scratch)
        (scratch / 'empty').mkdir()
        cases = [
            ('missing mirror', ['--mirror', str(scratch / 'nonexistent-mirror')], 'not a mirror root'),
            ('mirror without a build file', ['--mirror', str(scratch / 'empty')], 'desktop/build.gradle.kts not found'),
            ('unknown producer to run', ['--only-producers', 'extractGenn'], '--only-producers names no task'),
            ('unknown producer to skip', ['--skip-producers', 'extractGen,typo'], '--skip-producers names no task'),
            ('repository that does not exist', ['--repo', str(scratch / 'no-repo'), '--list-producers'], 'FileNotFoundError'),
            ('unwritable report', ['--expected-gaps', 'none', '--no-carried', '--json', str(scratch / 'no/dir/r.json')],
             'FileNotFoundError'),
        ]
        for label, argv, message in cases:
            with self.subTest(label):
                code, text, errors = run_cli(root, *argv)
                self.assertEqual(2, code, text)
                self.assertTrue(errors.startswith('ERROR: '), errors)
                self.assertEqual(1, len(errors.strip().splitlines()), errors)    # one line, never a traceback
                self.assertIn(message, errors)
        # a producer that never finishes: run_producers names it, and the command line maps whatever subprocess raises
        mirror, _ = gate.make_scratch_repo(root, scratch)
        model = gate.parse_gradle((mirror / 'desktop/build.gradle.kts').read_text(encoding='utf8'))
        tasks, _skipped = gate.select_producers(model)
        with mock.patch.object(gate.subprocess, 'run', side_effect=subprocess.TimeoutExpired(['python', 'x'], 7)):
            with self.assertRaisesRegex(gate.ToolError, 'producer extractGen did not finish within 7 seconds'):
                gate.run_producers(mirror, tasks, {}, timeout=7)
        with mock.patch.object(gate, 'run_producers', side_effect=subprocess.TimeoutExpired(['python', 'x'], 7)):
            code, text, errors = run_cli(root, '--expected-gaps', 'none')
        self.assertEqual(2, code, text)
        self.assertTrue(errors.startswith('ERROR: TimeoutExpired'), errors)
        self.assertEqual(1, len(errors.strip().splitlines()))
        # the exit codes of a judged run are unchanged: 1 for findings, 0 for a pass
        self.assertEqual(1, run_cli(root, '--expected-gaps', 'none', '--no-carried')[0])


def object_exists(rev):
    done = subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', rev + '^{commit}'], capture_output=True)
    return done.returncode == 0


# A commit missing from the clone must FAIL the history-dependent classes: a skipped class prints OK, and the project
# treats skipped as not passed. Skipping them on purpose needs BP_SYMBOL_GATE_ALLOW_SHALLOW=1.
ALLOW_SHALLOW = os.environ.get('BP_SYMBOL_GATE_ALLOW_SHALLOW') == '1'


def require_history(*revisions):
    missing = [r for r in revisions if not object_exists(r)]
    if missing:
        raise AssertionError(
            'commits missing from this clone: ' + ', '.join(missing) + '. A missing commit is a failure, not a skip: '
            'fetch them (the full history of the integration branch, or git fetch origin <commit>), or set '
            'BP_SYMBOL_GATE_ALLOW_SHALLOW=1 to skip these classes on purpose.')


CLOSED_SEEDS = (
    ('com.android.purebilibili.core.ui.components', 'userVipPink'),
    ('com.android.purebilibili.core.ui.components', 'AppPressShiftContent'),
    ('com.android.purebilibili.feature.home.components', 'HomeOverlayPillButton'),
    ('com.android.purebilibili.data.model', 'resolveVideoCardStatsTexts'),
    ('com.android.purebilibili.data.model', 'resolveWatchLaterDisplayProgressState'),
    ('com.android.purebilibili.data.model', 'resolveHistoryProgressLabel'),
    ('com.android.purebilibili.data.model', 'resolveVideoDisplayProgressState'),
    ('com.android.purebilibili.feature.home', 'resolveOldContentDividerContainerColor'),
    ('com.android.purebilibili.feature.video.ui.components', 'CommentFraudFeedbackHost'),
    ('com.android.purebilibili.core.coroutines', 'AccountScopedRequests'),
    ('com.android.purebilibili.feature.home.components.cards', 'resolveWallpaperGlassAlpha'),
)
# closed by the v046 danmaku protocol slice (members and identifiers, not top-level declarations)
DANMAKU_SEEDS = ('invalidateDanmakuSegment', 'parseFailed', 'failedSegmentCount')


@unittest.skipIf(SKIP_HEAVY, 'BP_SYMBOL_GATE_SKIP_HEAVY=1')
@unittest.skipIf(ALLOW_SHALLOW and not (object_exists(V025_COMMIT) and object_exists(gate.TARGET_COMMIT)),
                 'BP_SYMBOL_GATE_ALLOW_SHALLOW=1 and the pinned upstream objects (v0.2.5, v0.4.6) are not in this clone')
class CurrentIntegrationTree(unittest.TestCase):
    """Every real producer, run exactly as Gradle runs them, in a mirror of this checkout (about 3 minutes)."""

    @classmethod
    def setUpClass(cls):
        require_history(V025_COMMIT, gate.TARGET_COMMIT)
        cls.before = gate.repo_state(REPO)
        cls.scratch = Path(tempfile.mkdtemp(prefix='bp-sc-'))
        cls.addClassCleanup(rmtree_force, gate._long(cls.scratch))   # also runs when this setUp fails half way
        cls.root, env = gate.make_scratch_repo(REPO, cls.scratch)
        cls.model = gate.parse_gradle((cls.root / 'desktop/build.gradle.kts').read_text(encoding='utf8'))
        cls.tasks, cls.skipped = gate.select_producers(cls.model)
        cls.allowed = gate.not_run_outputs(cls.model, cls.tasks, cls.skipped)
        cls.producers = dict(results=gate.run_producers(cls.root, cls.tasks, env), skipped=cls.skipped)
        cls.sources, cls.notes = gate.collect_sources(cls.root, cls.model)
        cls.expected = gate.load_expected(REPO / gate.EXPECTED_GAPS_FILE)
        cls.extra = gate.producer_failures(cls.producers['results'])
        cls.result = gate.analyze(cls.sources, cls.notes, cls.expected, gate.Options(), extra_findings=cls.extra,
                                  allowed_unproduced=cls.allowed)
        cls.after = gate.repo_state(REPO)

    def test_every_producer_ran_and_the_checkout_was_not_written(self):
        self.assertEqual([], [r['task'] for r in self.producers['results'] if r['returncode']])
        self.assertGreater(len(self.producers['results']), 100)
        self.assertEqual(sorted(gate.UNRUNNABLE_TASKS), [n for n, _ in self.skipped])
        self.assertEqual(self.before, self.after)

    def test_the_universe_has_every_expected_source_kind(self):
        counts = self.result.counts
        for key in ('main/desktop', 'main/generated', 'main/vendored', 'test/desktop', 'test/generated',
                    'carried/archive', 'other/vendored-other'):
            self.assertGreater(counts.get(key, 0), 50, key)
        self.assertEqual(['source directory not produced: build/generated/js-worker',
                          'source directory not produced: build/generated/native-diagnostic-share/kotlin'],
                         self.result.notes)
        # exactly those two are the skipped toolchain producers' directories; nothing else may be missing silently
        self.assertEqual([], [f.symbol for f in self.result.findings if f.check == 'producer-output-missing'])
        unguarded = gate.analyze(self.sources, self.notes, self.expected, gate.Options(), extra_findings=self.extra)
        self.assertEqual(['build/generated/js-worker', 'build/generated/native-diagnostic-share/kotlin'],
                         sorted(f.symbol for f in unguarded.findings if f.check == 'producer-output-missing'))

    def test_gate_passes_with_only_the_explicitly_listed_known_gaps(self):
        self.assertEqual([], [(f.check, f.symbol, f.file) for f in self.result.unexpected])
        self.assertEqual([], self.result.stale)
        self.assertEqual([], [e for e in self.result.latent if e['status'] != 'open'])
        self.assertTrue(self.result.passed)
        self.assertGreaterEqual(len(self.result.known), len(self.expected['gaps']))

    def test_the_known_gaps_are_carried_references_plus_one_stated_false_positive(self):
        by_scope = {}
        for f in self.result.known:
            by_scope.setdefault(f.scope, set()).add(f.check)
        self.assertEqual({'carried': {'unresolved-import', 'unresolved-qualified'},
                          'compiled': {'unresolved-name'}}, by_scope)
        symbols = {f.symbol for f in self.result.known}
        # closed by the identity-badges, core-data-policies and home-card-cascade-definers slices: no longer gaps
        for closed in CLOSED_SEEDS:
            self.assertNotIn(closed[0] + '.' + closed[1], symbols)
        # closed by the v046 danmaku protocol slice: retired, no longer a known gap
        self.assertNotIn('com.android.purebilibili.data.repository.DanmakuContentRepository.invalidateDanmakuSegment', symbols)

    def test_the_longest_path_a_producer_wrote_is_within_the_measured_limit(self):
        build = gate._long(self.root / 'desktop/build')
        prefix = len(gate._plain(str(self.root))) + 1
        longest = 0
        for current, _folders, files in os.walk(build):
            for name in files:
                longest = max(longest, len(gate._plain(os.path.join(current, name))) - prefix)
        self.assertGreater(longest, 100)
        self.assertLessEqual(longest, gate.LONGEST_PRODUCER_PATH,
                             'a producer now writes a longer path: raise LONGEST_PRODUCER_PATH (and re-check '
                             'MAX_SCRATCH_ROOT)')

    MISSING_PROJECT_SYMBOLS = (
        'BottomPagerPreloadedItemsSaver', 'DanmakuRenderPauseState',
        'DynamicFeedPageDataSource', 'PlaybackLoadException', 'RecentPlaybackStore', 'StartupAnimationStyle',
        'SubtitleContentRepository', 'VideoDetailPlaybackErrorNotice', 'VideoLoadStage', 'VideoLoadTimeline',
        'VideoNetworkPressureObserver')

    def test_a_missing_project_definer_is_never_taught_as_an_ambient_name(self):
        # the carried archives bare-use these eleven names, whose definers no source declares; they used to be
        # learned as ambient, so a generated file that bare-used one of them passed the gate and failed the compile
        ambient = self.result.ambient
        self.assertGreater(len(ambient), 500)
        for name in self.MISSING_PROJECT_SYMBOLS:
            with self.subTest(name=name):
                self.assertNotIn(name, ambient)
                self.assertFalse(self.result.universe.named_anywhere(name), 'now declared: drop it from this list')
        # no capitalized ambient name is one that a scanned file imports from a project package
        providers = self.result.providers
        self.assertEqual([], sorted(n for n in ambient if n[:1].isupper()
                                    and any(gate._project_package(p) for p in providers.get(n, ()))))
        # lowercase project names that only an archive uses unbound are not ambient either
        for name in ('readSeedWallpaperColorScheme', 'readSystemWallpaperSeed'):
            self.assertNotIn(name, ambient)
        # the vendored v0.2.5 files still teach stdlib, Java and library names. OverlayClip, taught only by vendored
        # files outside the compiled set, is androidx.compose.animation.SharedTransitionScope.OverlayClip reached
        # through an implicit SharedTransitionScope receiver: reviewed, not a project symbol
        for name in ('OverlayClip', 'Array', 'IllegalStateException', 'Runnable'):
            self.assertIn(name, ambient)
        # the probe: a generated file without imports that bare-uses the missing symbols is reported, library names are not
        calls = '\n'.join('    %s()' % name for name in self.MISSING_PROJECT_SYMBOLS)
        text = ('package probe\n\ninternal fun probe() {\n%s\n    MadeUpNeverExistsAnywhere()\n    OverlayClip(1)\n'
                '    IllegalStateException("x")\n}\n' % calls)
        info = gate.parse_kotlin(text, 'build/generated/probe/Probe.kt', 'generated', 'main', keep_tokens=True)
        context = gate.FileContext(self.result.universe, info, providers)
        findings = gate.check_names(self.result.universe, info, context, ambient, {}, gate.OriginalCache({}), {'names'})
        self.assertEqual(sorted(self.MISSING_PROJECT_SYMBOLS + ('MadeUpNeverExistsAnywhere',)),
                         sorted(f.symbol for f in findings if f.check == 'unresolved-name'))

    def test_the_compat_extension_retires_the_twelfth_seed_in_the_real_tree(self):
        universe = self.result.universe
        entry = dict(symbol='reducedMotion', kind='member', owner='AppBottomSheetMotion')
        self.assertTrue(gate.latent_defined(universe, entry))
        self.assertEqual([('reducedMotion', 'defined')],
                         [(e['symbol'], e['status']) for e in self.result.retired if e['symbol'] == 'reducedMotion'])
        for seed in self.expected['latent']:
            self.assertFalse(gate.latent_defined(universe, dict(seed)), seed['symbol'])

    def test_the_closed_seeds_keep_their_definers_in_the_real_tree(self):
        # slices #3, #5 and #6 carried the real v0.4.6 declarations; the gate keeps them guarded as retired seeds
        status = {e['symbol']: e['status'] for e in self.result.retired}
        for package, name in CLOSED_SEEDS:
            with self.subTest(symbol=name):
                self.assertEqual('defined', status.get(name), package)
        self.assertEqual(sorted([name for _package, name in CLOSED_SEEDS] + list(DANMAKU_SEEDS) + ['reducedMotion']),
                         sorted(status))
        for name in DANMAKU_SEEDS:
            self.assertEqual('defined', status.get(name), name)
        known = {f.symbol for f in self.result.known}
        self.assertNotIn('com.android.purebilibili.data.repository.DanmakuContentRepository.invalidateDanmakuSegment', known)

    def test_analysis_is_deterministic_and_the_report_carries_the_limits(self):
        again = gate.analyze(self.sources, self.notes, self.expected, gate.Options(), extra_findings=self.extra,
                             allowed_unproduced=self.allowed)
        self.assertEqual(gate.render_text(self.result, self.producers), gate.render_text(again, self.producers))
        text = gate.render_text(self.result, self.producers)
        self.assertIn('RESULT: PASS', text)
        self.assertIn('heuristic limits:', text)


@unittest.skipIf(SKIP_HEAVY, 'BP_SYMBOL_GATE_SKIP_HEAVY=1')
@unittest.skipIf(ALLOW_SHALLOW and not object_exists(SOURCE260_HEAD),
                 'BP_SYMBOL_GATE_ALLOW_SHALLOW=1 and the failed Source260 head is not in this clone')
class Source260History(unittest.TestCase):
    """The failed cloud compile (24 errors, head aad198489) must be flagged by class, from `git archive` alone."""

    @classmethod
    def setUpClass(cls):
        require_history(SOURCE260_HEAD)
        cls.result, cls.producers = gate.run_gate(
            REPO, revision=SOURCE260_HEAD, expected_path=None, options=gate.Options(carried=False))

    def locations(self, check, symbol):
        return [f for f in self.result.unexpected if f.check == check and f.symbol == symbol]

    def test_every_producer_of_the_failed_head_ran(self):
        self.assertEqual([], [r['task'] for r in self.producers['results'] if r['returncode']])

    def test_redeclared_bottom_input_bar_helpers_are_flagged_in_both_files(self):
        package = 'com.android.purebilibili.feature.video.ui.components.'
        for symbol in ('shouldUseFloatingLiquidBottomInputBar(Boolean)',
                       'resolveBottomInputBarContentBottomPadding(Boolean,Boolean,Boolean)'):
            hits = self.locations('duplicate-declaration', package + symbol)
            self.assertEqual(['DesktopOriginalBottomInputBar.kt', 'DesktopOriginalDetailInputPadding.kt'],
                             sorted(Path(f.file).name for f in hits), symbol)

    def test_user_vip_pink_is_flagged_at_the_four_failing_calls(self):
        hits = self.locations('unresolved-qualified', 'com.android.purebilibili.core.ui.components.userVipPink')
        self.assertEqual(4, len(hits))

    def test_app_content_colors_is_flagged_at_the_three_failing_files_and_mutable_int_state_of_once(self):
        colors = self.locations('missing-copied-import', 'com.android.purebilibili.core.ui.AppContentColors')
        self.assertEqual(3, len(colors))
        state = self.locations('missing-copied-import', 'androidx.compose.runtime.mutableIntStateOf')
        self.assertEqual(['DesktopOriginalSubReplyDetailComponents.kt'], [Path(f.file).name for f in state])

    def test_missing_reduced_motion_member_is_flagged_at_both_consumers(self):
        hits = self.locations('unresolved-member', 'AppBottomSheetMotion.reducedMotion')
        self.assertEqual(['CommentCascadingContent.kt', 'CommentContentReveal.kt'], sorted(Path(f.file).name for f in hits))

    def test_landscape_panel_use_of_an_undeclared_pinned_set_is_flagged(self):
        hits = self.locations('unresolved-name', 'pinnedReplyIds')
        self.assertEqual(['DesktopOriginalVideoContentSection.kt'], [Path(f.file).name for f in hits])

    def test_the_failed_head_fails_the_gate_in_exactly_these_classes(self):
        self.assertFalse(self.result.passed)
        self.assertEqual(['duplicate-declaration', 'missing-copied-import', 'unresolved-member', 'unresolved-name',
                          'unresolved-qualified'], checks(self.result))


if __name__ == '__main__':
    unittest.main()
