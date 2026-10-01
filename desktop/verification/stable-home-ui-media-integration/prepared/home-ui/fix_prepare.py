from pathlib import Path
p=Path(__file__).parent/'prepare.py';s=p.read_text(encoding='utf-8-sig');audit=(p.parent/'audit.py').read_text(encoding='utf-8-sig')
f=audit[audit.index('def declarations(s):'):audit.index('originals={};symbols={}')]
s=s.replace("rows=json.loads",f+"\nrows=json.loads")
s=s.replace("body=appearance.declarations(parser,s,ns)","body='\\n\\n'.join(d for n,d in declarations(s) if n in ns)+'\\n'")
s=s.replace("appearance.declarations(parser,s,['MutedHeroVideoPlayer']).strip()","next(d for n,d in declarations(s) if n=='MutedHeroVideoPlayer')")
s=s.replace("appearance.declarations(parser,s,['DisposableVideoPlayer']).strip()","next(d for n,d in declarations(s) if n=='DisposableVideoPlayer')")
p.write_text(s,encoding='utf-8',newline='\n')
