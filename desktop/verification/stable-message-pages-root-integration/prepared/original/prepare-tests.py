from pathlib import Path
import re,importlib.util
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
spec=importlib.util.spec_from_file_location('test_parser',C/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
s=(P/'fixture/DesktopOriginalMessagePagesFixture.kt').read_text(encoding='utf8')
prefix=s[:s.index('fun main(args:')].replace('internal data class Hit(','private data class MessagePageTestHit(').replace('internal data class Reply(','private data class MessagePageTestReply(').replace('internal class Fixture :','private class MessagePageTestFixture :')
prefix=re.sub(r'\bHit\b','MessagePageTestHit',prefix);prefix=re.sub(r'\bReply\b','MessagePageTestReply',prefix);prefix=re.sub(r'\bFixture\b','MessagePageTestFixture',prefix)
prefix=prefix.replace('import kotlinx.coroutines.*','import kotlinx.coroutines.*\nimport kotlin.test.Test')
cases=[];tokens=parser.kotlin_tokens(s)
for n,m in enumerate(re.finditer(r'    test\("([^"\n]+)"\) \{ f ->',s),1):
 i=next(i for i,(_,begin,_)in enumerate(tokens)if begin>=m.start())
 while tokens[i][0]!='{':i+=1
 start=s.index('f ->',tokens[i][1])+len('f ->');depth=1
 while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
 body=s[start:tokens[i][1]]
 body=re.sub(r'\bReply\b','MessagePageTestReply',body)
 words=re.findall(r'[A-Za-z0-9]+',m.group(1));name=words[0].lower()+''.join(w[:1].upper()+w[1:]for w in words[1:])
 cases.append('    @Test fun '+name+'(): Unit = runBlocking {\n        // '+m.group(1)+'\n        MessagePageTestFixture().use { f ->'+body+'        }\n    }\n')
assert len(cases)==14,len(cases)
out=P/'prepared/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalMessagePagesTest.kt';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(prefix+'\nclass DesktopOriginalMessagePagesTest {\n'+''.join(cases)+'}\n',encoding='utf8',newline='\n')
print('14 meaningful JUnit methods, same executed fixture bodies; production JUnit execution not claimed')
