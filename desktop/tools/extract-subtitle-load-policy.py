"""Extract original UI-free subtitle retry and cue-quality decisions without changing their bodies."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse
import hashlib
import json
import importlib.util

SOURCE='app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
FUNCTIONS=('buildSubtitleTrackBindingKey','shouldRetrySubtitleLoadWithPlayerInfo','isLikelyLowQualitySubtitleTrack','resolveSubtitleTrackLoadDecision')
def read(repo): return (_desktop_canonical_source(repo, SOURCE)).read_text(encoding='utf-8').replace('\r\n','\n')
def selectors(repo):
    spec=importlib.util.spec_from_file_location('subtitle_original_selector',repo/'desktop/tools/extract-upstream-media.py')
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module,module.parser_for(repo)
def generate(repo,output):
    source=read(repo)
    selector,parser=selectors(repo)
    declaration=selector.data_class(source,'SubtitleTrackLoadDecision',parser)
    text='// Original source: '+SOURCE+'\n// LF SHA-256: '+hashlib.sha256(source.encode()).hexdigest()+'\n'
    text+='package com.android.purebilibili.feature.video.viewmodel\n\n'
    text+='import com.android.purebilibili.feature.video.subtitle.SubtitleCue\n\n'
    text+='import com.android.purebilibili.feature.video.subtitle.normalizeBilibiliSubtitleUrl\n\n'
    text+=declaration+'\n\n'+'\n\n'.join(selector.function(source,name,parser) for name in FUNCTIONS)+'\n'
    target=output/'com/android/purebilibili/feature/video/viewmodel/SubtitleTrackLoadPolicy.kt'
    target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8')
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo',type=Path,required=True)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--inventory',action='store_true')
    args=parser.parse_args()
    if args.inventory: print(json.dumps([{'path':SOURCE,'mode':'extracted','features':['playback','subtitle'],
        'sha256':hashlib.sha256(read(args.repo).encode()).hexdigest(),'symbols':['SubtitleTrackLoadDecision',*FUNCTIONS]}],indent=2))
    else:
        assert args.output;generate(args.repo,args.output)
