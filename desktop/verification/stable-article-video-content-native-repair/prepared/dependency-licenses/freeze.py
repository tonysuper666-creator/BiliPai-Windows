"""Prepare an additive resource-only handoff for four Content dependency licenses."""
from prepare import LANE, MAIN, safe, artifact, put, js, digest
import json, re, subprocess, xml.etree.ElementTree as ET, zipfile
inspection=json.loads(safe(LANE/'inspection.json').read_text())
rows=inspection['dependencies'];assert len(rows)==4
ns={'m':'http://maven.apache.org/POM/4.0.0'}
payloads=[];checks=[];lines=['Rich Content dependency attributions','',
    'This index was written for the BiliPai Windows distribution. It is not an upstream NOTICE file.',
    'The four unmodified binary dependencies below declare Apache License 2.0 in their published Maven POMs.',
    'Full upstream LICENSE files are included alongside this index. No upstream NOTICE file was found in',
    'the inspected binary JAR entries or the complete pinned primary repository trees.','']
for row in rows:
    name=row['coordinate'].split(':')[1];version=row['coordinate'].split(':')[2]
    repo=inspection['repositories'][row['repository']]
    raw=[v for v in repo['rawFiles'] if v['repositoryPath']=='LICENSE'];assert len(raw)==1
    b=safe(raw[0]['path']).read_bytes();assert b.lstrip().startswith(b'Apache License') and b'Version 2.0' in b
    relative=f'desktop/src/main/resources/licenses/rich-content/{name}-{version}-LICENSE.txt'
    payload=put('prepared/'+relative,b);payloads.append({'targetRelativePath':relative,**payload})
    poms=[v for v in row['pomAndModule'] if v['path'].endswith('.pom')];assert len(poms)==1
    root=ET.fromstring(safe(poms[0]['path']).read_bytes())
    declarations=[(v.find('m:name',ns).text,v.find('m:url',ns).text) for v in root.findall('m:licenses/m:license',ns)]
    assert len(declarations)==1 and 'Apache' in declarations[0][0]
    jar=row['runtimeJar']['path']
    assert artifact(jar)==row['runtimeJar']
    with zipfile.ZipFile(safe(jar)) as z:
        names=z.namelist();js('evidence/'+name+'/jar-entry-inventory.json',names)
        hits=[n for n in names if re.search(r'(^|/)(licen[cs]e|notice|copyright)(\.|/|$)',n,re.I)]
        assert not hits
    if row['tag']:
        remote=subprocess.run(['git','ls-remote','--tags','https://github.com/'+row['repository']+'.git',row['tag']],capture_output=True,text=True,check=True)
        assert remote.stdout.split()[0]==row['commit']
        put('evidence/'+name+'/tag-ls-remote.txt',remote.stdout.encode())
    assert repo['licenseNoticePaths']==['LICENSE']
    lines.extend([row['coordinate'],'Project: https://github.com/'+row['repository'],
        'Published license: '+declarations[0][0]+' ('+declarations[0][1]+')',
        'Binary SHA-256: '+row['runtimeJar']['sha256Bytes'],
        'License source: '+raw[0]['url'],
        'Included license: '+name+'-'+version+'-LICENSE.txt',
        'License association: '+repo['versionAssociation'],
        'Upstream NOTICE: none present in inspected binary JAR and pinned repository tree.',''])
    checks.append({'coordinate':row['coordinate'],'declaredLicense':declarations[0],
        'actual52JarVerified':True,'originalLicenseBytesUnchanged':digest(b)==payload['sha256Bytes'],
        'noUpstreamNoticeInJarOrPinnedFullTree':True,'repositoryCommit':repo['commit'],
        'releaseLicenseTagVerified':bool(row['tag']),'binaryBuildSourceCommitProven':False})
relative='desktop/src/main/resources/licenses/rich-content/ATTRIBUTIONS.txt'
payloads.append({'targetRelativePath':relative,**put('prepared/'+relative,('\n'.join(lines)+'\n').encode())})
js('validation.json',{'status':'PASS_FOUR_FIXED_DEPENDENCIES_ONLY','dependencies':checks,'productionChanges':0,
    'addedDependencies':0,'sharedGradle':False,'libraryBinaryModification':False,
    'note':'Markdown POM supplies version/license. Its primary repository has no 0.7.3 tag; pinned HEAD LICENSE is evidence, not a claim of exact artifact source identity.'})
js('install-recipe.json',{'kind':'additive resource copies only','payloads':payloads,
    'requiredExistingDistributionResourceRoot':'desktop/src/main/resources',
    'forbidden':'No runtime CP change, dependency rebuild, Android source or registry modification.',
    'licenseEvidenceVersion':'actual52 101 CP; four binary hashes fixed; Root may integrate into later unchanged-library cohort.'})
put('ROOT-INTEGRATION.md',b'# Four Content dependencies\n\nCopy exactly the five new prepared desktop/src/main/resources/licenses/rich-content files to Candidate at the same relative paths, preserving raw license bytes. The four runtime binary JARs were matched against actual52/101 and are unchanged. No dependency, Gradle, source registry, or UI change is part of this handoff.\n\nRichEditor and both Ksoup artifacts use verified version tags. JetBrains markdown publishes no 0.7.3 tag in the primary repository; its exact published POM declares Apache 2.0, and the raw primary repository LICENSE was fetched at recorded HEAD 82b3d42b698b20b4604afb9a4e4fdb1df442b5e4. Do not describe that HEAD as the binary source commit.\n\nNo upstream NOTICE file exists in the inspected four binary JARs or three complete pinned repository trees. ATTRIBUTIONS.txt is an authored project/version/license index, explicitly not an upstream NOTICE. All four full original LICENSE files are preserved, including the generic copyright placeholder in the JetBrains upstream license.\n')
raw=[]
for p in sorted(safe(LANE).rglob('*')):
    if p.is_file() and p.name!='frozen-handoff.json' and '__pycache__' not in p.parts:
        relative=p.relative_to(safe(LANE));raw.append(artifact(LANE/relative))
receipt={'schema':'path,sha256Bytes,size','status':'FROZEN_RAW_LICENSE_AND_NOTICE_EVIDENCE',
    'scope':'Exactly four new Content dependencies','preparedPayloads':payloads,'rawArtifacts':raw,
    'productionWrites':False,'sharedGradle':False,'newDependencies':False,'realUserData':False,
    'upstreamLicenseTextModified':False,'upstreamNoticeInvented':False,'exactMarkdownSourceCommitClaimed':False}
js('frozen-handoff.json',receipt)
print(json.dumps({'manifest':artifact(LANE/'frozen-handoff.json'),'payloads':len(payloads),'rawArtifacts':len(raw),'status':receipt['status']},indent=2))
