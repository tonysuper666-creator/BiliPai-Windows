from pathlib import Path
import collections,re,sys
p=Path(__file__).resolve().parent/'runs'/sys.argv[1]/'compile.log'
t=p.read_text();print('\n'.join(n+' '+str(c) for n,c in collections.Counter(re.findall(r"unresolved reference '([^']+)'",t)).items()));print('ERRORS',t.count('error:'))
