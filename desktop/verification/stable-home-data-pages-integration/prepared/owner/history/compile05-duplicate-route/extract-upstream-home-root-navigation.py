from pathlib import Path
import argparse,hashlib,importlib.util,json,textwrap

def load(repo):
 spec=importlib.util.spec_from_file_location('home_root_nav_parser',repo/'desktop/tools/sync-upstream.py')
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def read(repo,rel):return (repo/rel).read_text(encoding='utf-8-sig').replace('\r\n','\n')
def generate(repo,output,standalone=False):
 parser=load(repo)
 app='app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt'
 source=read(repo,app);tokens=parser.kotlin_tokens(source)
 name='resolveVideoRoutePath';i=next(i for i,t in enumerate(tokens) if t[0]==name and i>0 and tokens[i-1][0]=='fun')
 begin=source.rfind('\n',0,tokens[i-1][1])+1
 j=i
 while tokens[j][0]!='{':j+=1
 depth=1;k=j+1
 while depth:
  depth+=(tokens[k][0]=='{')-(tokens[k][0]=='}');k+=1
 body=textwrap.dedent(source[begin:tokens[k-1][2]])
 base='const val base = "video"'
 assert source.count(base)==1
 text='// Original source '+app+'; LF SHA256 '+hashlib.sha256(source.encode()).hexdigest()+'\n'
 text+='package com.android.purebilibili.navigation\n\nobject VideoRoute {\n    '+base+'\n'+textwrap.indent(body,'    ')+'\n}\n'
 folder=output/'com/android/purebilibili/navigation';folder.mkdir(parents=True,exist_ok=True)
 (folder/'DesktopHomeVideoRoute.kt').write_text(text,encoding='utf-8',newline='\n')
 direct='app/src/main/java/com/android/purebilibili/navigation/HomeVideoNavigationPolicy.kt'
 direct_body=read(repo,direct)
 if standalone:(folder/'HomeVideoNavigationPolicy.kt').write_text(direct_body,encoding='utf-8',newline='\n')
 return [{'path':app,'sha256LF':hashlib.sha256(source.encode()).hexdigest(),'selected':['VideoRoute.base','VideoRoute.resolveVideoRoutePath'],'mode':'policy-extract'},
  {'path':direct,'sha256LF':hashlib.sha256(direct_body.encode()).hexdigest(),'mode':'direct','production':'prepareUpstreamSources only; --standalone emits temporary exact body'}]
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--standalone',action='store_true')
 a=p.parse_args();report=generate(a.repo.resolve(),a.output.resolve(),a.standalone)
 print(json.dumps(report,indent=2))
