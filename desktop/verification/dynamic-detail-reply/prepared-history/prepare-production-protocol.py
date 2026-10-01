"""Mechanical installable producers, verified against frozen selected bodies.

Never executes or rewrites either historical producer or its frozen artifacts.
The production tools take only repo/output arguments, without snapshot/.local
runtime or testing dependencies. Outputs preserve frozen helpers+fragments.
"""
from pathlib import Path
import ast,hashlib,importlib.util,json,textwrap
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
def safe(p):return Path('\\\\?\\'+str(p.absolute()).removeprefix('\\\\?\\'))
def read(p):return safe(p).read_text(encoding='utf-8')
def write(p,s):
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
original=read(HERE/'protocol/produce.py')
tree=ast.parse(original)
lexers='\n\n'.join(ast.get_source_segment(original,n) for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in {'masked','balanced','fun_span','drop_logs'})
assert lexers.count('\ndef ')==3
common='''from pathlib import Path
import hashlib,re,subprocess,json
TAG='v0.2.3-alpha.9'
'''
comment_body=original[original.index('def safe('):original.index('with zipfile.ZipFile(PIN/')]
comment_body='\n'.join(line for line in comment_body.splitlines() if not (line.startswith('assert ') and 'PIN/' in line))+'\n'
cli='''if __name__=='__main__':
    import argparse
    cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True)
    args=cli.parse_args();generate(args.repo,args.output)
'''
def producer(prefix,body):
    # AST wrapping preserves multiline Kotlin template literal values exactly;
    # indenting Python source text would accidentally indent their contents.
    stub=ast.parse('def generate(repo:Path,output:Path):\n    pass').body[0]
    stub.body=ast.parse('ROOT=repo\nHERE=output\n'+body+"\nreturn sorted(HERE.rglob('generated/**/*.kt'))").body
    module=ast.Module(body=ast.parse(prefix).body+[stub]+ast.parse(cli).body,type_ignores=[])
    return '"""Original selected protocol producer; no snapshot or .local dependency."""\n'+ast.unparse(ast.fix_missing_locations(module))+'\n'
comment_tool=producer(common+lexers,comment_body)
tools=HERE/'prepared/desktop/tools'
write(tools/'extract-upstream-dynamic-reply-protocol.py',comment_tool)
detail=read(HERE/'protocol/detail-prepared/produce.py')
detail_body=detail[detail.index('def safe('):detail.index("pin=ROOT/'desktop/.local/")]
start=detail_body.index('# Reuse frozen producer lexer functions only;')
end=detail_body.index('records=[]',start)
detail_body=detail_body[:start]+'''# Shared exact lexer only; no frozen test or local producer dependency.
import importlib.util
spec=importlib.util.spec_from_file_location('original_reply_protocol_lexer',Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'))
protocol=importlib.util.module_from_spec(spec);spec.loader.exec_module(protocol)
masked=protocol.masked;balanced=protocol.balanced;fun_span=protocol.fun_span;drop_logs=protocol.drop_logs
'''+detail_body[end:]
detail_tool=producer(common,detail_body)
write(tools/'extract-upstream-dynamic-detail-protocol.py',detail_tool)
records=[]
for name,original_dir,tool in [('comment',HERE/'protocol','extract-upstream-dynamic-reply-protocol.py'),('detail',HERE/'protocol/detail-prepared','extract-upstream-dynamic-detail-protocol.py')]:
    output=HERE/'production-equivalence'/name
    spec=importlib.util.spec_from_file_location('production_'+name,tools/tool);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    module.generate(REPO,output)
    candidates=list(safe(original_dir/'generated').rglob('*.kt'))+[safe(original_dir/('DesktopDynamicCommentOperations.fragment.kt' if name=='comment' else 'DesktopDynamicDetailOperations.fragment.kt'))]
    for candidate in candidates:
        rel=candidate.relative_to(safe(original_dir));current=output/rel
        assert sha(candidate)==sha(current),(candidate,current)
        records.append(dict(family=name,path=rel.as_posix(),sha256Bytes=sha(current),equalFrozenBytes=True))
write(HERE/'production-equivalence/evidence.json',json.dumps(dict(passed=True,helpers=6,fragments=2,records=records,MainIntegration=False,HTTP=False,sharedGradle=False),indent=2)+'\n')
print(json.dumps(dict(passed=True,helpers=6,fragments=2)))
