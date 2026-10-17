from pathlib import Path
import importlib.util
import sys
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
PLAYER = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/player'


class WindowsAudioFocusSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('audio_focus_real_source', REPO / 'desktop/tools/extract-upstream-media.py')
        cls.helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.helper)
        cls.parser = cls.helper.parser_for(REPO)
        cls.native = (PLAYER / 'MpvPlayer.kt').read_text(encoding='utf8')

    def method(self, name):
        return self.helper.function(self.native, name, self.parser)

    def test_actual_native_loop_advances_lease_without_blocking_or_an_extra_worker(self):
        run = self.method('run')
        self.assertIn('advanceWindowsAudioFocus(native, handle)', run)
        self.assertLess(run.index('receiveEvent(native, handle, event)'), run.index('advanceWindowsAudioFocus(native, handle)'))
        focus = self.method('advanceWindowsAudioFocus')
        self.assertIn('perform(native, handle, pending)', focus)
        self.assertIn('admitPresentation(source) { synchronized(lock)', focus)
        for forbidden in ['Thread.sleep', '.await(', '.join(', 'runBlocking', 'Dispatchers.IO', 'MpvPlayer(']:
            self.assertNotIn(forbidden, focus)

    def test_real_load_is_gated_before_device_reconfiguration_and_keeps_original_publication(self):
        perform = self.method('perform')
        branch = perform[perform.index('is Action.Load ->'):perform.index('is Action.HardwareDecoding ->')]
        self.assertLess(branch.index('audioOutputFocus.beginOpen('), branch.index('applyWindowsAudioOutputForLoad(native, handle,'))
        self.assertLess(branch.index('audioOutputFocus.activate('), branch.index('audioOutputFocus.beginOpen('))
        self.assertLess(branch.index('audioOutputFocus.beginOpen('), branch.index('native.mpv_command_node(handle, args, null)'))
        self.assertIn('pendingAudioFocusLoad = action', branch)
        self.assertIn('admission.admit(command)', branch)
        self.assertIn('action.version != sourceVersion || action.revision != playbackRevision', branch)
        self.assertIn('fileOptions +', branch)
        self.assertIn('if (state.value.paused) "yes" else "no"', branch)

    def test_parking_reads_real_output_and_never_deselects_tracks_or_replaces_the_media(self):
        park = self.method('parkWindowsAudioOutput')
        self.assertIn('native.mpv_set_property_string(handle, "audio-device", "null")', park)
        self.assertIn('native.mpv_get_property(handle, name, 1, buffer)', park)
        for key in ['options/audio-device', 'current-ao', 'audio-out-params/format']:
            self.assertIn('read("' + key + '")', park)
        self.assertIn('++audioFocusSequence, proof', park)
        for forbidden in ['"aid"', '"loadfile"', '"stop"', 'sourceVersion++', 'playbackRevision++', '"mute"', '"volume"']:
            self.assertNotIn(forbidden, park)
        self.assertIn('audioOutputFocus.isReleased(audioFocusSession)', park)

    def test_resume_uses_applied_file_preferences_and_destruction_ack_follows_native_destroy(self):
        advance = self.method('advanceWindowsAudioFocus')
        self.assertIn('appliedWindowsAudioOutput.nativeOptions()', advance)
        self.assertNotIn('requestedWindowsAudioOutput.nativeOptions()', advance)
        self.assertNotIn('applyWindowsAudioOutputForLoad', advance)
        self.assertLess(advance.index('audioOutputFocus.beginOpen('), advance.index('appliedWindowsAudioOutput.nativeOptions()'))
        run = self.method('run')
        self.assertEqual(1, run.count('audioOutputFocus::acknowledgeDestroyed'))
        self.assertLess(run.index('native.mpv_terminate_destroy(handle)'), run.index('audioOutputFocus::acknowledgeDestroyed'))
        self.assertIn('!windowsAudioFocusWaiting', self.method('refreshState'))
        self.assertIn('5_000_000_000L', self.method('checkWindowsAudioFocusTimeout'))

    def test_paused_surface_restoration_prepares_the_actual_file_without_stealing_physical_output(self):
        perform = self.method('perform')
        branch = perform[perform.index('is Action.Load ->'):perform.index('is Action.HardwareDecoding ->')]
        self.assertIn('val parkedLoad = focusSession != null && state.value.paused', branch)
        self.assertIn('if (focusSession != null && !parkedLoad)', branch)
        self.assertIn('applyWindowsAudioOutputForLoad(native, handle, parked = parkedLoad)', branch)
        options = self.method('applyWindowsAudioOutputForLoad')
        self.assertIn('if (parked) it + ("audio-device" to "null") else it', options)
        advance = self.method('advanceWindowsAudioFocus')
        self.assertLess(advance.index('if (state.value.paused && windowsAudioFocusLoadRevision == null)'),
                        advance.index('audioOutputFocus.activate(intent, nativeSession)'))

    def test_explicit_parking_never_weakens_real_output_failure_or_counts_as_exclusive_success(self):
        options = (PLAYER / 'DesktopWindowsAudioOutput.kt').read_text(encoding='utf8')
        self.assertIn('"audio-fallback-to-null" to "no"', options)
        self.assertIn('"stop-playback-on-init-failure" to if (exclusive) "yes" else "no"', options)
        labels = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsAudioOutputSettings.kt').read_text(encoding='utf8')
        for phase in ['WAITING_FOR_RELEASE', 'RELEASING', 'RELEASED']:
            self.assertIn('DesktopWindowsAudioOutputPhase.' + phase + ' ->', labels)
        native = self.native
        self.assertIn('if (useNullAudioOutput) null else audioOutputFocus.registerActor()', native)
        self.assertIn('DesktopWindowsAudioOutputFocus.process', native)
        controller = (PLAYER / 'DesktopWindowsAudioOutputController.kt').read_text(encoding='utf8')
        self.assertIn('DesktopWindowsAudioOutputPhase.RELEASED,', controller)

    def test_new_kotlin_and_native_changes_have_balanced_lexical_delimiters(self):
        files = [PLAYER / 'MpvPlayer.kt', PLAYER / 'DesktopWindowsAudioOutputFocus.kt',
                 REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/player/DesktopWindowsAudioOutputFocusTest.kt',
                 REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/player/DesktopWindowsAudioOutputProtocolTest.kt']
        for path in files:
            stack = []
            for token, begin, _ in self.parser.kotlin_tokens(path.read_text(encoding='utf8')):
                if token in ['(', '[', '{']:
                    stack.append(token)
                elif token in [')', ']', '}']:
                    self.assertTrue(stack, (path.name, begin, token))
                    self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (path.name, begin))
            self.assertEqual([], stack, path.name)


if __name__ == '__main__':
    unittest.main()
