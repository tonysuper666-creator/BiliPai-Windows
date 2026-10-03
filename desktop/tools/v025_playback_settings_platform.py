"""Narrow Windows publication adaptations; every original callback body remains visible.
No output file/store authority. Root may import this exact module from both producers.
"""
def once(text, before, after):
    assert text.count(before) == 1, (before, text.count(before))
    return text.replace(before, after, 1)

OP='com.bilipai.desktop.ui.DesktopOriginalPlaybackPreferenceOperation'

def original_setters(text, required_names=None):
    # Use the existing source token scanner: braces in strings/comments are not code.
    import importlib.util, re, sys
    from pathlib import Path
    p=Path(__file__).resolve().parent/'sync-upstream.py'
    if not p.exists():
        # Main-only replay imports the same explicitly selected repository tools.
        p=next((Path(d)/'sync-upstream.py' for d in sys.path
                if (Path(d)/'sync-upstream.py').is_file()),p)
    assert p.is_file(), str(p)
    spec=importlib.util.spec_from_file_location('playback_original_setter_tokens',p)
    parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
    def paired(tokens,index):
        opening=tokens[index][0];closing={'(':')','{':'}'}[opening];depth=1
        for finish in range(index+1,len(tokens)):
            depth+=(tokens[finish][0]==opening)-(tokens[finish][0]==closing)
            if depth==0:return finish
        raise AssertionError((opening,index))
    rows=[]
    for match in re.finditer(r'\bsuspend fun\s+(\w+)\s*\(',text):
        name=match.group(1)
        if required_names is not None and name not in required_names:continue
        tokens=parser.kotlin_tokens(text[match.start():])
        paren=next(i for i,t in enumerate(tokens) if t[0]=='(')
        close=paired(tokens,paren)
        begin=close+1
        # All fixed original setters selected here have block bodies and Context parameter.
        while tokens[begin][0]!='{':begin+=1
        finish=paired(tokens,begin)
        start=match.start()+tokens[begin][2]
        end=match.start()+tokens[finish][1]
        body=text[start:end]
        assert not re.search(r'(?m)^\s*return(?:\s|$)',body),(name,'non-local return')
        rows.append((name,start,end,body))
    assert rows
    if required_names is not None:assert set(r[0] for r in rows)==set(required_names)
    for name,start,end,body in reversed(rows):
        # Preserve the complete original body verbatim between narrow entry/exit hooks.
        text=text[:start]+'\n        '+OP+'.runOriginalSetter(context) {'+body+'\n        }\n    '+text[end:]
    return text
def control_settings_body(text):
    text=once(text,'''    private fun rememberPlaybackCompletionBehavior(behavior: PlaybackCompletionBehavior) {
        playbackCompletionBehaviorMemoryCache = behavior.value
    }''','''    private fun rememberPlaybackCompletionBehavior(behavior: PlaybackCompletionBehavior) {
        // Windows synchronous readers use the sole Store's published canonical/mirror values.
        // The Android process cache must not be published ahead of its original DataStore edit.
        Unit
    }''')
    anchor='''    private fun healPlaybackCompletionSharedPreferences(
        context: Context,
        behavior: PlaybackCompletionBehavior,
    ) {
'''
    text=once(text,anchor,anchor+'''        if ('''+OP+'''.deferMirrorProjection(context) { fresh ->
                healPlaybackCompletionSharedPreferences(context, PlaybackCompletionBehavior.fromValue(
                    fresh[KEY_PLAYBACK_COMPLETION_BEHAVIOR]
                        ?: PlaybackCompletionBehavior.CONTINUE_CURRENT_LOGIC.value))
            }) return
''')
    original='''            .onEach { behavior ->
                // Flow（UI）与 Sync（播完回调）对齐：缓存 + 回写 SP，修复「界面顺序播放、实际单循」.
                rememberPlaybackCompletionBehavior(behavior)
                healPlaybackCompletionSharedPreferences(context, behavior)
            }'''
    text=once(text,original,'''            .onEach { behavior ->
                '''+OP+'''.runMirrorHealing(context) {
                    // Flow（UI）与 Sync（播完回调）对齐：缓存 + 回写 SP，修复「界面顺序播放、实际单循」.
                    rememberPlaybackCompletionBehavior(behavior)
                    healPlaybackCompletionSharedPreferences(context, behavior)
                }
            }''')
    if 'memoryCacheValue = playbackCompletionBehaviorMemoryCache,' in text:
        text=once(text,'memoryCacheValue = playbackCompletionBehaviorMemoryCache,',
                  'memoryCacheValue = (context.pluginContext.store.preferences("settings")["playback_completion_behavior"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull(),')
    # Both standalone UI and existing original video controls have status-bar healing.
    for parameter,key,default,originalBody in [
        ('value','KEY_AUDIO_NOW_PLAYING_BAR_ENABLED','true', '''            context.getSharedPreferences("mini_player", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("audio_now_playing_bar_enabled", value)
                .apply()'''),
        ('enabledFromDataStore','KEY_HIDE_VIDEO_PAGE_STATUS_BAR','false', '''            context.getSharedPreferences(VIDEO_PAGE_STATUS_BAR_CACHE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(CACHE_KEY_HIDE_VIDEO_PAGE_STATUS_BAR, enabledFromDataStore)
                .apply()'''),
    ]:
        original='        .onEach { '+parameter+' ->\n'+originalBody+'\n        }'
        if original not in text:
            assert parameter == 'value', ('Missing original status-bar callback',text.count(original))
            continue
        text=once(text,original,'        .onEach { '+parameter+' ->\n'
                  '            '+OP+'.runMirrorHealing(context) {\n'
                  '                '+OP+'.deferMirrorProjection(context) { fresh ->\n'
                  '                    val '+parameter+' = fresh['+key+'] ?: '+default+'\n'
                  +'\n'.join('        '+line for line in originalBody.splitlines())+'\n'
                  '                }\n            }\n        }')
    if 'suspend fun setPlaybackCompletionBehavior(' in text:
        text=original_setters(text,{'setPlaybackCompletionBehavior'})
    return text

def player_settings_body(text):
    anchor='''    internal fun syncPlaybackSpeedCache(context: Context, preferences: Preferences) {
'''
    text=once(text,anchor,anchor+'''        if ('''+OP+'''.deferMirrorProjection(context) { fresh ->
                syncPlaybackSpeedCache(context, fresh)
            }) return
''')
    return original_setters(text)
