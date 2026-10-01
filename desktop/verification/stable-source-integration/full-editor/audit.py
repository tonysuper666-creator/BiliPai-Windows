exec((__import__('pathlib').Path(__file__).parent/'prepare.py').read_text(encoding='utf-8').split("path = 'desktop/tools/extract-upstream-dynamic-editor.py'")[0])
import re
span_parser = module('full_editor_span', REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
lexer = module('full_editor_lexer', REPO/'desktop/tools/sync-upstream.py')
generic = read(REPO/'desktop/.local/stable-dynamic-protocol-rebase/audit.py')
exec(generic[generic.index('def functions(text):'):generic.index('manifest=json.loads')])
identities = json.loads(read(HERE/'generated/source-identity.json'))
paths = [r['path'] for r in identities] + [
    'app/src/main/java/com/android/purebilibili/core/util/GalleryVisualMediaContracts.kt',
    'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentInputBar.kt']
pin_reader = module('full_editor_identity', REPO/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
stable_sources, all_identities = pin_reader.load_pinned_sources(REPO, paths)
method_rows = []; file_rows = []
for path in paths:
    before = subprocess.check_output(['git','-c','core.longpaths=true','show',ALPHA+':'+path],cwd=REPO).decode('utf-8').replace('\r\n','\n')
    after = stable_sources[path]
    write(HERE/'original-alpha9'/path,before);write(HERE/'original-stable'/path,after)
    a = functions(before); b = functions(after); changes=[]
    for method in sorted(set(a)|set(b)):
        old=a.get(method);new=b.get(method)
        status='added' if old is None else 'removed' if new is None else 'unchanged' if old['tokenSha256']==new['tokenSha256'] else 'changed'
        method_rows.append(dict(path=path,method=method,status=status,alpha9=old,stable=new))
        if status!='unchanged':
            changes.append(method)
            write(HERE/'method-diffs'/Path(path).stem/(method.replace('.','_')+'.diff'),''.join(difflib.unified_diff(
                (old or {}).get('originalText','').splitlines(True),(new or {}).get('originalText','').splitlines(True),
                fromfile='alpha9:'+path+':'+method,tofile='v023:'+path+':'+method)))
    file_rows.append(dict(path=path,alpha9LfSha256=sha(before),stableLfSha256=sha(after),
        fullFileLfEqual=before==after,stableFunctions=len(b),changedOrAddedFunctions=changes))
dump(HERE/'method-token-original-diff.json',dict(alpha9Commit=ALPHA,stableCommit=STABLE,methodCount=len(method_rows),
    unchanged=sum(r['status']=='unchanged' for r in method_rows),changed=sum(r['status']=='changed' for r in method_rows),
    added=sum(r['status']=='added' for r in method_rows),removed=sum(r['status']=='removed' for r in method_rows),methods=method_rows))
dump(HERE/'source-delta.json',dict(alpha9Commit=ALPHA,stableCommit=STABLE,sourceIdentities=all_identities,sources=file_rows))

generated = json.loads(read(HERE/'generated-inventory.json'))
composer_path='app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicPublishComposer.kt'
source = stable_sources[composer_path]
target = next(r['path'] for r in generated if r['path'].endswith('DesktopOriginalDynamicPublishComposer.kt'))
body = read(target)
body = body[body.index('private const val MAX_DYNAMIC_IMAGES'):].strip() + '\n'
body = replace(body,'    val platform = LocalDesktopDynamicEditorBindings.current\n','')
call='platform.pickImages(MAX_DYNAMIC_IMAGES) { uris ->\n' + \
    '                                            if (platform.isOwned() && uris.isNotEmpty()) {\n' + \
    '                                                imageUris = (imageUris + uris.map { it.toString() }).distinct().take(MAX_DYNAMIC_IMAGES)\n' + \
    '                                            }\n                                        }'
assert body.count(call)==2
body = body.replace(call,'picker.launch(\n                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)\n                                        )',1)
body = body.replace(call,'picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))',1)
start=source.index('    val picker = rememberLauncherForActivityResult(');end=source.index('\n    val canPublish =',start)
picker=source[start:end]
body=replace(body,'\n\n    val canPublish =','\n'+picker+'\n    val canPublish =')
original_body=source[source.index('private const val MAX_DYNAMIC_IMAGES'):].strip()+'\n'
assert body==original_body
assert 'private const val MAX_DYNAMIC_IMAGES = 18' in body
assert body.count('ModalBottomSheet(')==1
assert not any(t in body for t in ['AppAlertDialog(', 'if (liquidGlassEnabled)', 'AppNativeSegmentedControl('])
write(HERE/'full-composer-reconstituted-original-body.kt',body)
dump(HERE/'full-composer-retention-proof.json',dict(passed=True,source=composer_path,
    fixedStableCommit=STABLE,entireOriginalBodyLfSha256=sha(original_body),reconstitutedBodyLfSha256=sha(body),
    fullOriginalBodyByteEqualAfterReversingOnlyActivityResultSeams=True,
    substitutions=['one current platform capture','two owned gallery callback calls','original Android activity-result launcher moved to the existing Windows callback'],
    sourceMaximumImages=18,fullWindowSheet=True,originalHeaderAndSubmitDraftAndFormAndFooterAndSubdialogsRetained=True,
    UIInteractionMeasured=False,MainChanged=False))
retained_rows=[dict(path=composer_path,entireOriginalBodyLfSha256=sha(original_body),reconstitutedBodyLfSha256=sha(body),byteEqual=True)]
for stem in ['DynamicPublishPickers','DynamicCreateVoteDialog','DynamicCreateReserveDialog']:
    path='app/src/main/java/com/android/purebilibili/feature/dynamic/components/'+stem+'.kt'
    original=stable_sources[path]; target=next(r['path'] for r in generated if r['path'].endswith('DesktopOriginal'+stem+'.kt'))
    reconstructed=read(target); reconstructed=reconstructed[reconstructed.index('@Composable'):].strip()+'\n'
    if stem=='DynamicPublishPickers':
        reconstructed=replace(reconstructed,'    val platform = LocalDesktopDynamicEditorBindings.current\n','',3)
        reconstructed=reconstructed.replace('platform.searchMentionUsers','CommentRepository.searchMentionUsers').replace('platform.searchPublishTopics','DynamicCreateRepository.searchPublishTopics').replace('platform.emotes.','DynamicEmoteCatalog.')
    elif stem=='DynamicCreateVoteDialog':
        reconstructed=replace(reconstructed,'    val platform = LocalDesktopDynamicEditorBindings.current\n','')
        reconstructed=reconstructed.replace('platform.createVote','DynamicCreateRepository.createVote').replace('durationDays = durationDays','durationSeconds = durationDays * 24 * 60 * 60')
    else:
        reconstructed=replace(reconstructed,'    val platform = LocalDesktopDynamicEditorBindings.current','    val context = LocalContext.current')
        reconstructed=reconstructed.replace('platform.createReserve','DynamicCreateRepository.createReserve')
        a=original.index('                        val calendar = Calendar.getInstance().apply { timeInMillis = startAtMillis }');b=original.index('\n                    }',a)
        x=reconstructed.index('                        platform.chooseDateAndTime(startAtMillis)');y=reconstructed.index('\n                    }',x)
        reconstructed=reconstructed[:x]+original[a:b]+reconstructed[y:]
    original_body=original[original.index('@Composable'):].strip()+'\n'
    assert reconstructed==original_body,path
    write(HERE/'reconstituted-original-bodies'/(stem+'.kt'),reconstructed)
    retained_rows.append(dict(path=path,entireOriginalBodyLfSha256=sha(original_body),reconstitutedBodyLfSha256=sha(reconstructed),byteEqual=True))
dump(HERE/'full-editor-ui-retention-proof.json',dict(passed=True,fixedStableCommit=STABLE,
    fullOriginalBodyReconstitutedLfByteEqualForAllFourUiFiles=True,sources=retained_rows,
    reversedPlatformSeams=['Android gallery activity callback','existing current Operations and emote catalog binding','Android date/time dialogs mapped to existing Windows callback'],
    renderersStripped=False,secondEditor=False,newHttpOrStore=False,UIInteractionMeasured=False))
comment_path='app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCommentSheet.kt'
comment=functions(stable_sources[comment_path])['DynamicCommentComposer#1']
dump(HERE/'comment-image-boundary-audit.json',dict(fixedStableCommit=STABLE,originalCommentComposer=comment,
    sourceImageCapacity=9,bodyNewSinceAlpha9=next(r['status'] for r in method_rows if r['path']==comment_path and r['method']=='DynamicCommentComposer#1'),
    owner='existing mounted comment platform and reply session; separate from current editor modal owner',
    producerOwner='dynamic_action_review lane owns extract-upstream-dynamic-reply.py; no comment UI consumer emitted in this lane',
    existingSelectedImagesProvider='same owned file URI selector and one-shot RequestBody seam; reusable adapter with maxItems=9 for comments and 18 for publishing',
    replyPicturesUploadAcceptanceProvenHere=False,MainChanged=False))
dump(HERE/'selected-editor-source-summary.json',dict(fixedStableCommit=STABLE,
    fullEditorMethods=[r for r in method_rows if r['path'] in [composer_path,
        'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicPublishPickers.kt',
        'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCreateVoteDialog.kt',
        'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCreateReserveDialog.kt']],
    otherSelectedProducerBodies='The unchanged selection boundaries emit current fixed stable original bodies for post-publish verification, comment header/models and rich-text/layout policies.',
    sourceTestLimitation='The stable Android DynamicPublishComposerPolicyTest still asserts removed alpha liquid dialog anchors. It is retained as source evidence, not used to replace the new stable full-window UI.',
    fullOriginalComposerRetentionReceipt='full-composer-retention-proof.json',commentImageScopeReceipt='comment-image-boundary-audit.json'))
test='app/src/test/java/com/android/purebilibili/feature/dynamic/components/DynamicPublishComposerPolicyTest.kt'
test_source=subprocess.check_output(['git','-c','core.longpaths=true','show',STABLE+':'+test],cwd=REPO).decode('utf-8').replace('\r\n','\n')
write(HERE/'original-stable'/test,test_source)
print(json.dumps({'fixedSourceFiles':len(paths),'methodCount':len(method_rows),'fullOriginalComposerReconstitutedEqual':True,
    'editorMethods':[(r['method'],r['status']) for r in method_rows if r['path'] in [composer_path,
        'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicPublishPickers.kt',
        'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCreateVoteDialog.kt',
        'app/src/main/java/com/android/purebilibili/feature/dynamic/components/DynamicCreateReserveDialog.kt']]},indent=2))
